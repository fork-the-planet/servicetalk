/*
 * Copyright © 2021-2023 Apple Inc. and the ServiceTalk project authors
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
package io.servicetalk.loadbalancer;

import io.servicetalk.client.api.ConnectTimeoutException;
import io.servicetalk.client.api.ConnectionFactory;
import io.servicetalk.client.api.ConnectionLimitReachedException;
import io.servicetalk.client.api.DelegatingConnectionFactory;
import io.servicetalk.client.api.LoadBalancedConnection;
import io.servicetalk.concurrent.api.AsyncContext;
import io.servicetalk.concurrent.api.Completable;
import io.servicetalk.concurrent.api.ListenableAsyncCloseable;
import io.servicetalk.concurrent.api.Single;
import io.servicetalk.concurrent.api.TerminalSignalConsumer;
import io.servicetalk.concurrent.internal.DefaultContextMap;
import io.servicetalk.concurrent.internal.DelayedCancellable;
import io.servicetalk.context.api.ContextMap;
import io.servicetalk.loadbalancer.LoadBalancerObserver.HostObserver;
import io.servicetalk.transport.api.TransportObserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Predicate;
import javax.annotation.Nullable;

import static io.servicetalk.client.api.RequestTracker.REQUEST_TRACKER_KEY;
import static io.servicetalk.concurrent.api.AsyncCloseables.toAsyncCloseable;
import static io.servicetalk.concurrent.api.Completable.completed;
import static io.servicetalk.concurrent.api.Publisher.fromIterable;
import static io.servicetalk.concurrent.api.RetryStrategies.retryWithConstantBackoffDeltaJitter;
import static io.servicetalk.concurrent.api.Single.failed;
import static io.servicetalk.concurrent.api.Single.succeeded;
import static io.servicetalk.concurrent.internal.FlowControlUtils.addWithOverflowProtection;
import static io.servicetalk.loadbalancer.ConnectTracker.ErrorClass.CANCELLED;
import static io.servicetalk.loadbalancer.ConnectTracker.ErrorClass.CONNECT_ERROR;
import static io.servicetalk.loadbalancer.ConnectTracker.ErrorClass.CONNECT_TIMEOUT;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.atomic.AtomicReferenceFieldUpdater.newUpdater;

final class DefaultHost<Addr, C extends LoadBalancedConnection> implements Host<Addr, C> {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultHost.class);

    private enum State {
        // Represents the state where the host is both active and healthy. This state may
        // have connection failures but hasn't yet transitioned to unhealthy.
        ACTIVE,
        // Represents the state where the host is active, but has reached the threshold for
        // consecutive connection failures.
        UNHEALTHY,
        // Represents the state where service discovery has signaled that this address may
        // still serve requests with existing connections but must not attempt new connections.
        // Once the number of connections reaches 0 the host will be closed and removed.
        EXPIRED,
        // Represents the closed state. This is a terminal state and all connections are
        // draining and will be closed.
        CLOSED
    }

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultHost, DefaultHost.ConnState> connStateUpdater =
            newUpdater(DefaultHost.class, DefaultHost.ConnState.class, "connState");

    private final String lbDescription;
    private final Addr address;
    @Nullable
    private final HealthCheckConfig healthCheckConfig;
    private final ConnectionSelector<C> connectionSelector;
    @Nullable
    private final HealthIndicator<Addr, C> healthIndicator;
    private final LoadBalancerObserver.HostObserver hostObserver;
    private final ConnectionFactory<Addr, ? extends C> connectionFactory;
    private final ListenableAsyncCloseable closeable;
    private volatile ConnState connState = new ConnState(emptyList(), State.ACTIVE, 0, null);

    DefaultHost(final String lbDescription, final Addr address,
                final ConnectionSelector<C> connectionSelector,
                final ConnectionFactory<Addr, ? extends C> connectionFactory,
                final HostObserver hostObserver, @Nullable final HealthCheckConfig healthCheckConfig,
                @Nullable final HealthIndicator<Addr, C> healthIndicator) {
        this.lbDescription = requireNonNull(lbDescription, "lbDescription");
        this.address = requireNonNull(address, "address");
        this.healthIndicator = healthIndicator;
        this.connectionSelector = requireNonNull(connectionSelector, "connectionSelector");
        requireNonNull(connectionFactory, "connectionFactory");
        this.connectionFactory = healthIndicator == null ? connectionFactory :
                new InstrumentedConnectionFactory<>(connectionFactory, healthIndicator);
        assert healthCheckConfig == null || healthCheckConfig.failedThreshold > 0;
        this.healthCheckConfig = healthCheckConfig;
        this.hostObserver = requireNonNull(hostObserver, "hostObserver");
        this.closeable = toAsyncCloseable(this::doClose);
    }

    @Override
    public Addr address() {
        return address;
    }

    @Override
    public double weight() {
        return 1;
    }

    @Override
    public boolean markActiveIfNotClosed() {
        final ConnState oldState = connStateUpdater.getAndUpdate(this, oldConnState -> {
            if (oldConnState.state == State.EXPIRED) {
                return oldConnState.toActiveNoFailures();
            }
            // If oldConnState.state == State.ACTIVE this could mean either a duplicate event,
            // or a repeated CAS operation. We could issue a warning, but as we don't know, we don't log anything.
            // UNHEALTHY state cannot transition to ACTIVE without passing the health check.
            return oldConnState;
        });
        if (oldState.state == State.EXPIRED) {
            hostObserver.onExpiredHostRevived(oldState.connections.size());
        }
        return oldState.state != State.CLOSED;
    }

    private ConnState closeConnState() {
        for (;;) {
            // We need to keep the oldState.connections around even if we are closed because the user may do
            // closeGracefully with a timeout, which fails, and then force close. If we discard connections when
            // closeGracefully is started we may leak connections.
            final ConnState oldState = connState;
            if (oldState.state == State.CLOSED) {
                // already closed.
                return oldState;
            }
            // Try to close. If we succeed we can also clean up the healthIndicator.
            if (connStateUpdater.compareAndSet(this, oldState, oldState.toClosed())) {
                if (healthIndicator != null) {
                    healthIndicator.cancel();
                }
                return oldState;
            }
        }
    }

    @Override
    public boolean markExpired() {
        for (;;) {
            ConnState oldState = connStateUpdater.get(this);
            if (oldState.state == State.EXPIRED) {
                return false;
            } else if (oldState.state == State.CLOSED) {
                return true;
            }
            assert oldState.state == State.ACTIVE || oldState.state == State.UNHEALTHY;
            if (connStateUpdater.compareAndSet(this, oldState, oldState.toExpired())) {
                cancelIfHealthCheck(oldState);
                hostObserver.onHostMarkedExpired(oldState.connections.size());
                if (oldState.connections.isEmpty()) {
                    // Trigger the callback to remove the host from usedHosts array.
                    this.closeAsync().subscribe();
                    return true;
                } else {
                    return false;
                }
            }
        }
    }

    @Override
    @Nullable
    public C pickConnection(Predicate<C> selector, @Nullable final ContextMap context) {
        final List<C> connections = connState.connections;
        return connectionSelector.select(connections, selector);
    }

    @Override
    public Single<C> newConnection(
            Predicate<C> selector, final boolean forceNewConnectionAndReserve, @Nullable final ContextMap context) {
        return Single.defer(() -> {
            ContextMap actualContext = context;
            if (actualContext == null) {
                actualContext = new DefaultContextMap();
            }

            // We need to put our address latency tracker in the context for consumption.
            if (healthIndicator != null) {
                actualContext.put(REQUEST_TRACKER_KEY, healthIndicator);
            }
            // This LB implementation does not automatically provide TransportObserver. Therefore, we pass "null" here.
            // Users can apply a ConnectionFactoryFilter if they need to override this "null" value with
            // TransportObserver.
            Single<? extends C> establishConnection = connectionFactory.newConnection(address, actualContext, null);
            if (healthCheckConfig != null) {
                // Schedule health check before returning
                establishConnection = establishConnection.beforeOnError(this::onConnectionError);
            }
            return establishConnection
                    .flatMap(newCnx -> {
                        if (forceNewConnectionAndReserve && !newCnx.tryReserve()) {
                            return newCnx.closeAsync().<C>concat(failed(
                                            Exceptions.StacklessConnectionRejectedException.newInstance(
                                                    "Newly created connection " + newCnx + " for " + lbDescription
                                                            + " could not be reserved.",
                                                    DefaultHost.class, "newConnection(...)")))
                                    .shareContextOnSubscribe();
                        }

                        // Invoke the selector before adding the connection to the pool, otherwise, connection can be
                        // used concurrently and hence a new connection can be rejected by the selector.
                        if (!selector.test(newCnx)) {
                            // Failure in selection could be the result of connection factory returning cached
                            // connection, and not having visibility into max-concurrent-requests, or other threads
                            // already selected the
                            // connection which uses all the max concurrent request count.

                            // If there is caching Propagate the exception and rely upon retry strategy.
                            Single<C> failedSingle = failed(Exceptions.StacklessConnectionRejectedException.newInstance(
                                    "Newly created connection " + newCnx + " for " + lbDescription
                                            + " was rejected by the selection filter.",
                                    DefaultHost.class, "newConnection(...)"));

                            // Just in case the connection is not closed add it to the host so we don't lose track,
                            // duplicates will be filtered out.
                            return (addConnection(newCnx) ?
                                    failedSingle : newCnx.closeAsync().concat(failedSingle)).shareContextOnSubscribe();
                        }
                        if (addConnection(newCnx)) {
                            return succeeded(newCnx).shareContextOnSubscribe();
                        }
                        return newCnx.closeAsync().<C>concat(
                                        failed(Exceptions.StacklessConnectionRejectedException.newInstance(
                                                "Failed to add newly created connection " + newCnx + " for " + this,
                                                DefaultHost.class, "newConnection(...)")))
                                .shareContextOnSubscribe();
                    }).shareContextOnSubscribe();
        });
    }

    private void markHealthy(final HealthCheck originalHealthCheckState) {
        // Marking healthy is called when we need to recover from an unexpected error.
        // However, it is possible that in the meantime, the host entered an EXPIRED state, then ACTIVE, then failed
        // to open connections and entered the UNHEALTHY state before the original thread continues execution here.
        // In such case, the flipped state is not the same as the one that just succeeded to open a connection.
        // In an unlikely scenario that the following connection attempts fail indefinitely, a health check task
        // would leak and would not be cancelled. Therefore, we cancel it here and allow failures to trigger a new
        // health check.
        ConnState oldState = connStateUpdater.getAndUpdate(this, previous -> {
            if (previous.isUnhealthy()) {
                return previous.toActiveNoFailures();
            }
            return previous;
        });
        if (oldState.healthCheck != originalHealthCheckState) {
            cancelIfHealthCheck(oldState);
        }
        // Only if the previous state was a healthcheck should we notify the observer.
        if (oldState.isUnhealthy()) {
            hostObserver.onHostRevived();
        }
    }

    private void onConnectionError(Throwable cause) {
        assert healthCheckConfig != null;
        for (;;) {
            ConnState previous = connStateUpdater.get(this);
            if (!previous.isActive() || !previous.connections.isEmpty()
                    || cause instanceof ConnectionLimitReachedException) {
                LOGGER.debug("{}: failed to open a new connection to the host on address {}. {}.",
                        lbDescription, address, previous, cause);
                break;
            }

            final ConnState nextState = previous.toNextFailedConnection(cause);
            if (connStateUpdater.compareAndSet(this, previous, nextState)) {
                // which state did we transition to?
                if (nextState.state == State.ACTIVE) {
                    LOGGER.debug("{}: failed to open a new connection to the host on address {}" +
                                    " {} time(s) ({} consecutive failures will trigger health-checking).",
                            lbDescription, address, nextState.failedConnections,
                            healthCheckConfig.failedThreshold, cause);
                } else {
                    // if we're unhealthy we also have a non-null healthCheck.
                    assert nextState.state == State.UNHEALTHY && nextState.healthCheck != null;
                    LOGGER.info("{}: failed to open a new connection to the host on address {} " +
                                    "{} time(s) in a row. Error counting threshold reached, marking this host as " +
                                    "UNHEALTHY for the selection algorithm and triggering background health-checking.",
                            lbDescription, address, healthCheckConfig.failedThreshold, cause);
                    hostObserver.onHostMarkedUnhealthy(cause);
                    nextState.healthCheck.schedule(cause);
                }
                break;
            }
        }
    }

    @Override
    public boolean isHealthy() {
        final State state = connState.state;
        return state != State.UNHEALTHY && state != State.CLOSED &&
                (healthIndicator == null || healthIndicator.isHealthy());
    }

    @Override
    public boolean canMakeNewConnections() {
        final State state = connState.state;
        return state != State.EXPIRED && state != State.CLOSED;
    }

    private boolean addConnection(final C connection) {
        int addAttempt = 0;
        for (;;) {
            final ConnState previous = connStateUpdater.get(this);
            if (previous.state == State.CLOSED ||
                    // an expired state with no connections is only a transient state and the host is going
                    // to be closing, so reject the connection.
                    previous.state == State.EXPIRED && previous.connections.isEmpty()) {
                return false;
            }
            ++addAttempt;

            ConnState nextState = previous.addNewConnection(connection);
            // If we didn't add a connection there is no need to update the state or add lifecycle observers.
            if (nextState == previous) {
                return true;
            }
            if (connStateUpdater.compareAndSet(this, previous, nextState)) {
                // It could happen that the Host turned into UNHEALTHY state either concurrently with adding a new
                // connection or with passing a previous health-check (if SD turned it into ACTIVE state). In both
                // cases we have to cancel the "previous" ongoing health check. See "markHealthy" for more context.
                if (previous.isUnhealthy()) {
                    cancelIfHealthCheck(previous);
                    // If we transitioned from unhealth to healthy we need to let the observer know.
                    hostObserver.onHostRevived();
                }
                break;
            }
        }

        LOGGER.trace("{}: added a new connection {} to {} after {} attempt(s).",
                lbDescription, connection, this, addAttempt);
        // Instrument the new connection so we prune it on close
        connection.onClose().beforeFinally(() -> {
            int removeAttempt = 0;
            for (;;) {
                final ConnState currentConnState = this.connState;
                if (currentConnState.state == State.CLOSED) {
                    break;
                }
                ++removeAttempt;
                ConnState nextState = currentConnState.removeConnection(connection);
                // Search for the connection in the list.
                if (nextState == currentConnState) {
                    // Connection was already removed, nothing to do.
                    break;
                } else if (nextState.connections.isEmpty()) {
                    if (currentConnState.isActive()) {
                        if (connStateUpdater.compareAndSet(this, currentConnState, nextState)) {
                            break;
                        }
                    } else if (currentConnState.state == State.EXPIRED
                            // We're closing the last connection, close the Host.
                            // Closing the host will trigger the Host's onClose method, which will remove the host
                            // from used hosts list. If a race condition appears and a new connection was added
                            // in the meantime, that would mean the host is available again and the CAS operation
                            // will allow for determining that. It will prevent closing the Host and will only
                            // remove the connection (previously considered as the last one) from the array
                            // in the next iteration.
                            && connStateUpdater.compareAndSet(this, currentConnState, nextState.toClosed())) {
                        closeAsync().subscribe();
                        hostObserver.onExpiredHostRemoved(nextState.connections.size());
                        break;
                    }
                } else {
                    if (connStateUpdater.compareAndSet(this, currentConnState, nextState)) {
                        break;
                    }
                }
            }
            LOGGER.trace("{}: removed connection {} from {} after {} attempt(s).",
                    lbDescription, connection, this, removeAttempt);
        }).onErrorComplete(t -> {
            // Use onErrorComplete instead of whenOnError to avoid double logging of an error inside subscribe():
            // SimpleCompletableSubscriber.
            LOGGER.error("{}: unexpected error while processing connection.onClose() for {}.",
                    lbDescription, connection, t);
            return true;
        }).subscribe();
        return true;
    }

    // Used for testing only
    Map.Entry<Addr, List<C>> asEntry() {
        return new AbstractMap.SimpleImmutableEntry<>(address, connState.connections);
    }

    @Override
    public Completable closeAsync() {
        return closeable.closeAsync();
    }

    @Override
    public Completable closeAsyncGracefully() {
        return closeable.closeAsyncGracefully();
    }

    @Override
    public Completable onClose() {
        return closeable.onClose();
    }

    @Override
    public Completable onClosing() {
        return closeable.onClosing();
    }

    private Completable doClose(final boolean graceful) {
        return Completable.defer(() -> {
            final ConnState oldState = closeConnState();
            cancelIfHealthCheck(oldState);
            LOGGER.debug("{}: closing {} connection(s) {}gracefully to the closed address: {}.",
                    lbDescription, oldState.connections.size(), graceful ? "" : "un", address);
            if (oldState.state == State.ACTIVE) {
                hostObserver.onActiveHostRemoved(oldState.connections.size());
            } else if (oldState.state == State.EXPIRED) {
                hostObserver.onExpiredHostRemoved(oldState.connections.size());
            }
            final List<C> connections = oldState.connections;
            return (connections.isEmpty() ? completed() :
                    fromIterable(connections).flatMapCompletableDelayError(conn -> graceful ?
                            conn.closeAsyncGracefully() : conn.closeAsync()))
                    .shareContextOnSubscribe();
        });
    }

    private void cancelIfHealthCheck(ConnState connState) {
        if (connState.isUnhealthy()) {
            LOGGER.debug("{}: health check cancelled for {}.", lbDescription, this);
            assert connState.healthCheck != null; // guaranteed by `.isUnhealthy()`
            connState.healthCheck.cancel();
        }
    }

    @Override
    public int score() {
        return healthIndicator == null ? 1 : healthIndicator.score();
    }

    @Override
    public String toString() {
        final ConnState connState = this.connState;
        String stateString = connState.isUnhealthy() ? State.UNHEALTHY + "(" + connState.healthCheck + ")" :
                connState.state.toString();
        return "DefaultHost{" +
                "lbDescription=" + lbDescription +
                ", address=" + address +
                ", state=" + stateString +
                ", healthIndicator=" + healthIndicator +
                ", #connections=" + connState.connections.size() +
                '}';
    }

    private final class HealthCheck extends DelayedCancellable {
        private final Throwable lastError;

        private HealthCheck(final Throwable lastError) {
            this.lastError = lastError;
        }

        public void schedule(final Throwable originalCause) {
            assert healthCheckConfig != null;
            delayedCancellable(
                    // Use retry strategy to utilize jitter.
                    retryWithConstantBackoffDeltaJitter(cause -> true,
                            healthCheckConfig.healthCheckInterval,
                            healthCheckConfig.jitter,
                            healthCheckConfig.executor)
                            .apply(0, originalCause)
                            // Remove any state from async context
                            .beforeOnSubscribe(__ -> AsyncContext.clear())
                            .concat(newConnection(cxn -> true, false, null)
                                    // There is no risk for StackOverflowError because result of each connection
                                    // attempt will be invoked on IoExecutor as a new task.
                                    .retryWhen(retryWithConstantBackoffDeltaJitter(
                                            cause -> {
                                                LOGGER.debug("{}: health check failed for {}.",
                                                        lbDescription, DefaultHost.this, cause);
                                                return true;
                                            },
                                            healthCheckConfig.healthCheckInterval,
                                            healthCheckConfig.jitter,
                                            healthCheckConfig.executor)))
                            .flatMapCompletable(newCnx -> {
                                LOGGER.info("{}: health check passed for {}, marked this " +
                                                "host as ACTIVE for the selection algorithm.",
                                        lbDescription, DefaultHost.this);
                                return completed();
                            })
                            // Use onErrorComplete instead of whenOnError to avoid double logging of an error inside
                            // subscribe(): SimpleCompletableSubscriber.
                            .onErrorComplete(t -> {
                                LOGGER.error("{}: health check terminated with " +
                                        "an unexpected error for {}. Marking this host as ACTIVE as a fallback " +
                                        "to allow connection attempts.", lbDescription, DefaultHost.this, t);
                                markHealthy(this);
                                return true;
                            })
                            .subscribe());
        }

        @Override
        public String toString() {
            return "HealthCheck(" + lastError + ')';
        }
    }

    private final class ConnState {

        final List<C> connections;
        final State state;
        final int failedConnections;
        @Nullable
        HealthCheck healthCheck;

        private ConnState(final List<C> connections, State state, int failedConnections,
                          @Nullable final HealthCheck healthCheck) {
            // These asserts codify the invariants of the state.
            // if the state is unhealthy there must be a healthcheck
            assert state != State.UNHEALTHY || healthCheck != null;
            // if the state is not unhealthy then there must not be a healthcheck.
            assert state == State.UNHEALTHY || healthCheck == null;
            // Only unhealthy and active states should be counting failed connections.
            assert (state == State.UNHEALTHY || state == State.ACTIVE) || failedConnections == 0;
            this.connections = connections;
            this.state = state;
            this.failedConnections = failedConnections;
            this.healthCheck = healthCheck;
        }

        ConnState toNextFailedConnection(Throwable cause) {
            assert healthCheckConfig != null;
            final int nextFailedCount = addWithOverflowProtection(this.failedConnections, 1);
            if (state == State.ACTIVE && healthCheckConfig.failedThreshold <= nextFailedCount) {
                return new ConnState(connections, State.UNHEALTHY, nextFailedCount, new HealthCheck(cause));
            } else {
                // either we're already unhealthy or not yet al the threshold so just increment the count.
                return new ConnState(connections, state, nextFailedCount, healthCheck);
            }
        }

        ConnState toActiveNoFailures() {
            return new ConnState(connections, State.ACTIVE, 0, null);
        }

        ConnState toClosed() {
            return new ConnState(connections, State.CLOSED, 0, null);
        }

        ConnState toExpired() {
            return new ConnState(connections, State.EXPIRED, 0, null);
        }

        ConnState removeConnection(C connection) {
            final int index = connections.indexOf(connection);
            if (index < 0) {
                return this;
            }
            // Create the new list.
            final List<C> newList;
            if (connections.size() == 1) {
                newList = emptyList();
            } else {
                newList = new ArrayList<>(connections.size() - 1);
                for (int i = 0; i < connections.size(); i++) {
                    if (i != index) {
                        newList.add(connections.get(i));
                    }
                }
            }
            return new ConnState(newList, state, failedConnections, healthCheck);
        }

        ConnState addNewConnection(C connection) {
            // Brute force iteration to avoid duplicates. If connections grow larger and faster lookup is required
            // we can keep a Set for faster lookups (at the cost of more memory) as well as array.
            if (connections.contains(connection)) {
                return this;
            }
            // We add the connection in a random position in our list to add some entropy which helps to avoid
            // problems with correlated connection churn.
            final int newSize = connections.size() + 1;
            final int insertionIndex = ThreadLocalRandom.current().nextInt(newSize);
            ArrayList<C> newList = new ArrayList<>(newSize);
            for (int i = 0, j = 0; i < newSize; i++) {
                newList.add(i == insertionIndex ? connection : connections.get(j++));
            }
            return new ConnState(newList, State.ACTIVE, 0, null);
        }

        boolean isActive() {
            return state == State.ACTIVE;
        }

        boolean isUnhealthy() {
            return state == State.UNHEALTHY;
        }

        @Override
        public String toString() {
            return "ConnState{" +
                    "state=" + state +
                    ", failedConnections=" + failedConnections +
                    ", healthCheck=" + healthCheck +
                    ", #connections=" + connections.size() +
                    '}';
        }
    }

    private static final class InstrumentedConnectionFactory<Addr, C extends LoadBalancedConnection>
            extends DelegatingConnectionFactory<Addr, C> {

        private final ConnectTracker connectTracker;

        InstrumentedConnectionFactory(final ConnectionFactory<Addr, C> delegate, ConnectTracker connectTracker) {
            super(delegate);
            this.connectTracker = connectTracker;
        }

        @Override
        public Single<C> newConnection(Addr addr, @Nullable ContextMap context, @Nullable TransportObserver observer) {
            return Single.defer(() -> {
                final long connectStartTime = connectTracker.beforeConnectStart();
                return delegate().newConnection(addr, context, observer)
                        .beforeFinally(new ConnectSignalConsumer(connectStartTime, connectTracker))
                        .shareContextOnSubscribe();
            });
        }
    }

    private static class ConnectSignalConsumer implements TerminalSignalConsumer {

        private final ConnectTracker connectTracker;
        private final long connectStartTime;

        ConnectSignalConsumer(final long connectStartTime, final ConnectTracker connectTracker) {
            this.connectStartTime = connectStartTime;
            this.connectTracker = connectTracker;
        }

        @Override
        public void onComplete() {
            connectTracker.onConnectSuccess(connectStartTime);
        }

        @Override
        public void cancel() {
            // We assume cancellation is the result of some sort of timeout.
            doOnError(CANCELLED);
        }

        @Override
        public void onError(Throwable t) {
            doOnError(t instanceof ConnectTimeoutException ? CONNECT_TIMEOUT : CONNECT_ERROR);
        }

        private void doOnError(ConnectTracker.ErrorClass errorClass) {
            connectTracker.onConnectError(connectStartTime, errorClass);
        }
    }
}
