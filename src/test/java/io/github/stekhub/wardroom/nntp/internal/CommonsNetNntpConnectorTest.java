package io.github.stekhub.wardroom.nntp.internal;

import static io.github.stekhub.wardroom.nntp.internal.NntpTestSupport.FAST;
import static io.github.stekhub.wardroom.nntp.internal.NntpTestSupport.PASSWORD;
import static io.github.stekhub.wardroom.nntp.internal.NntpTestSupport.USERNAME;
import static io.github.stekhub.wardroom.nntp.internal.NntpTestSupport.plain;
import static io.github.stekhub.wardroom.nntp.internal.NntpTestSupport.tls;
import static io.github.stekhub.wardroom.nntp.internal.NntpTestSupport.tlsWithLogin;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.github.stekhub.wardroom.nntp.NntpClient;
import io.github.stekhub.wardroom.nntp.NntpException;
import io.github.stekhub.wardroom.nntp.NntpFailureType;
import io.github.stekhub.wardroom.nntp.NntpServerSettings;
import io.github.stekhub.wardroom.nntp.NntpTlsMode;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import io.github.stekhub.wardroom.nntp.mock.MockReply;
import io.github.stekhub.wardroom.nntp.mock.TestCertificate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The NNTP adapter against the mock server: connecting, TLS, login, capabilities and the failure cases. */
@ExtendWith(OutputCaptureExtension.class)
class CommonsNetNntpConnectorTest {

    private static TestCertificate localhostCertificate;
    private static TestCertificate otherHostCertificate;

    private MockNntpServer server;
    private CommonsNetNntpConnector connector;

    @BeforeAll
    static void createCertificates() {
        localhostCertificate = TestCertificate.forHost("localhost");
        otherHostCertificate = TestCertificate.forHost("other.example");
    }

    @BeforeEach
    void startServer() throws Exception {
        server = MockNntpServer.start();
        connector = new CommonsNetNntpConnector(FAST, SSLContext.getDefault());
    }

    @AfterEach
    void stopServer() throws IOException {
        server.close();
    }

    @Nested
    class Connecting {

        @Test
        void readsTheGreetingAndTheCapabilities() {
            server.on("CAPABILITIES").reply(MockReply.fixture("capabilities-reader.txt"));

            try (NntpClient client = connector.connect(plain(server))) {
                assertThat(client.postingAllowedByGreeting()).isTrue();
                assertThat(client.capabilities().supported()).isTrue();
                assertThat(client.capabilities().has("READER")).isTrue();
                assertThat(client.capabilities().has("post")).isTrue();
                assertThat(client.capabilities().has("STARTTLS")).isFalse();
                assertThat(client.capabilities().arguments("LIST")).containsExactly("ACTIVE", "NEWSGROUPS",
                        "OVERVIEW.FMT");
            }

            assertThat(server.commands()).containsExactly("CAPABILITIES", "QUIT");
        }

        @Test
        void greeting201MeansPostingIsNotAllowed() {
            server.greeting().reply("201 Ready, no posting");

            try (NntpClient client = connector.connect(plain(server))) {
                assertThat(client.postingAllowedByGreeting()).isFalse();
            }
        }

        @Test
        void serverWithoutCapabilitiesCommandIsStillUsable() {
            // The mock answers unscripted commands with 500, like a server from before RFC 3977.
            try (NntpClient client = connector.connect(plain(server))) {
                assertThat(client.capabilities().supported()).isFalse();
                assertThat(client.capabilities().has("READER")).isFalse();
            }
        }

        @Test
        void closingTwiceIsHarmless() {
            NntpClient client = connector.connect(plain(server));

            client.close();
            client.close();

            assertThat(server.commands()).containsOnlyOnce("QUIT");
        }
    }

    @Nested
    class GreetingFailures {

        @Test
        void accessDeniedIsRefused() {
            server.greeting().reply("502 Access denied for your address");

            assertFailure(plain(server), NntpFailureType.SERVER_REFUSED).hasMessageContaining("502 Access denied");
        }

        @Test
        void serviceUnavailableIsRefused() {
            server.greeting().reply("400 Too many connections");

            assertFailure(plain(server), NntpFailureType.SERVER_REFUSED);
        }

