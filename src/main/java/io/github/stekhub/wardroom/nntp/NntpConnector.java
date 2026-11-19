package io.github.stekhub.wardroom.nntp;

/**
 * Opens a dedicated connection to an NNTP server, outside the connection pool, without the circuit breaker and
 * without retries. This is for direct user actions such as the connection test; everything else uses
 * {@link NntpConnections}.
 */
public interface NntpConnector {

    /**
     * Connects, validates the certificate for {@link NntpTlsMode#IMPLICIT}, reads the greeting, requests the
     * capabilities and logs in when the settings contain a user name.
     *
     * @return the open connection; the caller closes it
     * @throws NntpException when any of these steps fails
     */
    NntpClient connect(NntpServerSettings server);
}
