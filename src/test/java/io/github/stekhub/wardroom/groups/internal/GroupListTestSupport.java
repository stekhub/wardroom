package io.github.stekhub.wardroom.groups.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.stekhub.wardroom.TestBrowser;
import io.github.stekhub.wardroom.TestInstallation;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import java.time.Duration;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

/** What the group list tests share: an owner who is logged in, a server that points to a mock, and waiting. */
final class GroupListTestSupport {

    static final Duration REFRESH_TIMEOUT = Duration.ofSeconds(60);

    private GroupListTestSupport() {
    }

    /** Empties the tables and creates the account. */
    static TestBrowser loggedInOwner(MockMvcTester mvc, DSLContext database) {
        return TestInstallation.loggedInOwner(mvc, database);
    }

    /**
     * Adds a server without TLS and login that points to the mock server.
     *
     * @return the ID of the server
     */
    static long addServer(TestBrowser browser, MockNntpServer mockServer) {
        return TestInstallation.addServer(browser, mockServer, 4);
    }

    static JsonNode body(MvcTestResult result) {
        return TestInstallation.body(result);
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
