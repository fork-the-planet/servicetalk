/*
 * Copyright © 2021 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.concurrent.api;

import io.servicetalk.concurrent.api.DefaultPriorityQueue.Node;
import io.servicetalk.concurrent.internal.ArrayUtils;
import io.servicetalk.concurrent.internal.DelayedSubscription;
import io.servicetalk.concurrent.internal.RejectedSubscribeException;
import io.servicetalk.concurrent.internal.TerminalNotification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BiConsumer;
import java.util.function.Function;
import javax.annotation.Nullable;

import static io.servicetalk.concurrent.api.Completable.completed;
import static io.servicetalk.concurrent.api.PublishAndSubscribeOnPublishers.deliverOnSubscribeAndOnError;
import static io.servicetalk.concurrent.internal.ConcurrentUtils.releaseLock;
import static io.servicetalk.concurrent.internal.ConcurrentUtils.tryAcquireLock;
import static io.servicetalk.concurrent.internal.FlowControlUtils.addWithOverflowProtection;
import static io.servicetalk.concurrent.internal.SubscriberUtils.deliverCompleteFromSource;
import static io.servicetalk.concurrent.internal.SubscriberUtils.deliverErrorFromSource;
import static io.servicetalk.concurrent.internal.SubscriberUtils.isRequestNValid;
import static io.servicetalk.concurrent.internal.SubscriberUtils.safeOnError;
import static io.servicetalk.concurrent.internal.ThrowableUtils.catchUnexpected;
import static io.servicetalk.utils.internal.PlatformDependent.newUnboundedMpscQueue;
import static io.servicetalk.utils.internal.ThrowableUtils.throwException;
import static java.util.Comparator.comparingLong;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.atomic.AtomicReferenceFieldUpdater.newUpdater;

class MulticastPublisher<T> extends AbstractNoHandleSubscribePublisher<T> {
    private static final Logger LOGGER = LoggerFactory.getLogger(MulticastPublisher.class);
    static final int DEFAULT_MULTICAST_QUEUE_LIMIT = 64;
    static final Function<Throwable, Completable> DEFAULT_MULTICAST_TERM_RESUB = t -> completed();
    private static final Subscriber<?>[] EMPTY_SUBSCRIBERS = new Subscriber[0];
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<MulticastPublisher.State, Subscriber[]>
            newSubscribersUpdater = newUpdater(MulticastPublisher.State.class, Subscriber[].class, "subscribers");
    @SuppressWarnings("rawtypes")
    private static final AtomicIntegerFieldUpdater<MulticastPublisher.State> subscribeCountUpdater =
            AtomicIntegerFieldUpdater.newUpdater(MulticastPublisher.State.class, "subscribeCount");
    private final Publisher<T> original;
    private final Function<Throwable, Completable> terminalResubscribe;
    private final int minSubscribers;
    private final boolean exactlyMinSubscribers;
    private final boolean cancelUpstream;
    @Nullable
    volatile State state;

    MulticastPublisher(Publisher<T> original, int minSubscribers, boolean exactlyMinSubscribers, boolean cancelUpstream,
                       int maxQueueSize, Function<Throwable, Completable> terminalResubscribe) {
        if (minSubscribers < 1) {
            throw new IllegalArgumentException("minSubscribers: " + minSubscribers + " (expected >1)");
        }
        if (maxQueueSize < 1) {
            throw new IllegalArgumentException("maxQueueSize: " + maxQueueSize + " (expected >1)");
        }
        this.original = original;
        this.minSubscribers = minSubscribers;
        this.exactlyMinSubscribers = exactlyMinSubscribers;
        this.cancelUpstream = cancelUpstream;
        this.terminalResubscribe = requireNonNull(terminalResubscribe);
    }

    static <T> MulticastPublisher<T> newMulticastPublisher(
            Publisher<T> original, int minSubscribers, boolean exactlyMinSubscribers, boolean cancelUpstream,
            int maxQueueSize, Function<Throwable, Completable> terminalResubscribe) {
        MulticastPublisher<T> publisher = new MulticastPublisher<>(original, minSubscribers, exactlyMinSubscribers,
                cancelUpstream, minSubscribers, terminalResubscribe);
        publisher.resetState(maxQueueSize, minSubscribers);
        return publisher;
    }

    @Override
    final void handleSubscribe(Subscriber<? super T> subscriber, CapturedContext capturedContext,
                         AsyncContextProvider contextProvider) {
        final State cState = state;
        assert cState != null;
        cState.addNewSubscriber(subscriber, capturedContext, contextProvider);
    }

    void resetState(int maxQueueSize, int minSubscribers) {
        state = new State(maxQueueSize, minSubscribers);
    }

    class State extends MulticastRootSubscriber<MulticastFixedSubscriber<T>> implements Subscriber<T> {
        private final DefaultPriorityQueue<MulticastFixedSubscriber<T>> demandQueue;
        volatile int subscribeCount;
        @SuppressWarnings("unchecked")
        volatile Subscriber<? super T>[] subscribers = (Subscriber<? super T>[]) EMPTY_SUBSCRIBERS;

        State(final int maxQueueSize, final int minSubscribers) {
            super(maxQueueSize);
            demandQueue = new DefaultPriorityQueue<>(comparingLong(sub -> sub.priorityQueueValue), minSubscribers);
        }

        @Nullable
        @Override
        final TerminalSubscriber<?> addSubscriber(final MulticastFixedSubscriber<T> subscriber,
                                                  @Nullable CapturedContext capturedContext,
                                                  AsyncContextProvider contextProvider) {
            for (;;) {
                final Subscriber<? super T>[] currSubs = subscribers;
                if (currSubs.length == 1 && currSubs[0] instanceof TerminalSubscriber) {
                    return (TerminalSubscriber<?>) currSubs[0];
                } else {
                    @SuppressWarnings("unchecked")
                    Subscriber<? super T>[] newSubs = (Subscriber<? super T>[])
                            Array.newInstance(Subscriber.class, currSubs.length + 1);
                    System.arraycopy(currSubs, 0, newSubs, 0, currSubs.length);
                    newSubs[currSubs.length] = subscriber;
                    if (newSubscribersUpdater.compareAndSet(this, currSubs, newSubs)) {
                        if (capturedContext != null) {
                            // This operator has special behavior where it chooses to use the AsyncContext and signal
                            // offloader from the last subscribe operation.
                            original.delegateSubscribeWithContext(this, capturedContext, contextProvider);
                        }
                        return null;
                    }
                }
            }
        }

        @Override
        final boolean removeSubscriber(final MulticastFixedSubscriber<T> subscriber) {
            for (;;) {
                final Subscriber<? super T>[] currSubs = subscribers;
                @SuppressWarnings("deprecation")
                final int i = ArrayUtils.indexOf(subscriber, currSubs);
                if (i < 0) {
                    // terminated, demandQueue is not thread safe and isn't cleaned up on the Subscriber thread.
                    return false;
                }
                @SuppressWarnings("unchecked")
                Subscriber<? super T>[] newSubs = (Subscriber<? super T>[])
                        Array.newInstance(Subscriber.class, currSubs.length - 1);
                if (i == 0) {
                    System.arraycopy(currSubs, 1, newSubs, 0, newSubs.length);
                } else {
                    System.arraycopy(currSubs, 0, newSubs, 0, i);
                    System.arraycopy(currSubs, i + 1, newSubs, i, newSubs.length - i);
                }
                if (newSubscribersUpdater.compareAndSet(this, currSubs, newSubs)) {
                    if (cancelUpstream && newSubs.length == 0) {
                        // Reset the state when all subscribers have cancelled to allow for re-subscribe.
                        try {
                            resetState(maxQueueSize, minSubscribers);
                            return true;
                        } catch (Throwable cause) {
                            LOGGER.warn("unexpected exception creating new state {}", MulticastPublisher.this,
                                    cause);
                        }
                    }
                    return false;
                }
            }
        }

        @Override
        final long processRequestEvent(MulticastFixedSubscriber<T> subscriber, final long n) {
            assert n > 0;
            final MulticastFixedSubscriber<T> oldMin = demandQueue.peek();
            final long oldValue = subscriber.priorityQueueValue;
            subscriber.priorityQueueValue = addWithOverflowProtection(subscriber.priorityQueueValue, n);
            if (!demandQueue.priorityChanged(subscriber) || oldMin != subscriber) {
                return 0;
            }
            final MulticastFixedSubscriber<T> newMin = demandQueue.peek();
            assert newMin != null;
            return newMin.priorityQueueValue - oldValue;
        }

        @Override
        final long processCancelEvent(MulticastFixedSubscriber<T> subscriber) {
            MulticastFixedSubscriber<T> min = demandQueue.peek();
            if (!demandQueue.removeTyped(subscriber)) {
                return -1;
            }
            if (min == subscriber) {
                // We just removed the subscriber with the minimum demand. If this subscriber was the reason why we
                // have not yet requested upstream we need to re-asses otherwise we may live lock.
                min = demandQueue.peek();
                // processSubscribeEvent sets the initial value of new subscribers to the value of the current minimum.
                // We need to subtract the initial value to request the delta between the old min.
                return min == null ? 0 : min.priorityQueueValue - min.initPriorityQueueValue -
                        (subscriber.priorityQueueValue - subscriber.initPriorityQueueValue);
            }
            return 0;
        }

        @Override
        boolean processSubscribeEvent(MulticastFixedSubscriber<T> subscriber,
                                      @Nullable TerminalSubscriber<?> terminalSubscriber) {
            if (terminalSubscriber != null) {
                // Directly terminate the underlying subscriber to avoid queuing and MulticastFixedSubscriber rules.
                terminalSubscriber.terminate(subscriber.subscriber);
                return false;
            }
            // Initialize the new subscriber's priorityQueueValue to the current minimum demand value to keep
            // outstanding demand bounded to maxQueueSize.
            final MulticastFixedSubscriber<T> currMin = demandQueue.peek();
            if (currMin != null) {
                subscriber.priorityQueueValue = subscriber.initPriorityQueueValue = currMin.priorityQueueValue;
            }
            demandQueue.add(subscriber);
            return true;
        }

        @Override
        void processTerminal(final TerminalNotification terminalNotification) {
            throw new UnsupportedOperationException("terminal queuing not supported. terminal=" + terminalNotification);
        }

        @Override
        void processOnNextEvent(final Object wrapped) {
            throw new UnsupportedOperationException("onNext queuing not supported. wrapped=" + wrapped);
        }

        final void addNewSubscriber(Subscriber<? super T> subscriber, CapturedContext capturedContext,
                                    AsyncContextProvider contextProvider) {
            final int sCount = subscribeCountUpdater.incrementAndGet(this);
            if (exactlyMinSubscribers && sCount > minSubscribers) {
                deliverOnSubscribeAndOnError(subscriber, capturedContext, contextProvider,
                        new RejectedSubscribeException("Only " + minSubscribers + " subscribers are allowed!"));
                return;
            }
            MulticastFixedSubscriber<T> multiSubscriber =
                    new MulticastFixedSubscriber<>(this, subscriber, capturedContext, contextProvider, sCount);
            if (tryAcquireLock(subscriptionLockUpdater, this)) {
                try {
                    // This operator has special behavior where it chooses to use the AsyncContext and signal
                    // offloader from the last subscribe operation.
                    processSubscribeEventInternal(multiSubscriber, sCount == minSubscribers ? capturedContext : null,
                            contextProvider);
                } finally {
                    if (!releaseLock(subscriptionLockUpdater, this)) {
                        processSubscriptionEvents();
                    }
                }
            } else {
                subscriptionEvents.add(new SubscribeEvent<>(multiSubscriber,
                        sCount == minSubscribers ? capturedContext : null, contextProvider));
                processSubscriptionEvents();
            }
        }

        @Override
        public final void onSubscribe(final Subscription subscription) {
            onSubscribe0(subscription);
        }

        @Override
        public void onNext(@Nullable final T t) {
            // If there is re-entry triggered by a Subscriber which triggers delivery of new data this may result in
            // out of order delivery (e.g. s1.onNext(a), s1.request(1), s1.onNext(b), s2.onNext(b) s2 sees b before a!)
            // However the MulticastSubscriber protects against re-entry and if there are multiple threads involved the
            // upstream is responsible for ensuring no concurrency on this Subscriber. So we don't need special
            // provisions for re-entry ordering here.
            final Subscriber<? super T>[] subs = subscribers;
            for (Subscriber<? super T> subscriber : subs) {
                subscriber.onNext(t);
            }
        }

        @Override
        public void onError(final Throwable t) {
            onTerminal(t, Subscriber::onError);
        }

        @Override
        public void onComplete() {
            onTerminal(null, (subscriber, t) -> subscriber.onComplete());
        }

        private void onTerminal(@Nullable Throwable t, BiConsumer<Subscriber<? super T>, Throwable> terminator) {
            safeTerminalStateReset(t).whenFinally(() -> {
                try {
                    resetState(maxQueueSize, minSubscribers);
                } catch (Throwable cause) {
                    LOGGER.warn("unexpected exception from terminal resubscribe Completable {}",
                            MulticastPublisher.this, cause);
                }
            }).subscribe();

            @SuppressWarnings("unchecked")
            final Subscriber<? super T>[] newSubs = (Subscriber<? super T>[]) Array.newInstance(Subscriber.class, 1);
            newSubs[0] = new TerminalSubscriber<>(t);
            for (;;) {
                final Subscriber<? super T>[] subs = subscribers;
                if (newSubscribersUpdater.compareAndSet(this, subs, newSubs)) {
                    Throwable delayedCause = null;
                    for (final Subscriber<? super T> subscriber : subs) {
                        try {
                            terminator.accept(subscriber, t);
                        } catch (Throwable cause) {
                            delayedCause = catchUnexpected(delayedCause, cause);
                        }
                    }
                    if (delayedCause != null) {
                        throwException(delayedCause);
                    }
                    break;
                }
            }
        }

        private Completable safeTerminalStateReset(@Nullable Throwable t) {
            try {
                return terminalResubscribe.apply(t);
            } catch (Throwable cause) {
                LOGGER.warn("terminalStateReset {} threw", terminalResubscribe, cause);
            }
            return Completable.never();
        }
    }

    private abstract static class MulticastRootSubscriber<T extends MulticastLeafSubscriber<?>> {
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<MulticastRootSubscriber> subscriptionLockUpdater =
                AtomicIntegerFieldUpdater.newUpdater(MulticastRootSubscriber.class, "subscriptionLock");
        private final DelayedSubscription delayedSubscription = new DelayedSubscription();
        final Queue<Object> subscriptionEvents = newUnboundedMpscQueue(8);

        final int maxQueueSize;
        @SuppressWarnings("unused")
        private volatile int subscriptionLock;

        MulticastRootSubscriber(int maxQueueSize) {
            this.maxQueueSize = maxQueueSize;
        }

        /**
         * Add a {@link Subscriber} to the underlying collection.
         * <p>
         * Invocation while {@link #subscriptionLock} is held.
         * @param subscriber The {@link Subscriber} to remove.
         * @param capturedContext The context map to used when subscribing upstream, or {@code null} if should not
         *                     subscribe.
         * @param contextProvider The context provider to used when subscribing upstream.
         * @return {@code null} if {@code subscriber} was added to the list, or non-{@code null} if not added to the
         * because there was previously a terminal event.
         */
        @Nullable
        abstract TerminalSubscriber<?> addSubscriber(T subscriber, @Nullable CapturedContext capturedContext,
                                       AsyncContextProvider contextProvider);

        /**
         * Remove a {@link Subscriber} from the underlying collection, and stop delivering signals to it.
         * <p>
         * Invoked <strong>outside</strong> the scope of locks for early removal.
         * @param subscriber The {@link Subscriber} to remove.
         * @return {@code true} if upstream {@link Subscription} should be cancelled.
         */
        abstract boolean removeSubscriber(T subscriber);

        /**
         * The downstream {@link Subscriber}'s associated {@link Subscription} has requested {@code n} more signals.
         * <p>
         * Invocation while {@link #subscriptionLock} is held.
         * @param subscriber The subscriber which has requested more data.
         * @param n The amount of data that has been requested.
         * @return The amount to request upstream.
         */
        abstract long processRequestEvent(T subscriber, long n);

        /**
         * Invoked after {@link #removeSubscriber(MulticastLeafSubscriber)}.
         * <p>
         * Invocation while {@link #subscriptionLock} is held.
         * @param subscriber The subscriber that invoked cancel.
         * @return
         * <ul>
         *     <li>{@code >0} - the amount to request upstream.</li>
         *     <li>{@code 0} - removed but nothing to request upstream. defer to
         *     {@link #removeSubscriber(MulticastLeafSubscriber)}'s return value for upstream cancellation.</li>
         *     <li>{@code <0} - failed to remove. prevent upstream cancellation as determined by
         *     {@link #removeSubscriber(MulticastLeafSubscriber)}</li>
         * </ul>
         */
        abstract long processCancelEvent(T subscriber);

        /**
         * Invoked after {@link #addSubscriber(MulticastLeafSubscriber, CapturedContext, AsyncContextProvider)}.
         * <p>
         * Invocation while {@link #subscriptionLock} is held.
         * @param subscriber The subscriber which was passed to
         * @param terminalSubscriber {@code null} if the {@code subscriber} was added to the list or non-{@code null}
         * if a terminal event has occurred, and this method <b>MUST</b> eventually deliver the terminal signal to
         * {@code subscriber}.
         * {@link #addSubscriber(MulticastLeafSubscriber, CapturedContext, AsyncContextProvider)}.
         * @return {@code false} to stop handling this processor and break out early (e.g. can happen if
         * {@code terminalSubscriber} is non-{@code null} no signals are queued and the terminal is delivered).
         * {@code true} to unblock the {@code subscriber}'s signal queue and keep processing events.
         */
        abstract boolean processSubscribeEvent(T subscriber, @Nullable TerminalSubscriber<?> terminalSubscriber);

        /**
         * Invoked if a terminal signal for {@link State} couldn't be delivered inline.
         * <p>
         * Invocation while {@link #subscriptionLock} is held.
         * @param terminalNotification The terminal signal that is queued.
         */
        abstract void processTerminal(TerminalNotification terminalNotification);

        /**
         * Invoked if an {@link State#onNext(Object)} couldn't be delivered inline.
         * <p>
         * Invocation while {@link #subscriptionLock} is held.
         * @param wrapped The signal that is queued.
         */
        abstract void processOnNextEvent(Object wrapped);

        /**
         * Callback indicating upstream {@link Subscription} has been cancelled.
         * <p>
         * Invocation while {@link #subscriptionLock} is held.
         */
        void upstreamCancelled() {
        }

        final void onSubscribe0(final Subscription subscription) {
            delayedSubscription.delayedSubscription(subscription);
        }

        final void request(T subscriber, long n) {
            if (tryAcquireLock(subscriptionLockUpdater, this)) {
                try {
                    if (isRequestNValid(n)) {
                        final long toRequest = processRequestEvent(subscriber, n);
                        if (toRequest != 0) {
                            requestUpstream(toRequest);
                        }
                    } else {
                        delayedSubscription.request(n);
                    }
                } finally {
                    if (!releaseLock(subscriptionLockUpdater, this)) {
                        processSubscriptionEvents();
                    }
                }
            } else {
                subscriptionEvents.add(new RequestEvent<>(subscriber, n));
                processSubscriptionEvents();
            }
        }

        final void cancel(T subscriber) {
            final boolean cancelUpstream = removeSubscriber(subscriber);
            if (tryAcquireLock(subscriptionLockUpdater, this)) {
                try {
                    processCancelEventInternal(subscriber, cancelUpstream);
                } finally {
                    if (!releaseLock(subscriptionLockUpdater, this)) {
                        processSubscriptionEvents();
                    }
                }
            } else {
                subscriptionEvents.add(new CancelEvent<>(subscriber, cancelUpstream));
                processSubscriptionEvents();
            }
        }

        private void requestUpstream(long n) {
            assert n > 0 : n;
            delayedSubscription.request(n);
        }

        final void processSubscriptionEvents() {
            boolean tryAcquire = true;
            Throwable delayedCause = null;
            while (tryAcquire && tryAcquireLock(subscriptionLockUpdater, this)) {
                try {
                    long toRequest = 0;
                    Object event;
                    while ((event = subscriptionEvents.poll()) != null) {
                        if (event instanceof RequestEvent) {
                            if (toRequest >= 0) {
                                @SuppressWarnings("unchecked")
                                final RequestEvent<T> rEvent = (RequestEvent<T>) event;
                                // rEvent.n is already sanitized (e.g. invalid value will always be negative).
                                toRequest = rEvent.n > 0 ? addWithOverflowProtection(toRequest,
                                        processRequestEvent(rEvent.subscriber, rEvent.n)) : rEvent.n;
                            }
                        } else if (event instanceof SubscribeEvent) {
                            @SuppressWarnings("unchecked")
                            final SubscribeEvent<T> sEvent = (SubscribeEvent<T>) event;
                            processSubscribeEventInternal(sEvent.subscriber, sEvent.capturedContext,
                                    sEvent.contextProvider);
                        } else if (event instanceof CancelEvent) {
                            @SuppressWarnings("unchecked")
                            final CancelEvent<T> cEvent = (CancelEvent<T>) event;
                            processCancelEventInternal(cEvent.subscriber, cEvent.cancelUpstream);
                        } else if (event instanceof TerminalNotification) {
                            processTerminal((TerminalNotification) event);
                        } else {
                            processOnNextEvent(event);
                        }
                    }
                    if (toRequest != 0) {
                        requestUpstream(toRequest);
                    }
                } catch (Throwable cause) {
                    delayedCause = catchUnexpected(delayedCause, cause);
                } finally {
                    tryAcquire = !releaseLock(subscriptionLockUpdater, this);
                }
            }
            if (delayedCause != null) {
                throwException(delayedCause);
            }
        }

        private void processCancelEventInternal(T subscriber, boolean cancelUpstream) {
            final long result = processCancelEvent(subscriber);
            if (result >= 0) {
                if (cancelUpstream) {
                    try {
                        delayedSubscription.cancel();
                    } finally {
                        upstreamCancelled();
                    }
                } else if (result > 0) {
                    requestUpstream(result);
                }
            }
        }

        void processSubscribeEventInternal(T subscriber, @Nullable CapturedContext capturedContext,
                                           AsyncContextProvider contextProvider) {
            TerminalSubscriber<?> terminalSubscriber = addSubscriber(subscriber, capturedContext, contextProvider);
            if (!processSubscribeEvent(subscriber, terminalSubscriber)) {
                return;
            }
            try {
                // Note we invoke onSubscribe AFTER the subscribers array and demandQueue state is set
                // because the subscription methods depend upon this state. This may result in onNext(),
                // onError(t), or onComplete() being called before onSubscribe() which is unexpected for the
                // Subscriber API, but MulticastSubscriber can tolerate this and internally queues signals.
                subscriber.triggerOnSubscribe();
            } catch (Throwable cause) {
                try {
                    cancel(subscriber);
                } finally {
                    safeOnError((Subscriber<?>) subscriber, cause);
                }
            }
        }

        static final class SubscribeEvent<T extends MulticastLeafSubscriber<?>> {
            private final T subscriber;
            @Nullable
            private final CapturedContext capturedContext;
            private final AsyncContextProvider contextProvider;

            SubscribeEvent(final T subscriber, @Nullable final CapturedContext capturedContext,
                           final AsyncContextProvider contextProvider) {
                this.subscriber = subscriber;
                this.capturedContext = capturedContext;
                this.contextProvider = contextProvider;
            }
        }

        private static final class RequestEvent<T extends MulticastLeafSubscriber<?>> {
            final T subscriber;
            final long n;

            private RequestEvent(final T subscriber, final long n) {
                this.subscriber = subscriber;
                // sanitizeRequestN to avoid additional state for invalid requestN.
                this.n = n == 0 ? -1 : n;
            }
        }

        private static final class CancelEvent<T extends MulticastLeafSubscriber<?>> {
            final T subscriber;
            final boolean cancelUpstream;

            private CancelEvent(final T subscriber, final boolean cancelUpstream) {
                this.subscriber = subscriber;
                this.cancelUpstream = cancelUpstream;
            }
        }
    }

    static final class MulticastFixedSubscriber<T> extends MulticastLeafSubscriber<T> implements Node {
        private final int index;
        private final MulticastPublisher<T>.State root;
        private final Subscriber<? super T> subscriber;
        private final Subscriber<? super T> ctxSubscriber;
        /**
         * Protected by {@link MulticastRootSubscriber} lock.
         */
        private long initPriorityQueueValue;
        /**
         * Protected by {@link MulticastRootSubscriber} lock.
         */
        private long priorityQueueValue;
        /**
         * Protected by {@link MulticastRootSubscriber} lock.
         */
        private int priorityQueueIndex = INDEX_NOT_IN_QUEUE;

        MulticastFixedSubscriber(final MulticastPublisher<T>.State root,
                                 final Subscriber<? super T> subscriber, final CapturedContext capturedContext,
                                 final AsyncContextProvider contextProvider, final int index) {
            this.root = root;
            this.index = index;
            this.subscriber = requireNonNull(subscriber);
            ctxSubscriber = contextProvider.wrapPublisherSubscriber(subscriber, capturedContext);
        }

        @Override
        Subscriber<? super T> subscriber() {
            return subscriber;
        }

        @Override
        Subscriber<? super T> subscriberOnSubscriptionThread() {
            return ctxSubscriber;
        }

        @Override
        void requestUpstream(final long n) {
            root.request(this, n);
        }

        @Override
        void cancelUpstream() {
            root.cancel(this);
        }

        @Override
        int outstandingDemandLimit() {
            return root.maxQueueSize;
        }

        @Override
        public int priorityQueueIndex(final DefaultPriorityQueue<?> queue) {
            return priorityQueueIndex;
        }

        @Override
        public void priorityQueueIndex(final DefaultPriorityQueue<?> queue, final int i) {
            priorityQueueIndex = i;
        }

        @Override
        public String toString() {
            return String.valueOf(index);
        }
    }

    static final class TerminalSubscriber<T> implements Subscriber<T> {
        @Nullable
        final Throwable terminalError;

        private TerminalSubscriber(@Nullable final Throwable terminalError) {
            this.terminalError = terminalError;
        }

        void terminate(Subscriber<?> sub) {
            if (terminalError == null) {
                deliverCompleteFromSource(sub);
            } else {
                deliverErrorFromSource(sub, terminalError);
            }
        }

        @Override
        public void onSubscribe(final Subscription subscription) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void onNext(@Nullable final T t) {
            throw new IllegalStateException("terminal signal already received in onNext: " + t, terminalError);
        }

        @Override
        public void onError(final Throwable t) {
            throw new IllegalStateException("duplicate terminal signal in onError", t);
        }

        @Override
        public void onComplete() {
            throw new IllegalStateException("duplicate terminal signal in onComplete", terminalError);
        }
    }
}
