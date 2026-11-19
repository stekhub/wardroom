package io.github.stekhub.wardroom.nntp;

/**
 * Runs work on pooled connections to an NNTP server, with the per-server connection limit, the circuit breaker and
 * retries (ADR 0006, ADR 0026).
 *
 * <p>A server with {@code maxConnections} connections gives at most {@code maxConnections − 1} of them (at least
 * one) to background work. The remaining one is kept for interactive actions (spec 005).
 */
public interface NntpConnections {

    /**
     * Runs background work, for example the synchronization of one group. Waits until a background connection is
     * free. A failed attempt is retried with backoff where the failure allows it.
     *
     * @throws NntpException when the work failed after all attempts or the circuit breaker is open
     */
    <T> T background(NntpServerSettings serverSettings, NntpAction<T> action);

    /**
     * Runs an action the user is waiting for, for example opening an article. Waits at most 30 seconds for a free
     * connection. A failed attempt is retried with backoff where the failure allows it.
     *
     * @throws NntpException as for background work, and with {@link NntpFailureType#NO_CONNECTION_AVAILABLE} when
     *                       no connection became free in time
     */
    <T> T interactive(NntpServerSettings serverSettings, NntpAction<T> action);

    /**
     * Like {@link #interactive(NntpServerSettings, NntpAction)}, but with a single attempt. For actions that must
     * not run twice, such as posting.
     */
    <T> T interactiveWithoutRetry(NntpServerSettings serverSettings, NntpAction<T> action);

    /**
     * Closes the idle connections to the server and closes its circuit breaker. To be called when the settings of
     * the server were saved.
     */
    void reset(long serverId);
}
