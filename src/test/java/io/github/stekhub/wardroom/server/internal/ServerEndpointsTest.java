package io.github.stekhub.wardroom.server.internal;

import static io.github.stekhub.wardroom.jooq.Tables.NNTP_SERVER;
import static io.github.stekhub.wardroom.jooq.Tables.SPRING_SESSION;
import static io.github.stekhub.wardroom.jooq.Tables.WARDROOM_USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.github.stekhub.wardroom.IntegrationTest;
import io.github.stekhub.wardroom.TestBrowser;
import io.github.stekhub.wardroom.nntp.NntpClient;
import io.github.stekhub.wardroom.nntp.NntpConnections;
import io.github.stekhub.wardroom.nntp.NntpConnector;
import io.github.stekhub.wardroom.nntp.NntpException;
import io.github.stekhub.wardroom.nntp.NntpFailureType;
import io.github.stekhub.wardroom.nntp.NntpServerSettings;
import io.github.stekhub.wardroom.nntp.internal.TestNntpConnectors;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import io.github.stekhub.wardroom.nntp.mock.MockReply;
import io.github.stekhub.wardroom.nntp.mock.TestCertificate;
import io.github.stekhub.wardroom.server.NntpServers;
import io.github.stekhub.wardroom.server.ServerCreated;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The testing requirements of spec 002, against the whole application, a real SQLite database and the mock NNTP
 * server. The key file rules are in {@link CredentialKeyLoaderTest} and {@link ServerStartupTest}.
 */
@IntegrationTest
@Import({ServerEndpointsTest.SwitchableConnector.class, ServerEndpointsTest.CreatedServers.class})
@ExtendWith(OutputCaptureExtension.class)
class ServerEndpointsTest {

    private static final String NNTP_USERNAME = "reader";
    private static final String NNTP_PASSWORD = "s3cret-Passw0rd";
    private static final String OTHER_NNTP_PASSWORD = "an0ther-Passw0rd";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static TestCertificate localhostCertificate;
    private static TestCertificate otherHostCertificate;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DSLContext database;

    @Autowired
    SwitchableConnector connector;

    @Autowired
    CreatedServers createdServers;

    @Autowired
    NntpServers nntpServers;

    @Autowired
    NntpConnections connections;

    TestBrowser browser;
    MockNntpServer tlsServer;
    MockNntpServer plainServer;

    /**
     * Stands in for the connector of the application, so that a test can use short timeouts and can trust the
     * certificate of the mock server.
     */
    @TestComponent
    @Primary
    static class SwitchableConnector implements NntpConnector {

        volatile NntpConnector delegate = TestNntpConnectors.withRuntimeTrust();

        @Override
        public NntpClient connect(NntpServerSettings serverSettings) {
            return delegate.connect(serverSettings);
        }
    }

    @TestComponent
    static class CreatedServers {

        final List<ServerCreated> events = new CopyOnWriteArrayList<>();

        @EventListener
        void serverCreated(ServerCreated event) {
            events.add(event);
        }
    }

    @BeforeAll
    static void createCertificates() {
        localhostCertificate = TestCertificate.forHost("localhost");
        otherHostCertificate = TestCertificate.forHost("other.example");
    }

    /** Every test starts with a logged-in owner, no server, and two mock servers that accept the login. */
    @BeforeEach
    void startLoggedInWithoutServer() throws IOException {
        database.deleteFrom(NNTP_SERVER).execute();
        database.deleteFrom(SPRING_SESSION).execute();
        database.deleteFrom(WARDROOM_USER).execute();
        createdServers.events.clear();
        browser = new TestBrowser(mvc);
        assertThat(browser.post("/api/auth/setup",
                TestBrowser.json("username", "owner", "password", "correct horse battery")))
                .hasStatus(HttpStatus.CREATED);

        plainServer = MockNntpServer.start();
        plainServer.on("CAPABILITIES").reply(MockReply.fixture("capabilities-reader.txt"));
        tlsServer = MockNntpServer.startTls(localhostCertificate.serverContext());
        tlsServer.on("CAPABILITIES").reply(MockReply.fixture("capabilities-reader.txt"));
        tlsServer.on("AUTHINFO USER").reply("381 Password required");
        tlsServer.on("AUTHINFO PASS").reply("281 Welcome");
        connector.delegate = TestNntpConnectors.trusting(localhostCertificate.trustingClientContext(tlsServer));
    }

