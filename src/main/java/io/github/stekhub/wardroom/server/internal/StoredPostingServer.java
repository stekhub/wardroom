package io.github.stekhub.wardroom.server.internal;

import io.github.stekhub.wardroom.nntp.NntpTlsMode;

/**
 * The separate server for posting as it is stored (spec 002, "Posting server").
 *
 * @param authMethod        {@code null} when the posting server uses the login of the server it belongs to
 * @param username          {@code null} unless the authentication is {@link AuthMethod#USER_PASS}
 * @param passwordEncrypted the encrypted password in the format of {@link CredentialCipher}, or {@code null}
 */
record StoredPostingServer(String host, int port, NntpTlsMode tlsMode, AuthMethod authMethod, String username,
                           byte[] passwordEncrypted) {

    boolean sameLogin() {
        return authMethod == null;
    }

    boolean passwordStored() {
        return passwordEncrypted != null;
    }

    /** The encrypted password is left out; a log line has no use for it. */
    @Override
    public String toString() {
        return "StoredPostingServer[host=" + host + ", port=" + port + ", tlsMode=" + tlsMode + ", authMethod="
                + authMethod + ", passwordStored=" + passwordStored() + "]";
    }
}
