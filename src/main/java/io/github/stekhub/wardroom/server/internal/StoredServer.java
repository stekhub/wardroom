package io.github.stekhub.wardroom.server.internal;

import io.github.stekhub.wardroom.nntp.NntpTlsMode;

/**
 * The NNTP server as it is stored (data model: {@code nntp_server}), without the columns of other features.
 *
 * @param id                 the ID; {@code 0} for a server that is not stored yet
 * @param username           {@code null} unless the authentication is {@link AuthMethod#USER_PASS}
 * @param passwordEncrypted  the encrypted password in the format of {@link CredentialCipher}, or {@code null}
 * @param credentialsInvalid whether the server rejected the credentials or the password can no longer be read
 * @param postingServer      the separate server for posting, or {@code null} when posts go to this server
 */
record StoredServer(long id, String name, String host, int port, NntpTlsMode tlsMode, AuthMethod authMethod,
                    String username, byte[] passwordEncrypted, int maxConnections, boolean credentialsInvalid,
                    StoredPostingServer postingServer) {

    boolean passwordStored() {
        return passwordEncrypted != null;
    }

    /** The encrypted password is left out; a log line has no use for it. */
    @Override
    public String toString() {
        return "StoredServer[id=" + id + ", host=" + host + ", port=" + port + ", tlsMode=" + tlsMode
                + ", authMethod=" + authMethod + ", passwordStored=" + passwordStored() + "]";
    }
}
