/*
 * Copyright © 2018 Apple Inc. and the ServiceTalk project authors
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

import io.servicetalk.concurrent.BlockingIterable;
import io.servicetalk.concurrent.BlockingIterator;
import io.servicetalk.concurrent.PublisherSource.Subscriber;
import io.servicetalk.concurrent.PublisherSource.Subscription;
import io.servicetalk.concurrent.internal.ConcurrentSubscription;
import io.servicetalk.concurrent.internal.DelayedSubscription;
import io.servicetalk.concurrent.internal.QueueFullException;
import io.servicetalk.concurrent.internal.TerminalNotification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;

import static io.servicetalk.concurrent.api.SubscriberApiUtils.unwrapNullUnchecked;
import static io.servicetalk.concurrent.api.SubscriberApiUtils.wrapNull;
import static io.servicetalk.concurrent.internal.TerminalNotification.complete;
import static io.servicetalk.concurrent.internal.TerminalNotification.error;
import static io.servicetalk.utils.internal.NumberUtils.ensurePositive;
import static io.servicetalk.utils.internal.ThrowableUtils.throwException;
import static java.lang.Math.min;
import static java.lang.Thread.currentThread;
import static java.util.Objects.requireNonNull;

/**
 * As returned by {@link Publisher#toIterable(int)} and {@link Publisher#toIterable()}.
 *
 * @param <T> Type of items emitted by the {@link Publisher} from which this {@link BlockingIterable} is created.
 */
final class PublisherAsBlockingIterable<T> implements BlockingIterable<T> {
    final Publisher<T> original;
    private final int queueCapacityHint;

    PublisherAsBlockingIterable(final Publisher<T> original) {
        this(original, 16);
    }

    PublisherAsBlockingIterable(final Publisher<T> original, int queueCapacityHint) {
        this.original = requireNonNull(original);
        // Add a sane upper bound to the capacity to reduce buffering.
        this.queueCapacityHint = min(ensurePositive(queueCapacityHint, "queueCapacityHint"), 128);
    }

    @Override
    public BlockingIterator<T> iterator() {
        SubscriberAndIterator<T> subscriberAndIterator = new SubscriberAndIterator<>(queueCapacityHint);
        original.subscribeInternal(subscriberAndIterator);
        return subscriberAndIterator;
    }

    private static final class SubscriberAndIterator<T> implements Subscriber<T>, BlockingIterator<T> {
        /**
         * Allows to re-enable cancelling the subscription on {@link #hasNext(long, TimeUnit)} timeout. This flag
         * will be removed after a couple releases and no issues identified with the new behavior.
         */
        private static final boolean CANCEL_SUBSCRIPTION_ON_HAS_NEXT_TIMEOUT = Boolean
                .getBoolean("io.servicetalk.concurrent.api.cancelSubscriptionOnHasNextTimeout");
        private static final Logger LOGGER = LoggerFactory.getLogger(SubscriberAndIterator.class);
        private static final Object CANCELLED_SIGNAL = new Object();
        private static final TerminalNotification COMPLETE_NOTIFICATION = complete();
        private final BlockingQueue<Object> data;
        private final DelayedSubscription subscription = new DelayedSubscription();
        private final int requestN;
        /**
         * Number of items to emit from {@link #next()} till we request more.
         * Alternatively we can {@link Subscription#request(long) request(1)} every time we emit an item.
         * This approach aids batch production of data without sacrificing responsiveness.
         * Assumption here is that the source does not necessarily wait to produce all data before emitting hence
         * {@link Subscription#request(long) request(n)} should be as fast as
         * {@link Subscription#request(long) request(1)}.
         * <p>
         * Only accessed from {@link Iterator} methods and not from {@link Subscriber} methods (after initialization).
         */
        private int itemsToNextRequest;

        /**
         * Next item to return from {@link #next()}
         */
        @Nullable
        private Object next;
        private boolean terminated;

        SubscriberAndIterator(int queueCapacity) {
            requestN = queueCapacity;
            data = new LinkedBlockingQueue<>();
        }

        @Override
        public void onSubscribe(final Subscription s) {
            // Subscription is requested from here as well as hasNext. Also, it can be cancelled from close(). So, we
            // need to protect it from concurrent access.
            subscription.delayedSubscription(ConcurrentSubscription.wrap(s));
            itemsToNextRequest = requestN;
            subscription.request(itemsToNextRequest);
        }

