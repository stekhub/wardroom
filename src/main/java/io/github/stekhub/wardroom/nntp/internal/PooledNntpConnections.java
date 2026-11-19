package io.github.stekhub.wardroom.nntp.internal;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import io.github.stekhub.wardroom.nntp.NntpAction;
import io.github.stekhub.wardroom.nntp.NntpConnections;
import io.github.stekhub.wardroom.nntp.NntpException;
import io.github.stekhub.wardroom.nntp.NntpFailureType;
import io.github.stekhub.wardroom.nntp.NntpServerSettings;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;

/**
 * The pooled connections of all servers, each with its own connection pool and circuit breaker (ADR 0006).
 *
 * <p>Every attempt passes the circuit breaker, so the circuit opens after five failed attempts in a row, and a
 * retry stops as soon as it is open.
 */
@Slf4j
@Component
public class PooledNntpConnections implements NntpConnections, DisposableBean {

    /** ADR 0026: three attempts in total. */
    private static final int RETRIES = 2;
    private static final double BACKOFF_MULTIPLIER = 2.0;
    /** ADR 0026: the circuit opens after this many failures in a row. */
    private static final int FAILURES_TO_OPEN = 5;
    private static final String BREAKER_NAME_PREFIX = "nntp-server-";

    private final CommonsNetNntpConnector connector;
    private final NntpTimings timings;
    private final CircuitBreakerRegistry circuitBreakers;
    private final RetryTemplate retryTemplate;
    private final RetryTemplate singleAttemptTemplate = new RetryTemplate(RetryPolicy.withMaxRetries(0));
    private final Map<Long, ServerConnectionPool> connectionPools = new ConcurrentHashMap<>();

    @Autowired
    public PooledNntpConnections(CommonsNetNntpConnector connector) {
        this(connector, NntpTimings.DEFAULTS);
    }

    PooledNntpConnections(CommonsNetNntpConnector connector, NntpTimings timings) {
        this.connector = connector;
        this.timings = timings;
        this.circuitBreakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                // With a window of five calls and a threshold of 100 %, the circuit opens exactly when the last
                // five attempts all failed.
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(FAILURES_TO_OPEN)
                .minimumNumberOfCalls(FAILURES_TO_OPEN)
                .failureRateThreshold(100)
                .waitDurationInOpenState(timings.circuitOpenDuration())
                .permittedNumberOfCallsInHalfOpenState(1)
                .ignoreException(exception -> !countsAsFailure(exception))
                .build());
        this.circuitBreakers.getEventPublisher().onEntryAdded(added -> added.getAddedEntry().getEventPublisher()
                .onStateTransition(PooledNntpConnections::logStateTransition));
        this.retryTemplate = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(RETRIES)
                .delay(timings.firstRetryDelay())
                .multiplier(BACKOFF_MULTIPLIER)
                .predicate(exception -> exception instanceof NntpException failure && failure.type().retryable())
                .build());
    }

    @Override
    public <T> T background(NntpServerSettings serverSettings, NntpAction<T> action) {
        return execute(retryTemplate, serverSettings, false, action);
    }

    @Override
    public <T> T interactive(NntpServerSettings serverSettings, NntpAction<T> action) {
        return execute(retryTemplate, serverSettings, true, action);
    }

    @Override
    public <T> T interactiveWithoutRetry(NntpServerSettings serverSettings, NntpAction<T> action) {
        return execute(singleAttemptTemplate, serverSettings, true, action);
    }

    @Override
    public void reset(long serverId) {
        ServerConnectionPool connectionPool = connectionPools.get(serverId);
        if (connectionPool != null) {
            connectionPool.closeIdle();
        }
        circuitBreaker(serverId).reset();
    }

    @Override
    public void destroy() {
        connectionPools.values().forEach(ServerConnectionPool::retire);
        connectionPools.clear();
    }

    private <T> T execute(RetryTemplate template, NntpServerSettings serverSettings, boolean interactive,
            NntpAction<T> action) {
        try {
            return template.execute(() -> attempt(serverSettings, interactive, action));
        } catch (RetryException e) {
            switch (e.getCause()) {
                case NntpException failure -> {
                    if (failure.type().retryable() && template == retryTemplate) {
                        log.warn("NNTP request to server {} failed after {} attempts: {}", serverSettings.serverId(),
                                e.getRetryCount() + 1, failure.getMessage());
                    }
                    throw failure;
                }
                case RuntimeException other -> throw other;
                case Error error -> throw error;
                case null, default -> throw new IllegalStateException("NNTP request failed", e);
            }
        }
    }

    private <T> T attempt(NntpServerSettings serverSettings, boolean interactive, NntpAction<T> action) {
        try {
            return circuitBreaker(serverSettings.serverId())
                    .executeSupplier(() -> poolFor(serverSettings).run(serverSettings, interactive, action));
        } catch (CallNotPermittedException e) {
            throw new NntpException(NntpFailureType.CIRCUIT_OPEN,
                    "The server failed repeatedly and is not contacted for a while", e);
        }
    }

    private CircuitBreaker circuitBreaker(long serverId) {
        return circuitBreakers.circuitBreaker(BREAKER_NAME_PREFIX + serverId);
    }

    /** A changed connection limit replaces the pool; the old one closes its connections as they become free. */
    private ServerConnectionPool poolFor(NntpServerSettings serverSettings) {
        return connectionPools.compute(serverSettings.serverId(), (id, connectionPool) -> {
            if (connectionPool != null && connectionPool.maxConnections() == serverSettings.maxConnections()) {
                return connectionPool;
            }
            if (connectionPool != null) {
                connectionPool.retire();
            }
            return new ServerConnectionPool(serverSettings.maxConnections(), timings, connector);
        });
    }

    private static boolean countsAsFailure(Throwable exception) {
        return exception instanceof NntpException failure && failure.type().countedByCircuitBreaker();
    }

    private static void logStateTransition(CircuitBreakerOnStateTransitionEvent event) {
        String serverId = event.getCircuitBreakerName().substring(BREAKER_NAME_PREFIX.length());
        switch (event.getStateTransition().getToState()) {
            case OPEN -> log.warn("Circuit opened for NNTP server {}: it failed repeatedly and is not contacted for"
                    + " a while", serverId);
            case CLOSED -> log.info("Circuit closed again for NNTP server {}", serverId);
            default -> {
            }
        }
    }
}
