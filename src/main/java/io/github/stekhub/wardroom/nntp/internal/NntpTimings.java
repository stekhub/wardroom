package io.github.stekhub.wardroom.nntp.internal;

import java.time.Duration;

/**
 * The timeouts and waiting times of NNTP communication. In the application they are fixed to {@link #DEFAULTS}
 * (ADR 0026); tests use shorter ones.
 *
 * @param connectTimeout      how long opening the TCP connection may take
 * @param readTimeout         how long the server may take to send the next piece of an answer
 * @param firstRetryDelay     the wait before the second attempt; the wait before the third is twice as long
 * @param interactiveWait     how long an interactive action waits for a free connection
 * @param circuitOpenDuration how long a server is not contacted after its circuit opened
 * @param idleLifetime        how long an unused connection is kept for reuse. Not part of ADR 0026: servers close
 *                            idle connections after a while, and a connection that was idle longer than this is
 *                            replaced instead of risking a failure on the next command
 */
record NntpTimings(Duration connectTimeout, Duration readTimeout, Duration firstRetryDelay, Duration interactiveWait,
                   Duration circuitOpenDuration, Duration idleLifetime) {

    static final NntpTimings DEFAULTS = new NntpTimings(Duration.ofSeconds(10), Duration.ofSeconds(30),
            Duration.ofSeconds(2), Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofSeconds(60));
}