        @Test
        void unexpectedCodeIsAProtocolAnomaly(CapturedOutput output) {
            server.greeting().reply("299 What is this");

            assertFailure(plain(server), NntpFailureType.PROTOCOL).hasMessageContaining("299 What is this");

            assertThat(output).contains("PROTOCOL_ANOMALY kind=response.unexpected-code action=connection-closed"
                    + " raw=\"299 What is this\"");
        }

        @Test
        void lineWithoutResponseCodeIsAProtocolAnomaly(CapturedOutput output) {
            server.greeting().reply("Welcome to my server");

            assertFailure(plain(server), NntpFailureType.PROTOCOL);

            assertThat(output).contains("PROTOCOL_ANOMALY kind=response.malformed-line action=connection-closed"
                    + " raw=\"Welcome to my server\"");
        }

        @Test
        void serverLineCannotForgeALogLine(CapturedOutput output) {
            server.greeting().reply(MockReply.raw("299 odd\rforged-entry\r\n".getBytes(StandardCharsets.ISO_8859_1)));

            assertFailure(plain(server), NntpFailureType.PROTOCOL);

            assertThat(output.getOut().lines().filter(line -> line.startsWith("forged-entry"))).isEmpty();
            assertThat(output).contains("299 odd\\rforged-entry");
        }

        @Test
        void droppedConnectionIsConnectionLost() {
            server.greeting().reply(MockReply.drop());

            assertFailure(plain(server), NntpFailureType.CONNECTION_LOST);
        }

        @Test
        void silentServerIsATimeout() {
            server.greeting().reply(MockReply.silence());

            assertFailure(plain(server), NntpFailureType.TIMEOUT);
        }

        @Test
        void closedPortIsUnreachable() throws IOException {
            NntpServerSettings settings = plain(server);
            server.close();

            assertFailure(settings, NntpFailureType.UNREACHABLE);
        }
    }

    @Nested
    class CapabilitiesFailures {

        @Test
        void listWithoutItsTerminatingDotIsATimeout() {
            server.on("CAPABILITIES").reply("101 Capability list:", "VERSION 2");

            // The list never ends, so the client waits for the next line until the read timeout.
            assertFailure(plain(server), NntpFailureType.TIMEOUT);
        }

        @Test
        void serviceUnavailableIsRefused() {
            server.on("CAPABILITIES").reply("400 Shutting down");

            assertFailure(plain(server), NntpFailureType.SERVER_REFUSED);
        }
    }

    @Nested
    class Tls {

        private MockNntpServer tlsServer;

        @BeforeEach
        void startTlsServer() throws IOException {
            tlsServer = MockNntpServer.startTls(localhostCertificate.serverContext());
            connector = new CommonsNetNntpConnector(FAST, localhostCertificate.trustingClientContext(tlsServer));
        }

        @AfterEach
        void stopTlsServer() throws IOException {
            tlsServer.close();
        }

        @Test
        void connectsWithATrustedCertificateForTheHost() {
            tlsServer.on("CAPABILITIES").reply(MockReply.fixture("capabilities-reader.txt"));

            try (NntpClient client = connector.connect(tls(tlsServer))) {
                assertThat(client.capabilities().has("READER")).isTrue();
            }
        }

        @Test
        void certificateThatTheRuntimeDoesNotTrustIsRejected() throws Exception {
            connector = new CommonsNetNntpConnector(FAST, SSLContext.getDefault());

            assertFailure(tls(tlsServer), NntpFailureType.TLS_CERTIFICATE).hasMessageContaining("not trusted");
            assertThat(tlsServer.commands()).isEmpty();
        }

        @Test
        void trustedCertificateForAnotherHostIsRejected() throws IOException {
            try (MockNntpServer otherHost = MockNntpServer.startTls(otherHostCertificate.serverContext())) {
                connector = new CommonsNetNntpConnector(FAST, otherHostCertificate.trustingClientContext(otherHost));

                assertFailure(tls(otherHost), NntpFailureType.TLS_CERTIFICATE);
                assertThat(otherHost.commands()).isEmpty();
            }
        }

        @Test
        void serverThatDoesNotSpeakTlsIsAHandshakeFailure() {
            assertFailure(tls(server), NntpFailureType.TLS_HANDSHAKE);
        }

