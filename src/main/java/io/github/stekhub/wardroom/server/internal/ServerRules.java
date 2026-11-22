package io.github.stekhub.wardroom.server.internal;

import io.github.stekhub.wardroom.nntp.NntpTlsMode;
import io.github.stekhub.wardroom.platform.FieldValidationException;
import io.github.stekhub.wardroom.server.internal.ServerRequests.PostingServerRequest;
import io.github.stekhub.wardroom.server.internal.ServerRequests.ServerRequest;
import java.util.regex.Pattern;

/** The rules for the fields of a server (spec 002). Lengths are counted in characters, not in bytes. */
final class ServerRules {

    static final int NAME_MAX_LENGTH = 64;
    static final int HOST_MAX_LENGTH = 253;
    static final int PORT_MAX = 65_535;
    static final int CREDENTIAL_MAX_LENGTH = 255;
    static final int MAX_CONNECTIONS_MAX = 50;

    /** The fields of the posting server are named with this before their own name. */
    private static final String POSTING = "postingServer.";

    private static final String LOGIN_NEEDS_TLS =
            "A login with username and password needs TLS, so that the password is never sent unencrypted.";

    /** The characters of host names, IPv4 addresses and IPv6 addresses. */
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._:-]+");

    private ServerRules() {
    }

    /**
     * A request that passed all rules.
     *
     * @param name          without surrounding whitespace
     * @param host          without surrounding whitespace
     * @param username      {@code null} unless the authentication is {@link AuthMethod#USER_PASS}
     * @param newPassword   the password of the request, or {@code null} when the stored one is to be used
     * @param postingServer the separate server for posting, or {@code null} when posts go to the server itself
     */
    record ValidServer(String name, String host, int port, NntpTlsMode tlsMode, AuthMethod authMethod,
                       String username, String newPassword, int maxConnections, ValidPostingServer postingServer) {

        @Override
        public String toString() {
            return "ValidServer[password hidden]";
        }
    }

    /**
     * The separate posting server of a request that passed all rules.
     *
     * @param host        without surrounding whitespace
     * @param authMethod  {@code null} when the login of the server is used
     * @param username    {@code null} unless the authentication is {@link AuthMethod#USER_PASS}
     * @param newPassword the password of the request, or {@code null} when the stored one is to be used
     */
    record ValidPostingServer(String host, int port, NntpTlsMode tlsMode, AuthMethod authMethod, String username,
                              String newPassword) {

        @Override
        public String toString() {
            return "ValidPostingServer[password hidden]";
        }
    }

    /** A login that passed the rules; the password is {@code null} when the stored one is to be used. */
    private record ValidLogin(String username, String newPassword) {
    }

    /**
     * @param passwordStored        whether a stored password is available when the request has none
     * @param postingPasswordStored the same for the password of the posting server's own login
     * @throws FieldValidationException naming the first field that breaks a rule
     */
    static ValidServer validate(ServerRequest request, boolean passwordStored, boolean postingPasswordStored) {
        String name = request.name().strip();
        if (characterCount(name) < 1 || characterCount(name) > NAME_MAX_LENGTH) {
            throw new FieldValidationException("name", "The name must have 1 to " + NAME_MAX_LENGTH + " characters.");
        }
        String host = validHost("host", request.host());
        requireValidPort("port", request.port());
        if (request.maxConnections() < 1 || request.maxConnections() > MAX_CONNECTIONS_MAX) {
            throw new FieldValidationException("maxConnections",
                    "The number of connections must be between 1 and " + MAX_CONNECTIONS_MAX + ".");
        }
        ValidLogin login = new ValidLogin(null, null);
        if (request.authMethod() == AuthMethod.USER_PASS) {
            login = validLogin("", request.tlsMode(), request.username(), request.password(), passwordStored);
        }
        ValidPostingServer postingServer = request.postingServer() == null
                ? null
                : validPostingServer(request.postingServer(), request.authMethod(), postingPasswordStored);
        return new ValidServer(name, host, request.port(), request.tlsMode(), request.authMethod(),
                login.username(), login.newPassword(), request.maxConnections(), postingServer);
    }

    /** @param serverAuthMethod the authentication of the server, which the posting server may share */
    private static ValidPostingServer validPostingServer(PostingServerRequest request, AuthMethod serverAuthMethod,
            boolean postingPasswordStored) {
        String host = validHost(POSTING + "host", request.host());
        requireValidPort(POSTING + "port", request.port());
        if (request.sameLogin()) {
            if (serverAuthMethod == AuthMethod.USER_PASS && request.tlsMode() != NntpTlsMode.IMPLICIT) {
                throw new FieldValidationException(POSTING + "tlsMode",
                        "The posting server uses the login of the server and needs TLS, so that the password is "
                                + "never sent unencrypted.");
            }
            return new ValidPostingServer(host, request.port(), request.tlsMode(), null, null, null);
        }
        if (request.authMethod() == null) {
            throw new FieldValidationException(POSTING + "authMethod",
                    "Choose the authentication of the posting server.");
        }
        ValidLogin login = new ValidLogin(null, null);
        if (request.authMethod() == AuthMethod.USER_PASS) {
            login = validLogin(POSTING, request.tlsMode(), request.username(), request.password(),
                    postingPasswordStored);
        }
        return new ValidPostingServer(host, request.port(), request.tlsMode(), request.authMethod(),
                login.username(), login.newPassword());
    }

    private static String validHost(String field, String requestedHost) {
        String host = requestedHost.strip();
        if (host.length() > HOST_MAX_LENGTH || !HOST.matcher(host).matches()) {
            throw new FieldValidationException(field,
                    "The host must be a host name or an IP address with 1 to " + HOST_MAX_LENGTH + " characters.");
        }
        return host;
    }

    private static void requireValidPort(String field, int port) {
        if (port < 1 || port > PORT_MAX) {
            throw new FieldValidationException(field, "The port must be between 1 and " + PORT_MAX + ".");
        }
    }

    /**
     * The rules of a login with username and password.
     *
     * @param fieldPrefix what stands before the names of the fields: nothing, or the prefix of the posting server
     */
    private static ValidLogin validLogin(String fieldPrefix, NntpTlsMode tlsMode, String username, String password,
            boolean passwordStored) {
        if (tlsMode != NntpTlsMode.IMPLICIT) {
            throw new FieldValidationException(fieldPrefix + "authMethod", LOGIN_NEEDS_TLS);
        }
        requireValidCredential(fieldPrefix + "username", "username", username);
        String newPassword = password == null || password.isEmpty() ? null : password;
        if (newPassword == null && !passwordStored) {
            throw new FieldValidationException(fieldPrefix + "password", "The password must have 1 to "
                    + CREDENTIAL_MAX_LENGTH + " characters.");
        }
        if (newPassword != null) {
            requireValidCredential(fieldPrefix + "password", "password", newPassword);
        }
        return new ValidLogin(username, newPassword);
    }

    /**
     * A username or password is sent to the server as part of a command line. A line break in it would start a new
     * command, so control characters are refused.
     *
     * @param label the word for the value in the message
     */
    private static void requireValidCredential(String field, String label, String value) {
        if (value == null || characterCount(value) < 1 || characterCount(value) > CREDENTIAL_MAX_LENGTH) {
            throw new FieldValidationException(field, "The " + label + " must have 1 to " + CREDENTIAL_MAX_LENGTH
                    + " characters.");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new FieldValidationException(field, "The " + label + " must not contain line breaks or other "
                    + "control characters.");
        }
    }

    private static int characterCount(String text) {
        return text.codePointCount(0, text.length());
    }
}