        @Override
        public void close() {
            try {
                subscription.cancel();
            } finally {
                if (!terminated) {
                    offer(CANCELLED_SIGNAL);
                }
            }
        }

        @Override
        public void onNext(@Nullable T t) {
            offer(wrapNull(t));
        }

        @Override
        public void onError(final Throwable t) {
            offer(error(t));
        }

        @Override
        public void onComplete() {
            offer(COMPLETE_NOTIFICATION);
        }

        private void offer(Object o) {
            if (!data.offer(o)) {
                enqueueFailed(o);
            }
        }

        @Override
        public boolean hasNext() {
            if (terminated) {
                return next != null && next != COMPLETE_NOTIFICATION;
            }
            if (next != null) {
                return true; // Keep returning true till next() is called which sets next to null
            }
            try {
                next = data.take();
                requestMoreIfRequired();
            } catch (InterruptedException e) {
                return hasNextInterrupted(e);
            }
            return hasNextProcessNext();
        }

        @Override
        public boolean hasNext(final long timeout, final TimeUnit unit) throws TimeoutException {
            if (terminated) {
                return next != null && next != COMPLETE_NOTIFICATION;
            }
            if (next != null) {
                return true; // Keep returning true till next() is called which sets next to null
            }
            try {
                next = data.poll(timeout, unit);
                if (next == null) {
                    terminated = true;
                    if (CANCEL_SUBSCRIPTION_ON_HAS_NEXT_TIMEOUT) {
                        subscription.cancel();
                    }
                    throw new TimeoutException("timed out after: " + timeout + " units: " + unit);
                }
                requestMoreIfRequired();
            } catch (InterruptedException e) {
                return hasNextInterrupted(e);
            }
            return hasNextProcessNext();
        }

        private void enqueueFailed(Object item) {
            LOGGER.error("Queue should be unbounded, but an offer failed for item {}!", item);
            // Note that we throw even if the item represents a terminal signal (even though we don't expect another
            // terminal signal to be delivered from the upstream source because we are already terminated). If we fail
            // to enqueue a terminal event async control flow won't be completed and the user won't be notified. This
            // is a relatively extreme failure condition and we fail loudly to clarify that signal delivery is
            // interrupted and the user may experience hangs.
            throw new QueueFullException("data");
        }

        private boolean hasNextInterrupted(InterruptedException e) {
            currentThread().interrupt(); // Reset the interrupted flag.
            terminated = true;
            next = error(e);
            subscription.cancel();
            return true; // Return true so that the InterruptedException can be thrown from next()
        }

        private boolean hasNextProcessNext() {
            if (next instanceof TerminalNotification) {
                terminated = true;
                // If we have an error, return true, so that the same can be thrown from next().
                return ((TerminalNotification) next).cause() != null;
            }
            if (next == CANCELLED_SIGNAL) {
                terminated = true;
                next = null;
                return false;
            }
            return true;
        }

        private void requestMoreIfRequired() {
            // Request more when we half of the outstanding demand has been delivered. This attempts to keep some
            // outstanding demand in the event there is impedance mismatch between producer and consumer (as opposed to
            // waiting until outstanding demand reaches 0) while still having an upper bound.
            if (--itemsToNextRequest == requestN >>> 1) {
                final int toRequest = requestN - itemsToNextRequest;
                itemsToNextRequest = requestN;
                subscription.request(toRequest);
            }
        }

        @Nullable
        @Override
        public T next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return processNext();
        }

        @Nullable
        @Override
        public T next(final long timeout, final TimeUnit unit) throws TimeoutException {
            if (!hasNext(timeout, unit)) {
                throw new NoSuchElementException();
            }
            return processNext();
        }

        @Nullable
        private T processNext() {
            final Object signal = next;
            assert next != null;
            next = null;
            if (signal instanceof TerminalNotification) {
                TerminalNotification terminalNotification = (TerminalNotification) signal;
                Throwable cause = terminalNotification.cause();
                if (cause == null) {
                    throw new NoSuchElementException();
                }
                throwException(cause);
            }
            return unwrapNullUnchecked(signal);
        }
    }
}
