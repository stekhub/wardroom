package io.github.stekhub.wardroom.nntp;

import java.util.Objects;

/**
 * What the NNTP adapter needs to know to talk to one server.
 *
 * @param serverId       identifies the server; connections and the circuit breaker are kept per server ID
 * @param host           host name or IP address
 * @param port           TCP port
 * @param tlsMode        how the connection is secured
 * @param username       the user name, or {@code null} to connect without authentication
 * @param password       the password in clear text; required when a user name is given. It is never logged and
 *                       not part of {@link #toString()}
 * @param maxConnections the highest number of simultaneous connections to this server
 */
public record NntpServerSettings(long serverId, String host, int port, NntpTlsMode tlsMode, String username,
                                 String password, int maxConnections) {

    public NntpServerSettings {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(tlsMode, "tlsMode");
        if (maxConnections < 1) {
            throw new IllegalArgumentException("maxConnections must be at least 1");
        }
        if (username != null && password == null) {
            throw new IllegalArgumentException("A user name requires a password");
        }
        if (username != null && tlsMode == NntpTlsMode.NONE) {
            throw new IllegalArgumentException("Credentials are sent only over TLS (ADR 0016)");
        }
    }

    /** @return whether Wardroom logs in after connecting */
    public boolean authenticates() {
        return username != null;
    }

    @Override
    public String toString() {
        return "NntpServerSettings[serverId=" + serverId + ", host=" + host + ", port=" + port + ", tlsMode=" + tlsMode
                + ", authenticates=" + authenticates() + ", maxConnections=" + maxConnections + "]";
    }
}
