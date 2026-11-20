package io.github.stekhub.wardroom.server.internal;

import io.github.stekhub.wardroom.nntp.NntpTlsMode;
import io.github.stekhub.wardroom.platform.FieldValidationException;
import io.github.stekhub.wardroom.server.internal.ServerRequests.ServerRequest;
import java.util.regex.Pattern;

/** The rules for the fields of a server (spec 002). Lengths are counted in characters, not in bytes. */
final class ServerRules {

    static final int NAME_MAX_LENGTH = 64;
    static final int HOST_MAX_LENGTH = 253;
    static final int PORT_MAX = 65_535;
    static final int CREDENTIAL_MAX_LENGTH = 255;
    static final int MAX_CONNECTIONS_MAX = 50;

    /** The characters of host names, IPv4 addresses and IPv6 addresses. */
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._:-]+");

    private ServerRules() {
    }

    /**
     * A request that passed all rules.
     *
     * @param name        without surrounding whitespace
     * @param host        without surrounding whitespace
     * @param username    {@code null} unless the authentication is {@link AuthMethod#USER_PASS}
     * @param newPassword the password of the request, or {@code null} when the stored one is to be used
     */
    record ValidServer(String name, String host, int port, NntpTlsMode tlsMode, AuthMethod authMethod,
                       String username, String newPassword, int maxConnections) {

        @Override
        public String toString() {
            return "ValidServer[password hidden]";
        }
    }

    /**
     * @param passwordStored whether a stored password is available when the request has none
     * @throws FieldValidationException naming the first field that breaks a rule
     */
    static ValidServer validate(ServerRequest request, boolean passwordStored) {
        String name = request.name().strip();
        if (characterCount(name) < 1 || characterCount(name) > NAME_MAX_LENGTH) {
            throw new FieldValidationException("name", "The name must have 1 to " + NAME_MAX_LENGTH + " characters.");
        }
        String host = request.host().strip();
        if (host.length() > HOST_MAX_LENGTH || !HOST.matcher(host).matches()) {
            throw new FieldValidationException("host",
                    "The host must be a host name or an IP address with 1 to " + HOST_MAX_LENGTH + " characters.");
        }
        if (request.port() < 1 || request.port() > PORT_MAX) {
            throw new FieldValidationException("port", "The port must be between 1 and " + PORT_MAX + ".");
        }
        if (request.maxConnections() < 1 || request.maxConnections() > MAX_CONNECTIONS_MAX) {
            throw new FieldValidationException("maxConnections",
                    "The number of connections must be between 1 and " + MAX_CONNECTIONS_MAX + ".");
        }
        if (request.authMethod() == AuthMethod.NONE) {
            return new ValidServer(name, host, request.port(), request.tlsMode(), AuthMethod.NONE, null, null,
                    request.maxConnections());
        }
        if (request.tlsMode() != NntpTlsMode.IMPLICIT) {
            throw new FieldValidationException("authMethod",
                    "A login with username and password needs TLS, so that the password is never sent unencrypted.");
        }
        requireValidCredential("username", request.username());
        String newPassword = request.password() == null || request.password().isEmpty() ? null : request.password();
        if (newPassword == null && !passwordStored) {
            throw new FieldValidationException("password", "The password must have 1 to " + CREDENTIAL_MAX_LENGTH
                    + " characters.");
        }
        if (newPassword != null) {
            requireValidCredential("password", newPassword);
        }
        return new ValidServer(name, host, request.port(), request.tlsMode(), AuthMethod.USER_PASS,
                request.username(), newPassword, request.maxConnections());
    }

    /**
     * A username or password is sent to the server as part of a command line. A line break in it would start a new
     * command, so control characters are refused.
     */
    private static void requireValidCredential(String field, String value) {
        if (value == null || characterCount(value) < 1 || characterCount(value) > CREDENTIAL_MAX_LENGTH) {
            throw new FieldValidationException(field, "The " + field + " must have 1 to " + CREDENTIAL_MAX_LENGTH
                    + " characters.");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new FieldValidationException(field, "The " + field + " must not contain line breaks or other "
                    + "control characters.");
        }
    }

    private static int characterCount(String text) {
        return text.codePointCount(0, text.length());
    }
}
