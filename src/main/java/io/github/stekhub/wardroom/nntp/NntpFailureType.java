package io.github.stekhub.wardroom.nntp;

/**
 * Why talking to an NNTP server failed. Normal results such as "no such group" are not failures (ADR 0006).
 */
public enum NntpFailureType {

    /** Host not found, connection refused, no route. */
    UNREACHABLE(true, true),

    /** The server did not accept the connection or did not answer in time. */
    TIMEOUT(true, true),

    /** The certificate is not trusted or does not match the host name (ADR 0016). Trying again does not help. */
    TLS_CERTIFICATE(false, true),

    /** TLS could not be set up for another reason, for example because the port does not speak TLS. */
    TLS_HANDSHAKE(true, true),

    /** The server rejected the credentials ({@code 481}). Never retried, to avoid account lockouts. */
    AUTH_FAILED(false, false),

    /** The server refused service ({@code 400}, {@code 502}). */
    SERVER_REFUSED(true, true),

    /** The server sent something the protocol does not allow at this point. Logged as a protocol anomaly. */
    PROTOCOL(false, false),

    /** The connection broke after it was established. */
    CONNECTION_LOST(true, true),

    /** The circuit breaker of the server is open; the server was not contacted. */
    CIRCUIT_OPEN(false, false),

    /** No connection to the server became free in time; the server was not contacted. */
    NO_CONNECTION_AVAILABLE(false, false);

    private final boolean retryable;
    private final boolean countedByCircuitBreaker;

    NntpFailureType(boolean retryable, boolean countedByCircuitBreaker) {
        this.retryable = retryable;
        this.countedByCircuitBreaker = countedByCircuitBreaker;
    }

    /** @return whether another attempt can succeed without anyone changing something */
    public boolean retryable() {
        return retryable;
    }

    /** @return whether the failure says that the server is in trouble and counts toward opening the circuit */
    public boolean countedByCircuitBreaker() {
        return countedByCircuitBreaker;
    }
}
