package io.github.stekhub.wardroom;

import static io.github.stekhub.wardroom.jooq.Tables.APP_SETTING;
import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE;
import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE_LOCATION;
import static io.github.stekhub.wardroom.jooq.Tables.IGNORE_RULE;
import static io.github.stekhub.wardroom.jooq.Tables.NEWSGROUP;
import static io.github.stekhub.wardroom.jooq.Tables.NNTP_SERVER;
import static io.github.stekhub.wardroom.jooq.Tables.READ_STATE;
import static io.github.stekhub.wardroom.jooq.Tables.SPRING_SESSION;
import static io.github.stekhub.wardroom.jooq.Tables.SUBSCRIPTION;
import static io.github.stekhub.wardroom.jooq.Tables.SYNCED_RANGE;
import static io.github.stekhub.wardroom.jooq.Tables.WARDROOM_USER;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import java.io.UnsupportedEncodingException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What integration tests of several features share: an installation without data, an owner who is logged in, and a
 * server that points to a mock.
 */
public final class TestInstallation {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TestInstallation() {
    }

    /** Empties the tables, which puts the settings back to their defaults, and creates the account. */
    public static TestBrowser loggedInOwner(MockMvcTester mvc, DSLContext database) {
        database.deleteFrom(IGNORE_RULE).execute();
        database.deleteFrom(READ_STATE).execute();
        database.deleteFrom(ARTICLE_LOCATION).execute();
        database.deleteFrom(ARTICLE).execute();
        database.deleteFrom(SYNCED_RANGE).execute();
        database.deleteFrom(SUBSCRIPTION).execute();
        database.deleteFrom(NEWSGROUP).execute();
        database.deleteFrom(NNTP_SERVER).execute();
        database.deleteFrom(APP_SETTING).execute();
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
    public static long addServer(TestBrowser browser, MockNntpServer mockServer, int maxConnections) {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("name", "Mock server");
        server.put("host", "localhost");
        server.put("port", mockServer.port());
        server.put("tlsMode", "NONE");
        server.put("authMethod", "NONE");
        server.put("maxConnections", maxConnections);
        MvcTestResult created = browser.post("/api/servers", JSON.writeValueAsString(server));
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return body(created).get("server").get("id").asLong();
    }

    public static JsonNode body(MvcTestResult result) {
        try {
            return JSON.readTree(result.getResponse().getContentAsString());
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