        @Test
        void logsInAndReadsTheCapabilitiesAgain() {
            tlsServer.on("CAPABILITIES").once(MockReply.lines("101 Capability list:", "VERSION 2", "AUTHINFO USER",
                    ".")).reply(MockReply.fixture("capabilities-reader.txt"));
            tlsServer.on("AUTHINFO USER").reply("381 Password required");
            tlsServer.on("AUTHINFO PASS").reply("281 Welcome");

            try (NntpClient client = connector.connect(tlsWithLogin(tlsServer))) {
                assertThat(client.capabilities().has("POST")).isTrue();
            }

            assertThat(tlsServer.commands()).containsExactly("CAPABILITIES", "AUTHINFO USER " + USERNAME,
                    "AUTHINFO PASS " + PASSWORD, "CAPABILITIES", "QUIT");
        }

        @Test
        void serverThatNeedsNoPasswordIsAccepted() {
            tlsServer.on("AUTHINFO USER").reply("281 Welcome");

            connector.connect(tlsWithLogin(tlsServer)).close();

            assertThat(tlsServer.commands()).noneMatch(command -> command.startsWith("AUTHINFO PASS"));
        }

        @Test
        void rejectedCredentialsAreAnAuthenticationFailure() {
            tlsServer.on("AUTHINFO USER").reply("381 Password required");
            tlsServer.on("AUTHINFO PASS").reply("481 Authentication failed");

            assertFailure(tlsWithLogin(tlsServer), NntpFailureType.AUTH_FAILED)
                    .hasMessageNotContaining(PASSWORD);
        }

        @Test
        void accessDeniedAtLoginIsRefused() {
            tlsServer.on("AUTHINFO USER").reply("502 Authentication not possible");

            assertFailure(tlsWithLogin(tlsServer), NntpFailureType.SERVER_REFUSED);
        }

        @Test
        void unexpectedCodeAtLoginIsAProtocolAnomalyWithoutThePassword(CapturedOutput output) {
            tlsServer.on("AUTHINFO USER").reply("381 Password required");
            tlsServer.on("AUTHINFO PASS").reply("299 Odd answer");

            assertFailure(tlsWithLogin(tlsServer), NntpFailureType.PROTOCOL).hasMessageNotContaining(PASSWORD);

            assertThat(output).contains("PROTOCOL_ANOMALY kind=response.unexpected-code command=\"AUTHINFO PASS\"")
                    .doesNotContain(PASSWORD);
        }

        @Test
        void passwordIsMaskedInTheDebugLog(CapturedOutput output) {
            Logger nntpLogger = (Logger) LoggerFactory.getLogger("io.github.stekhub.wardroom.nntp");
            Level before = nntpLogger.getLevel();
            nntpLogger.setLevel(Level.DEBUG);
            try {
                tlsServer.on("AUTHINFO USER").reply("381 Password required");
                tlsServer.on("AUTHINFO PASS").reply("281 Welcome");

                connector.connect(tlsWithLogin(tlsServer)).close();
            } finally {
                nntpLogger.setLevel(before);
            }

            assertThat(output).contains("> AUTHINFO USER " + USERNAME).contains("> AUTHINFO PASS ****")
                    .contains("< 281 Welcome").doesNotContain(PASSWORD);
        }
    }

    @Nested
    class Settings {

        @Test
        void credentialsWithoutTlsAreRejectedBeforeAnythingIsSent() {
            assertThatThrownBy(() -> new NntpServerSettings(1, "localhost", 119, NntpTlsMode.NONE, USERNAME,
                    PASSWORD, 4)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void textFormDoesNotContainThePassword() {
            NntpServerSettings settings = new NntpServerSettings(1, "localhost", 563, NntpTlsMode.IMPLICIT, USERNAME,
                    PASSWORD, 4);

            assertThat(settings.toString()).doesNotContain(PASSWORD).contains("localhost");
        }
    }

    private org.assertj.core.api.AbstractThrowableAssert<?, NntpException> assertFailure(NntpServerSettings settings,
            NntpFailureType expected) {
        NntpException failure = catchThrowableOfType(NntpException.class, () -> connector.connect(settings));
        assertThat(failure).as("connecting must fail").isNotNull();
        assertThat(failure.type()).isEqualTo(expected);
        return assertThat(failure);
    }
}
