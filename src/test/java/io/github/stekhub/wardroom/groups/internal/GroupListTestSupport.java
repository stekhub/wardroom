package io.github.stekhub.wardroom.groups.internal;

import static io.github.stekhub.wardroom.jooq.Tables.NEWSGROUP;
import static io.github.stekhub.wardroom.jooq.Tables.NNTP_SERVER;
import static io.github.stekhub.wardroom.jooq.Tables.SPRING_SESSION;
import static io.github.stekhub.wardroom.jooq.Tables.WARDROOM_USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.stekhub.wardroom.TestBrowser;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What the group list tests share: an owner who is logged in, a server that points to a mock, and waiting. */
final class GroupListTestSupport {

    static final Duration REFRESH_TIMEOUT = Duration.ofSeconds(60);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private GroupListTestSupport() {
    }

    /** Empties the tables and creates the account. */
    static TestBrowser loggedInOwner(MockMvcTester mvc, DSLContext database) {
        database.deleteFrom(NEWSGROUP).execute();
        database.deleteFrom(NNTP_SERVER).execute();
        database.deleteFrom(SPRING_SESSION).execute();
        database.deleteFrom(WARDROOM_USER).execute();
        TestBrowser browser = new TestBrowser(mvc);
        assertThat(browser.post("/api/auth/setup",
                TestBrowser.json("username", "owner", "password", "correct horse battery")))
                .hasStatus(HttpStatus.CREATED);
        return browser;
    }

    /**
     * Adds a server without TLS and login that points to the mock server.
     *
     * @return the ID of the server
     */
    static long addServer(TestBrowser browser, MockNntpServer mockServer) {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("name", "Mock server");
        server.put("host", "localhost");
        server.put("port", mockServer.port());
        server.put("tlsMode", "NONE");
        server.put("authMethod", "NONE");
        server.put("maxConnections", 4);
        MvcTestResult created = browser.post("/api/servers", JSON.writeValueAsString(server));
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return body(created).get("server").get("id").asLong();
    }

    static JsonNode body(MvcTestResult result) {
        try {
            return JSON.readTree(result.getResponse().getContentAsString());
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonNode refreshStatus(TestBrowser browser, long serverId) {
        MvcTestResult status = browser.get("/api/servers/" + serverId + "/groups/refresh");
        assertThat(status).hasStatusOk();
        return body(status);
    }

    /** Waits until no refresh of the server is running, and returns the status then. */
    static JsonNode awaitRefreshEnd(TestBrowser browser, long serverId) {
        await().atMost(REFRESH_TIMEOUT).pollInterval(Duration.ofMillis(20))
                .until(() -> !refreshStatus(browser, serverId).get("running").asBoolean());
        return refreshStatus(browser, serverId);
    }

    /** Asks for a refresh like the owner does, and waits for its end. */
    static JsonNode refreshAndWait(TestBrowser browser, long serverId) {
        assertThat(browser.post("/api/servers/" + serverId + "/groups/refresh")).hasStatus(HttpStatus.ACCEPTED);
        return awaitRefreshEnd(browser, serverId);
    }
}