    @AfterEach
    void stopServers() throws IOException {
        plainServer.close();
        tlsServer.close();
    }

    // Access

    @Test
    void endpointsNeedASessionAndTheCsrfToken() {
        assertThat(new TestBrowser(mvc).get("/api/servers")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(new TestBrowser(mvc).post("/api/servers", json(serverWithLogin())))
                .hasStatus(HttpStatus.UNAUTHORIZED);

        assertThat(browser.withoutCsrfToken().post("/api/servers", json(serverWithLogin())))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(database.fetchCount(NNTP_SERVER)).isZero();
    }

    // Listing and adding

    @Test
    void listIsEmptyWithoutAServer() {
        assertThat(browser.get("/api/servers")).hasStatusOk().bodyJson().isStrictlyEqualTo("[]");
    }

    @Test
    void addingAServerSavesItTestsTheConnectionAndReturnsBoth() {
        MvcTestResult created = browser.post("/api/servers", json(with(serverWithLogin(), "name", "  My provider ")));

        assertThat(created).hasStatus(HttpStatus.CREATED).bodyJson().satisfies(body -> {
            assertThat(body).extractingPath("$.server.name").isEqualTo("My provider");
            assertThat(body).extractingPath("$.server.host").isEqualTo("localhost");
            assertThat(body).extractingPath("$.server.port").isEqualTo(tlsServer.port());
            assertThat(body).extractingPath("$.server.tlsMode").isEqualTo("IMPLICIT");
            assertThat(body).extractingPath("$.server.authMethod").isEqualTo("USER_PASS");
            assertThat(body).extractingPath("$.server.username").isEqualTo(NNTP_USERNAME);
            assertThat(body).extractingPath("$.server.passwordStored").isEqualTo(true);
            assertThat(body).extractingPath("$.server.maxConnections").isEqualTo(4);
            assertThat(body).extractingPath("$.server.credentialsInvalid").isEqualTo(false);
            assertThat(body).extractingPath("$.connectionTest.success").isEqualTo(true);
            assertThat(body).extractingPath("$.connectionTest.failure").isNull();
            assertThat(body).extractingPath("$.connectionTest.postingAllowed").isEqualTo(true);
        });
        long serverId = serverId(created);
        assertThat(browser.get("/api/servers")).hasStatusOk().bodyJson().satisfies(body -> {
            assertThat(body).extractingPath("$.length()").isEqualTo(1);
            assertThat(body).extractingPath("$[0].id").isEqualTo((int) serverId);
            assertThat(body).extractingPath("$[0].name").isEqualTo("My provider");
        });
        assertThat(tlsServer.commands()).containsExactly("CAPABILITIES", "AUTHINFO USER " + NNTP_USERNAME,
                "AUTHINFO PASS " + NNTP_PASSWORD, "CAPABILITIES", "QUIT");
    }

    @Test
    void secondServerIsRefused() {
        assertThat(browser.post("/api/servers", json(serverWithLogin()))).hasStatus(HttpStatus.CREATED);

        assertThat(browser.post("/api/servers", json(serverWithoutLogin())))
                .hasStatus(HttpStatus.CONFLICT)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(database.fetchCount(NNTP_SERVER)).isEqualTo(1);
    }

    @Test
    void addingAServerAnnouncesItForTheGroupListFetchAndEditingDoesNot() {
        long serverId = serverId(browser.post("/api/servers", json(serverWithLogin())));

        assertThat(createdServers.events).containsExactly(new ServerCreated(serverId));

        assertThat(browser.put("/api/servers/" + serverId, json(with(serverWithLogin(), "name", "Renamed"))))
                .hasStatusOk();

        assertThat(createdServers.events).hasSize(1);
    }

    @Test
    void savingWithAFailingTestStillSavesAndReturnsTheFailure() throws IOException {
        Map<String, Object> unreachable = with(serverWithoutLogin(), "port", closedPort());

        MvcTestResult created = browser.post("/api/servers", json(unreachable));

        assertThat(created).hasStatus(HttpStatus.CREATED).bodyJson().satisfies(body -> {
            assertThat(body).extractingPath("$.connectionTest.success").isEqualTo(false);
            assertThat(body).extractingPath("$.connectionTest.failure.type").isEqualTo("UNREACHABLE");
            assertThat(body).extractingPath("$.connectionTest.failure.message").asString().contains("localhost");
            assertThat(body).extractingPath("$.connectionTest.postingAllowed").isNull();
        });
        assertThat(browser.get("/api/servers")).bodyJson().extractingPath("$[0].host").isEqualTo("localhost");

        assertThat(browser.put("/api/servers/" + serverId(created), json(with(unreachable, "name", "Still down"))))
                .hasStatusOk()
                .bodyJson()
                .satisfies(body -> {
                    assertThat(body).extractingPath("$.server.name").isEqualTo("Still down");
                    assertThat(body).extractingPath("$.connectionTest.failure.type").isEqualTo("UNREACHABLE");
                });
    }

    // Validation

    @Test
    void invalidFieldsAreAnsweredWithFieldAndRule() {
        assertInvalid(with(serverWithLogin(), "name", "   "), "name", "The name must have 1 to 64 characters.");
        assertInvalid(with(serverWithLogin(), "name", "n".repeat(65)), "name",
                "The name must have 1 to 64 characters.");
        assertInvalid(with(serverWithLogin(), "host", "h".repeat(254)), "host",
                "The host must be a host name or an IP address with 1 to 253 characters.");
        assertInvalid(with(serverWithLogin(), "port", 0), "port", "The port must be between 1 and 65535.");
        assertInvalid(with(serverWithLogin(), "port", 65_536), "port", "The port must be between 1 and 65535.");
        assertInvalid(with(serverWithLogin(), "maxConnections", 0), "maxConnections",
                "The number of connections must be between 1 and 50.");
        assertInvalid(with(serverWithLogin(), "maxConnections", 51), "maxConnections",
                "The number of connections must be between 1 and 50.");
        assertInvalid(with(serverWithLogin(), "username", null), "username",
                "The username must have 1 to 255 characters.");
        assertInvalid(with(serverWithLogin(), "username", "u".repeat(256)), "username",
                "The username must have 1 to 255 characters.");
        assertInvalid(with(serverWithLogin(), "password", null), "password",
                "The password must have 1 to 255 characters.");
        assertInvalid(with(serverWithLogin(), "password", "p".repeat(256)), "password",
                "The password must have 1 to 255 characters.");
        assertInvalid(with(serverWithLogin(), "port", null), "port", "port must not be null");

        assertThat(database.fetchCount(NNTP_SERVER)).isZero();
        assertThat(tlsServer.commands()).as("an invalid server is not contacted").isEmpty();
    }

    @Test
    void usernameAndPasswordWithoutTlsAreRefused() {
        Map<String, Object> loginWithoutTls = with(with(serverWithLogin(), "tlsMode", "NONE"), "port",
                plainServer.port());

        assertInvalid(loginWithoutTls, "authMethod",
                "A login with username and password needs TLS, so that the password is never sent unencrypted.");
        assertThat(plainServer.connectionCount()).isZero();
    }

    @Test
    void unknownTlsModeIsABadRequest() {
        assertThat(browser.post("/api/servers", json(with(serverWithLogin(), "tlsMode", "STARTTLS"))))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    // The password

    @Test
    void passwordIsNeverInAResponseOrInTheLog(CapturedOutput output) throws IOException {
        Logger wardroomLogger = (Logger) LoggerFactory.getLogger("io.github.stekhub.wardroom");
        Level before = wardroomLogger.getLevel();
        wardroomLogger.setLevel(Level.DEBUG);
        List<String> responses = new ArrayList<>();
        try {
            MvcTestResult created = browser.post("/api/servers", json(serverWithLogin()));
            long serverId = serverId(created);
            responses.add(body(created));
            responses.add(body(browser.get("/api/servers")));
            responses.add(body(browser.post("/api/servers/connection-test", json(serverWithLogin()))));
            tlsServer.on("AUTHINFO PASS").reply("481 Wrong password");
            responses.add(body(browser.post("/api/servers/connection-test", json(serverWithLogin()))));
            responses.add(body(browser.put("/api/servers/" + serverId, json(serverWithLogin()))));
            responses.add(body(browser.put("/api/servers/" + serverId,
                    json(with(serverWithLogin(), "password", "p".repeat(256))))));
        } finally {
            wardroomLogger.setLevel(before);
        }

        assertThat(responses).hasSize(6).allSatisfy(response -> assertThat(response).doesNotContain(NNTP_PASSWORD)
                .doesNotContain("p".repeat(20)));
        assertThat(responses.subList(0, 5)).as("no response has a field for the password")
                .allSatisfy(response -> assertThat(response).doesNotContain("\"password\""));
        assertThat(output.getAll()).contains("AUTHINFO PASS ****").doesNotContain(NNTP_PASSWORD)
                .doesNotContain("p".repeat(20));
        byte[] stored = database.select(NNTP_SERVER.PASSWORD_ENCRYPTED).from(NNTP_SERVER).fetchSingle().value1();
        assertThat(stored[0]).as("the version byte").isEqualTo((byte) 1);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(NNTP_PASSWORD);
    }

    @Test
    void emptyPasswordOnEditKeepsTheStoredPassword() {
        long serverId = serverId(browser.post("/api/servers", json(serverWithLogin())));
        byte[] storedBefore = storedPassword();

        MvcTestResult withEmptyPassword = browser.put("/api/servers/" + serverId,
                json(with(with(serverWithLogin(), "password", ""), "name", "Renamed")));
        MvcTestResult withoutPassword = browser.put("/api/servers/" + serverId,
                json(with(serverWithLogin(), "password", null)));

        assertThat(withEmptyPassword).hasStatusOk().bodyJson().satisfies(body -> {
            assertThat(body).extractingPath("$.server.name").isEqualTo("Renamed");
            assertThat(body).extractingPath("$.server.passwordStored").isEqualTo(true);
            assertThat(body).extractingPath("$.connectionTest.success").isEqualTo(true);
        });
        assertThat(withoutPassword).hasStatusOk();
        assertThat(storedPassword()).isEqualTo(storedBefore);
        assertThat(tlsServer.commands().stream().filter(command -> command.startsWith("AUTHINFO PASS")))
                .as("the test after each save logs in with the stored password")
                .containsExactly("AUTHINFO PASS " + NNTP_PASSWORD, "AUTHINFO PASS " + NNTP_PASSWORD,
                        "AUTHINFO PASS " + NNTP_PASSWORD);
    }

    @Test
    void newPasswordOnEditReplacesTheStoredPassword() {
        long serverId = serverId(browser.post("/api/servers", json(serverWithLogin())));
        byte[] storedBefore = storedPassword();

        assertThat(browser.put("/api/servers/" + serverId,
                json(with(serverWithLogin(), "password", OTHER_NNTP_PASSWORD)))).hasStatusOk();

        assertThat(storedPassword()).isNotEqualTo(storedBefore);
        assertThat(tlsServer.commands()).contains("AUTHINFO PASS " + OTHER_NNTP_PASSWORD);
    }

    @Test
    void switchingToNoAuthenticationDeletesTheStoredCredentials() {
        long serverId = serverId(browser.post("/api/servers", json(serverWithLogin())));

        MvcTestResult updated = browser.put("/api/servers/" + serverId,
                json(with(serverWithLogin(), "authMethod", "NONE")));

        assertThat(updated).hasStatusOk().bodyJson().satisfies(body -> {
            assertThat(body).extractingPath("$.server.authMethod").isEqualTo("NONE");
            assertThat(body).extractingPath("$.server.username").isNull();
            assertThat(body).extractingPath("$.server.passwordStored").isEqualTo(false);
        });
        assertThat(database.select(NNTP_SERVER.USERNAME, NNTP_SERVER.PASSWORD_ENCRYPTED).from(NNTP_SERVER)
                .fetchSingle()).satisfies(row -> {
                    assertThat(row.value1()).isNull();
                    assertThat(row.value2()).isNull();
                });
        assertThat(browser.put("/api/servers/" + serverId, json(with(serverWithLogin(), "password", ""))))
                .as("after that, a login needs the password again")
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    // Connection test

    @Test
    void connectionTestSucceedsAndSavesNothing() {
        assertThat(browser.post("/api/servers/connection-test", json(serverWithLogin())))
                .hasStatusOk()
                .bodyJson()
                .isStrictlyEqualTo("{\"success\": true, \"failure\": null, \"postingAllowed\": true}");

        assertThat(tlsServer.commands()).containsExactly("CAPABILITIES", "AUTHINFO USER " + NNTP_USERNAME,
                "AUTHINFO PASS " + NNTP_PASSWORD, "CAPABILITIES", "QUIT");
        assertThat(database.fetchCount(NNTP_SERVER)).isZero();
        assertThat(createdServers.events).isEmpty();
    }

    @Test
    void serverWithoutCapabilitiesPassesAndPostingComesFromTheGreeting() throws IOException {
        try (MockNntpServer oldServer = MockNntpServer.start()) {
            oldServer.greeting().reply("201 Old server ready, no posting");

            assertThat(browser.post("/api/servers/connection-test",
                    json(with(serverWithoutLogin(), "port", oldServer.port()))))
                    .hasStatusOk()
                    .bodyJson()
                    .isStrictlyEqualTo("{\"success\": true, \"failure\": null, \"postingAllowed\": false}");
            assertThat(oldServer.commands()).containsExactly("CAPABILITIES", "QUIT");
        }
    }

    @Test
    void unreachableServerIsReported() throws IOException {
        assertFailedTest(with(serverWithoutLogin(), "port", closedPort()), "UNREACHABLE");
    }

    @Test
    void silentServerIsATimeout() {
        plainServer.greeting().reply(MockReply.silence());

        assertFailedTest(serverWithoutLogin(), "TIMEOUT");
    }

    @Test
    void untrustedCertificateIsReported() {
        connector.delegate = TestNntpConnectors.withRuntimeTrust();

        assertFailedTest(serverWithLogin(), "TLS_CERTIFICATE").contains("not trusted");
        assertThat(tlsServer.commands()).as("nothing is sent to a server with an untrusted certificate").isEmpty();
    }

    @Test
    void certificateForAnotherHostNameIsReported() throws IOException {
        try (MockNntpServer otherHost = MockNntpServer.startTls(otherHostCertificate.serverContext())) {
            connector.delegate = TestNntpConnectors.trusting(otherHostCertificate.trustingClientContext(otherHost));

            assertFailedTest(with(serverWithLogin(), "port", otherHost.port()), "TLS_CERTIFICATE");
            assertThat(otherHost.commands()).isEmpty();
        }
    }

    @Test
    void serverThatDoesNotSpeakTlsIsAHandshakeFailure() {
        assertFailedTest(with(serverWithLogin(), "port", plainServer.port()), "TLS_HANDSHAKE");
    }

    @Test
    void rejectedLoginIsReportedWithoutTheCredentials() {
        tlsServer.on("AUTHINFO PASS").reply("481 Authentication failed");

        assertFailedTest(serverWithLogin(), "AUTH_FAILED").doesNotContain(NNTP_PASSWORD);
    }

    @Test
    void refusingServerIsReportedAndContactedOnce() {
        plainServer.greeting().reply("502 Access denied for your address");

        assertFailedTest(serverWithoutLogin(), "SERVER_REFUSED").contains("502 Access denied");
        assertThat(plainServer.connectionCount()).as("the test is not retried").isEqualTo(1);
    }

    @Test
    void serverThatClosesTheConnectionCountsAsRefusing() {
        plainServer.greeting().reply(MockReply.drop());

        assertFailedTest(serverWithoutLogin(), "SERVER_REFUSED");
    }

    @Test
    void unexpectedResponseIsAProtocolFailure() {
        plainServer.greeting().reply("299 What is this");

        assertFailedTest(serverWithoutLogin(), "PROTOCOL");
    }

    @Test
    void connectionTestWithAnIdUsesTheStoredPasswordWhenNoneIsGiven() {
        long serverId = serverId(browser.post("/api/servers", json(serverWithLogin())));
        int commandsBefore = tlsServer.commands().size();
        Map<String, Object> formWithoutPassword = with(with(serverWithLogin(), "password", ""), "id", serverId);

        assertThat(browser.post("/api/servers/connection-test", json(formWithoutPassword)))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.success")
                .isEqualTo(true);

        assertThat(tlsServer.commands().subList(commandsBefore, tlsServer.commands().size()))
                .contains("AUTHINFO PASS " + NNTP_PASSWORD);
    }

    @Test
    void connectionTestWithoutAStoredPasswordNeedsOne() {
        assertThat(browser.post("/api/servers/connection-test", json(with(serverWithLogin(), "password", ""))))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.errors[0].field")
                .isEqualTo("password");
        assertThat(browser.post("/api/servers/connection-test", json(with(serverWithLogin(), "id", 4711))))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    // Invalid credentials

    @Test
    void loginRejectedWhileSavingMarksTheCredentialsAndNewOnesClearTheMark() {
        tlsServer.on("AUTHINFO PASS").reply("481 Authentication failed");

        MvcTestResult created = browser.post("/api/servers", json(serverWithLogin()));

        assertThat(created).hasStatus(HttpStatus.CREATED).bodyJson().satisfies(body -> {
            assertThat(body).extractingPath("$.connectionTest.failure.type").isEqualTo("AUTH_FAILED");
            assertThat(body).extractingPath("$.server.credentialsInvalid").isEqualTo(true);
        });
        assertThat(browser.get("/api/servers")).bodyJson().extractingPath("$[0].credentialsInvalid").isEqualTo(true);

        tlsServer.on("AUTHINFO PASS").reply("281 Welcome");
        assertThat(browser.put("/api/servers/" + serverId(created),
                json(with(serverWithLogin(), "password", OTHER_NNTP_PASSWORD))))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.server.credentialsInvalid")
                .isEqualTo(false);
    }

    @Test
    void credentialsRejectedLaterAreMarkedUntilNewOnesAreSaved(CapturedOutput output) {
        long serverId = serverId(browser.post("/api/servers", json(serverWithLogin())));

        nntpServers.markCredentialsInvalid(serverId);

        assertThat(browser.get("/api/servers")).bodyJson().extractingPath("$[0].credentialsInvalid").isEqualTo(true);
        assertThat(output).contains("rejected the stored username and password");

        // Saving while the server is unreachable says nothing about the credentials: unchanged ones stay marked.
        tlsServer.greeting().once(MockReply.drop());
        assertThat(browser.put("/api/servers/" + serverId, json(with(serverWithLogin(), "password", ""))))
                .bodyJson().extractingPath("$.server.credentialsInvalid").isEqualTo(true);
        tlsServer.greeting().once(MockReply.drop());
        assertThat(browser.put("/api/servers/" + serverId,
                json(with(serverWithLogin(), "password", OTHER_NNTP_PASSWORD))))
                .as("new credentials clear the mark, also when they could not be tested")
                .bodyJson().extractingPath("$.server.credentialsInvalid").isEqualTo(false);
    }

    @Test
    void passwordThatCannotBeDecryptedMarksTheCredentialsAsInvalid(CapturedOutput output) {
        long serverId = serverId(browser.post("/api/servers", json(serverWithLogin())));
        byte[] damaged = storedPassword();
        damaged[damaged.length - 1] ^= 1;
        database.update(NNTP_SERVER).set(NNTP_SERVER.PASSWORD_ENCRYPTED, damaged).execute();
        int connectionsBefore = tlsServer.connectionCount();

        MvcTestResult tested = browser.post("/api/servers/connection-test",
                json(with(with(serverWithLogin(), "password", ""), "id", serverId)));

        assertThat(tested).hasStatusOk().bodyJson().satisfies(body -> {
            assertThat(body).extractingPath("$.success").isEqualTo(false);
            assertThat(body).extractingPath("$.failure.type").isEqualTo("AUTH_FAILED");
            assertThat(body).extractingPath("$.failure.message")
                    .isEqualTo("The stored password can no longer be read. Enter the password again.");
        });
        assertThat(tlsServer.connectionCount()).as("the server is not contacted").isEqualTo(connectionsBefore);
        assertThat(browser.get("/api/servers")).bodyJson().extractingPath("$[0].credentialsInvalid").isEqualTo(true);
        assertThat(output).contains("WARN").contains("cannot be decrypted");

        NntpException failure = catchThrowableOfType(NntpException.class,
                () -> nntpServers.connectionSettings(serverId));
        assertThat(failure.type()).isEqualTo(NntpFailureType.AUTH_FAILED);
    }

    // What other modules get

    @Test
    void otherModulesGetTheSettingsWithTheDecryptedPassword() {
        long serverId = serverId(browser.post("/api/servers", json(serverWithLogin())));

        NntpServerSettings serverSettings = nntpServers.connectionSettings(serverId).orElseThrow();

        assertThat(serverSettings.serverId()).isEqualTo(serverId);
        assertThat(serverSettings.host()).isEqualTo("localhost");
        assertThat(serverSettings.port()).isEqualTo(tlsServer.port());
        assertThat(serverSettings.username()).isEqualTo(NNTP_USERNAME);
        assertThat(serverSettings.password()).isEqualTo(NNTP_PASSWORD);
        assertThat(serverSettings.maxConnections()).isEqualTo(4);
        assertThat(nntpServers.connectionSettings(serverId + 1)).isEmpty();
    }

    @Test
    void savingClosesTheCircuitBreakerOfTheServer() throws IOException {
        Map<String, Object> unreachable = with(serverWithoutLogin(), "port", closedPort());
        long serverId = serverId(browser.post("/api/servers", json(unreachable)));
        NntpServerSettings unreachableSettings = nntpServers.connectionSettings(serverId).orElseThrow();
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(failureOfARequest(unreachableSettings)).isEqualTo(NntpFailureType.UNREACHABLE);
        }
        assertThat(failureOfARequest(unreachableSettings)).isEqualTo(NntpFailureType.CIRCUIT_OPEN);

        assertThat(browser.put("/api/servers/" + serverId, json(serverWithoutLogin()))).hasStatusOk();

        NntpServerSettings reachableSettings = nntpServers.connectionSettings(serverId).orElseThrow();
        boolean reader = connections.interactiveWithoutRetry(reachableSettings,
                client -> client.capabilities().has("READER"));
        assertThat(reader).as("the server is contacted again").isTrue();
        connections.reset(serverId);
    }

    // Editing and removal

    @Test
    void editingHostAndPortKeepsTheServerAndWhatBelongsToIt() {
        long serverId = serverId(browser.post("/api/servers", json(serverWithLogin())));
        database.update(NNTP_SERVER)
                .set(NNTP_SERVER.GROUPS_REFRESHED_AT, 1_700_000_000_000L)
                .set(NNTP_SERVER.POSTING_NAME, "Owner")
                .execute();

        MvcTestResult updated = browser.put("/api/servers/" + serverId, json(with(with(with(serverWithoutLogin(),
                "host", "127.0.0.1"), "port", plainServer.port()), "maxConnections", 9)));

        assertThat(updated).hasStatusOk().bodyJson().satisfies(body -> {
            assertThat(body).extractingPath("$.server.id").isEqualTo((int) serverId);
            assertThat(body).extractingPath("$.server.host").isEqualTo("127.0.0.1");
            assertThat(body).extractingPath("$.server.port").isEqualTo(plainServer.port());
            assertThat(body).extractingPath("$.server.maxConnections").isEqualTo(9);
            assertThat(body).extractingPath("$.connectionTest.success").isEqualTo(true);
        });
        assertThat(database.select(NNTP_SERVER.GROUPS_REFRESHED_AT, NNTP_SERVER.POSTING_NAME).from(NNTP_SERVER)
                .fetchSingle()).satisfies(row -> {
                    assertThat(row.value1()).isEqualTo(1_700_000_000_000L);
                    assertThat(row.value2()).isEqualTo("Owner");
                });
    }

    @Test
    void unknownServerIsNotFound() {
        assertThat(browser.put("/api/servers/4711", json(serverWithLogin())))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(browser.delete("/api/servers/4711")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(browser.delete("/api/servers/99999999999")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void removingTheServerDeletesItAndAllowsANewOne() {
        long serverId = serverId(browser.post("/api/servers", json(serverWithLogin())));

        assertThat(browser.delete("/api/servers/" + serverId)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(browser.get("/api/servers")).bodyJson().isStrictlyEqualTo("[]");
        assertThat(database.fetchCount(NNTP_SERVER)).isZero();
        assertThat(browser.delete("/api/servers/" + serverId)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(browser.post("/api/servers", json(serverWithoutLogin()))).hasStatus(HttpStatus.CREATED);
    }

    // Account creation (ADR 0017)

    @Test
    void newAccountDoesNotInheritTheNntpCredentials() {
        browser.post("/api/servers", json(with(serverWithLogin(), "maxConnections", 7)));
        database.deleteFrom(WARDROOM_USER).execute();

        TestBrowser newOwner = new TestBrowser(mvc);
        assertThat(newOwner.post("/api/auth/setup",
                TestBrowser.json("username", "owner", "password", "another long password")))
                .hasStatus(HttpStatus.CREATED);

        assertThat(newOwner.get("/api/servers")).hasStatusOk().bodyJson().satisfies(body -> {
            assertThat(body).extractingPath("$[0].authMethod").isEqualTo("NONE");
            assertThat(body).extractingPath("$[0].username").isNull();
            assertThat(body).extractingPath("$[0].passwordStored").isEqualTo(false);
            assertThat(body).extractingPath("$[0].credentialsInvalid").isEqualTo(false);
            assertThat(body).as("the connection settings are kept").extractingPath("$[0].host").isEqualTo("localhost");
            assertThat(body).extractingPath("$[0].port").isEqualTo(tlsServer.port());
            assertThat(body).extractingPath("$[0].tlsMode").isEqualTo("IMPLICIT");
            assertThat(body).extractingPath("$[0].maxConnections").isEqualTo(7);
        });
        assertThat(storedPassword()).isNull();
    }

    private Map<String, Object> serverWithLogin() {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("name", "My provider");
        server.put("host", "localhost");
        server.put("port", tlsServer.port());
        server.put("tlsMode", "IMPLICIT");
        server.put("authMethod", "USER_PASS");
        server.put("username", NNTP_USERNAME);
        server.put("password", NNTP_PASSWORD);
        server.put("maxConnections", 4);
        return server;
    }

    private Map<String, Object> serverWithoutLogin() {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("name", "Open server");
        server.put("host", "localhost");
        server.put("port", plainServer.port());
        server.put("tlsMode", "NONE");
        server.put("authMethod", "NONE");
        server.put("maxConnections", 4);
        return server;
    }

    /** @return a copy with one field changed; {@code null} removes the field */
    private static Map<String, Object> with(Map<String, Object> server, String field, Object value) {
        Map<String, Object> changed = new LinkedHashMap<>(server);
        if (value == null) {
            changed.remove(field);
        } else {
            changed.put(field, value);
        }
        return changed;
    }

    private static String json(Map<String, Object> server) {
        return JSON.writeValueAsString(server);
    }

    private static String body(MvcTestResult result) throws IOException {
        return result.getResponse().getContentAsString();
    }

    private static long serverId(MvcTestResult saved) {
        try {
            JsonNode body = JSON.readTree(saved.getResponse().getContentAsString());
            return body.get("server").get("id").asLong();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] storedPassword() {
        return database.select(NNTP_SERVER.PASSWORD_ENCRYPTED).from(NNTP_SERVER).fetchSingle().value1();
    }

    private void assertInvalid(Map<String, Object> server, String field, String message) {
        for (String path : List.of("/api/servers", "/api/servers/connection-test")) {
            assertThat(browser.post(path, json(server)))
                    .as("%s with an invalid %s", path, field)
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyJson()
                    .satisfies(body -> {
                        assertThat(body).extractingPath("$.errors[0].field").isEqualTo(field);
                        assertThat(body).extractingPath("$.detail").isEqualTo(message);
                    });
        }
    }

    /** @return an assertion on the message of the failure */
    private org.assertj.core.api.AbstractStringAssert<?> assertFailedTest(Map<String, Object> server,
            String failureType) {
        MvcTestResult tested = browser.post("/api/servers/connection-test", json(server));
        assertThat(tested).as("a failed test is a result, not an error").hasStatusOk();
        try {
            JsonNode body = JSON.readTree(tested.getResponse().getContentAsString());
            assertThat(body.get("success").asBoolean()).isFalse();
            assertThat(body.get("failure").get("type").asString()).isEqualTo(failureType);
            assertThat(body.get("postingAllowed").isNull()).isTrue();
            return assertThat(body.get("failure").get("message").asString()).isNotBlank();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private NntpFailureType failureOfARequest(NntpServerSettings serverSettings) {
        NntpException failure = catchThrowableOfType(NntpException.class,
                () -> connections.interactiveWithoutRetry(serverSettings, NntpClient::capabilities));
        return failure.type();
    }

    /** A port that was open and is closed again, so nothing listens there. */
    private static int closedPort() throws IOException {
        try (ServerSocket closedSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            return closedSocket.getLocalPort();
        }
    }
}
