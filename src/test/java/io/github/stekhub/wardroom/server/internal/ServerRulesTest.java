package io.github.stekhub.wardroom.server.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.stekhub.wardroom.nntp.NntpTlsMode;
import io.github.stekhub.wardroom.platform.FieldValidationException;
import io.github.stekhub.wardroom.server.internal.ServerRequests.ServerRequest;
import io.github.stekhub.wardroom.server.internal.ServerRules.ValidServer;
import org.junit.jupiter.api.Test;

/** The field rules of spec 002 and their boundaries. */
class ServerRulesTest {

    private static final String PASSWORD = "s3cret-Passw0rd";

    @Test
    void nameHas1To64CharactersAfterTrimming() {
        assertValid(withName("x"));
        assertValid(withName("n".repeat(64)));
        assertValid(withName("  " + "n".repeat(64) + "  "));
        assertValid(withName("😀".repeat(64)));
        assertThat(ServerRules.validate(withName("  My provider "), false, false).name()).isEqualTo("My provider");

        assertInvalid(withName(""), "name", "The name must have 1 to 64 characters.");
        assertInvalid(withName("   "), "name", "The name must have 1 to 64 characters.");
        assertInvalid(withName("n".repeat(65)), "name", "The name must have 1 to 64 characters.");
    }

    @Test
    void hostIsAHostNameOrAnIpAddressWith1To253Characters() {
        String message = "The host must be a host name or an IP address with 1 to 253 characters.";
        assertValid(withHost("news.example.com"));
        assertValid(withHost("192.0.2.7"));
        assertValid(withHost("2001:db8::7"));
        assertValid(withHost("h".repeat(253)));
        assertThat(ServerRules.validate(withHost(" news.example.com "), false, false).host())
                .isEqualTo("news.example.com");

        assertInvalid(withHost(""), "host", message);
        assertInvalid(withHost("h".repeat(254)), "host", message);
        assertInvalid(withHost("news example.com"), "host", message);
        assertInvalid(withHost("news.example.com/path"), "host", message);
        assertInvalid(withHost("news.example.com\r\nQUIT"), "host", message);
    }

    @Test
    void portIsBetween1And65535() {
        assertValid(withPort(1));
        assertValid(withPort(65_535));

        assertInvalid(withPort(0), "port", "The port must be between 1 and 65535.");
        assertInvalid(withPort(65_536), "port", "The port must be between 1 and 65535.");
        assertInvalid(withPort(-1), "port", "The port must be between 1 and 65535.");
    }

    @Test
    void maxConnectionsIsBetween1And50() {
        assertValid(withMaxConnections(1));
        assertValid(withMaxConnections(50));

        assertInvalid(withMaxConnections(0), "maxConnections", "The number of connections must be between 1 and 50.");
        assertInvalid(withMaxConnections(51), "maxConnections",
                "The number of connections must be between 1 and 50.");
    }

    @Test
    void usernameAndPasswordNeedTls() {
        ServerRequest withoutTls = new ServerRequest("My provider", "news.example.com", 119, NntpTlsMode.NONE,
                AuthMethod.USER_PASS, "me", PASSWORD, 4);

        assertInvalid(withoutTls, "authMethod",
                "A login with username and password needs TLS, so that the password is never sent unencrypted.");
    }

    @Test
    void usernameHas1To255CharactersForALogin() {
        assertValid(withCredentials("u", PASSWORD));
        assertValid(withCredentials("u".repeat(255), PASSWORD));

        assertInvalid(withCredentials(null, PASSWORD), "username", "The username must have 1 to 255 characters.");
        assertInvalid(withCredentials("", PASSWORD), "username", "The username must have 1 to 255 characters.");
        assertInvalid(withCredentials("u".repeat(256), PASSWORD), "username",
                "The username must have 1 to 255 characters.");
    }

    @Test
    void passwordHas1To255CharactersAndIsNeededWhenNoneIsStored() {
        assertValid(withCredentials("me", "p"));
        assertValid(withCredentials("me", "p".repeat(255)));

        assertInvalid(withCredentials("me", null), "password", "The password must have 1 to 255 characters.");
        assertInvalid(withCredentials("me", ""), "password", "The password must have 1 to 255 characters.");
        assertInvalid(withCredentials("me", "p".repeat(256)), "password",
                "The password must have 1 to 255 characters.");
    }

    @Test
    void emptyPasswordUsesTheStoredOne() {
        ValidServer withoutPassword = ServerRules.validate(withCredentials("me", ""), true, false);
        ValidServer withPassword = ServerRules.validate(withCredentials("me", PASSWORD), true, false);

        assertThat(withoutPassword.newPassword()).isNull();
        assertThat(withPassword.newPassword()).isEqualTo(PASSWORD);
    }

    @Test
    void credentialsCannotStartAnotherCommand() {
        assertInvalid(withCredentials("me\r\nQUIT", PASSWORD), "username",
                "The username must not contain line breaks or other control characters.");
        assertInvalid(withCredentials("me", "secret\nQUIT"), "password",
                "The password must not contain line breaks or other control characters.");
    }

    @Test
    void withoutALoginUsernameAndPasswordAreDropped() {
        ServerRequest request = new ServerRequest("My provider", "news.example.com", 119, NntpTlsMode.NONE,
                AuthMethod.NONE, "me", PASSWORD, 4);

        ValidServer validServer = ServerRules.validate(request, true, false);

        assertThat(validServer.username()).isNull();
        assertThat(validServer.newPassword()).isNull();
    }

    @Test
    void passwordIsNotPrinted() {
        ServerRequest request = withCredentials("me", PASSWORD);

        assertThat(request.toString()).doesNotContain(PASSWORD);
        assertThat(ServerRules.validate(request, false, false).toString()).doesNotContain(PASSWORD);
    }

    private static ServerRequest withName(String name) {
        return new ServerRequest(name, "news.example.com", 563, NntpTlsMode.IMPLICIT, AuthMethod.NONE, null, null, 4);
    }

    private static ServerRequest withHost(String host) {
        return new ServerRequest("My provider", host, 563, NntpTlsMode.IMPLICIT, AuthMethod.NONE, null, null, 4);
    }

    private static ServerRequest withPort(int port) {
        return new ServerRequest("My provider", "news.example.com", port, NntpTlsMode.IMPLICIT, AuthMethod.NONE, null,
                null, 4);
    }

    private static ServerRequest withMaxConnections(int maxConnections) {
        return new ServerRequest("My provider", "news.example.com", 563, NntpTlsMode.IMPLICIT, AuthMethod.NONE, null,
                null, maxConnections);
    }

    private static ServerRequest withCredentials(String username, String password) {
        return new ServerRequest("My provider", "news.example.com", 563, NntpTlsMode.IMPLICIT, AuthMethod.USER_PASS,
                username, password, 4);
    }

    private static void assertValid(ServerRequest request) {
        assertThatCode(() -> ServerRules.validate(request, false, false)).doesNotThrowAnyException();
    }

    private static void assertInvalid(ServerRequest request, String field, String message) {
        FieldValidationException failure = catchThrowableOfType(FieldValidationException.class,
                () -> ServerRules.validate(request, false, false));
        assertThat(failure).as("a validation failure").isNotNull();
        assertThat(failure.field()).isEqualTo(field);
        assertThat(failure).hasMessage(message);
    }
}
