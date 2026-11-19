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
    private final RetryTemplate retrying;
    private final RetryTemplate singleAttempt = new RetryTemplate(RetryPolicy.withMaxRetries(0));
    private final Map<Long, ServerConnectionPool> pools = new ConcurrentHashMap<>();

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
                .ignoreException(e -> !countsAsFailure(e))
                .build());
        this.circuitBreakers.getEventPublisher().onEntryAdded(added -> added.getAddedEntry().getEventPublisher()
                .onStateTransition(PooledNntpConnections::logStateTransition));
        this.retrying = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(RETRIES)
                .delay(timings.firstRetryDelay())
                .multiplier(BACKOFF_MULTIPLIER)
                .predicate(e -> e instanceof NntpException failure && failure.type().retryable())
                .build());
    }

    @Override
    public <T> T background(NntpServerSettings server, NntpAction<T> action) {
        return execute(retrying, server, false, action);
    }

    @Override
    public <T> T interactive(NntpServerSettings server, NntpAction<T> action) {
        return execute(retrying, server, true, action);
    }

    @Override
    public <T> T interactiveWithoutRetry(NntpServerSettings server, NntpAction<T> action) {
        return execute(singleAttempt, server, true, action);
    }

    @Override
    public void reset(long serverId) {
        ServerConnectionPool pool = pools.get(serverId);
        if (pool != null) {
            pool.closeIdle();
        }
        circuitBreaker(serverId).reset();
    }

    @Override
    public void destroy() {
        pools.values().forEach(ServerConnectionPool::retire);
        pools.clear();
    }

    private <T> T execute(RetryTemplate retry, NntpServerSettings server, boolean interactive, NntpAction<T> action) {
        try {
            return retry.execute(() -> attempt(server, interactive, action));
        } catch (RetryException e) {
            switch (e.getCause()) {
                case NntpException failure -> {
                    if (failure.type().retryable() && retry == retrying) {
                        log.warn("NNTP request to server {} failed after {} attempts: {}", server.serverId(),
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

    private <T> T attempt(NntpServerSettings server, boolean interactive, NntpAction<T> action) {
        try {
            return circuitBreaker(server.serverId())
                    .executeSupplier(() -> poolFor(server).run(server, interactive, action));
        } catch (CallNotPermittedException e) {
            throw new NntpException(NntpFailureType.CIRCUIT_OPEN,
                    "The server failed repeatedly and is not contacted for a while", e);
        }
    }

    private CircuitBreaker circuitBreaker(long serverId) {
        return circuitBreakers.circuitBreaker(BREAKER_NAME_PREFIX + serverId);
    }

    /** A changed connection limit replaces the pool; the old one closes its connections as they become free. */
    private ServerConnectionPool poolFor(NntpServerSettings server) {
        return pools.compute(server.serverId(), (id, pool) -> {
            if (pool != null && pool.maxConnections() == server.maxConnections()) {
                return pool;
            }
            if (pool != null) {
                pool.retire();
            }
            return new ServerConnectionPool(server.maxConnections(), timings, connector);
        });
    }

    private static boolean countsAsFailure(Throwable e) {
        return e instanceof NntpException failure && failure.type().countedByCircuitBreaker();
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
