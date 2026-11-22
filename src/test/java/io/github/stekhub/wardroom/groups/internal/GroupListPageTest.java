package io.github.stekhub.wardroom.groups.internal;

import static io.github.stekhub.wardroom.groups.internal.GroupListTestSupport.body;
import static io.github.stekhub.wardroom.jooq.Tables.NEWSGROUP;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.IntegrationTest;
import io.github.stekhub.wardroom.TestBrowser;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import io.github.stekhub.wardroom.nntp.mock.MockReply;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

/** Browsing and searching the group list (spec 003), against the whole application and a real SQLite database. */
@IntegrationTest
class GroupListPageTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DSLContext database;

    TestBrowser browser;
    MockNntpServer mockServer;
    long serverId;

    @BeforeEach
    void startWithServer() throws IOException {
        browser = GroupListTestSupport.loggedInOwner(mvc, database);
        mockServer = MockNntpServer.start();
        mockServer.on("CAPABILITIES").reply(MockReply.fixture("capabilities-reader.txt"));
        serverId = GroupListTestSupport.addServer(browser, mockServer);
    }

    @AfterEach
    void removeServer() throws IOException {
        assertThat(browser.delete("/api/servers/" + serverId)).hasStatus(HttpStatus.NO_CONTENT);
        mockServer.close();
    }

    @Test
    void pageShowsTheGroupsByNameWithTheirFields() {
        store("comp.lang.java.programmer", "Programming in the Java language.", "ALLOWED");
        store("alt.test", null, "NOT_ALLOWED");
        store("comp.lang.java.announce", "Announcements", "MODERATED");

        MvcTestResult page = browser.get("/api/servers/" + serverId + "/groups");

        assertThat(page).hasStatusOk().bodyJson().satisfies(body -> {
            assertThat(body).extractingPath("$.page").isEqualTo(0);
            assertThat(body).extractingPath("$.size").isEqualTo(50);
            assertThat(body).extractingPath("$.total").isEqualTo(3);
            assertThat(body).extractingPath("$.items[*].name").asArray()
                    .containsExactly("alt.test", "comp.lang.java.announce", "comp.lang.java.programmer");
            assertThat(body).extractingPath("$.items[0].description").isNull();
            assertThat(body).extractingPath("$.items[0].postingStatus").isEqualTo("NOT_ALLOWED");
            assertThat(body).extractingPath("$.items[0].subscribed").isEqualTo(false);
            assertThat(body).extractingPath("$.items[1].postingStatus").isEqualTo("MODERATED");
            assertThat(body).extractingPath("$.items[2].description").isEqualTo("Programming in the Java language.");
            assertThat(body).extractingPath("$.items[2].postingStatus").isEqualTo("ALLOWED");
        });
        int storedId = database.select(NEWSGROUP.ID).from(NEWSGROUP).where(NEWSGROUP.NAME.eq("alt.test"))
                .fetchSingle().value1();
        assertThat(body(page).get("items").get(0).get("id").asInt()).isEqualTo(storedId);
    }

    @Test
    void emptyListIsAnEmptyPage() {
        assertThat(browser.get("/api/servers/" + serverId + "/groups")).hasStatusOk().bodyJson()
                .isStrictlyEqualTo("{\"items\":[],\"page\":0,\"size\":50,\"total\":0,\"activityKnown\":false}");
    }

    @Test
    void listIsPagedAndReportsTheTotal() {
        for (int index = 0; index < 120; index++) {
            store("paged.group-%03d".formatted(index), null, "ALLOWED");
        }

        assertThat(names(search("", "")))
                .hasSize(50).startsWith("paged.group-000").endsWith("paged.group-049");
        JsonNode secondPage = search("", "&page=1&size=50");
        assertThat(names(secondPage)).hasSize(50).startsWith("paged.group-050");
        assertThat(secondPage.get("total").asInt()).isEqualTo(120);
        assertThat(secondPage.get("page").asInt()).isEqualTo(1);
        assertThat(names(search("", "&page=2"))).hasSize(20).endsWith("paged.group-119");
        assertThat(names(search("", "&page=3"))).isEmpty();
        assertThat(names(search("", "&size=100"))).hasSize(100);
        assertThat(names(search("", "&size=1&page=119"))).containsExactly("paged.group-119");
        assertThat(search("group-11", "&size=5").get("total").asInt())
                .as("the total counts the matches, not the page").isEqualTo(10);
    }

    @Test
    void removedGroupsAreNotListed() {
        store("example.here", null, "ALLOWED");
        store("example.gone", "No longer on the server", "ALLOWED");
        database.update(NEWSGROUP).set(NEWSGROUP.REMOVED_AT, 1_795_000_000_000L)
                .where(NEWSGROUP.NAME.eq("example.gone")).execute();

        assertThat(names(search("", ""))).containsExactly("example.here");
        assertThat(search("gone", "").get("total").asInt()).isZero();
    }

    // Activity

    @Test
    void sortedByActivityTheMostActiveGroupsComeFirstAndGroupsWithoutAValueLast() {
        store("example.quiet", null, "ALLOWED", 0.0);
        store("example.new", null, "ALLOWED", null);
        store("example.busy", "Much to read", "ALLOWED", 240.5);
        store("example.also-new", null, "ALLOWED", null);
        store("example.steady", null, "ALLOWED", 12.0);
        store("example.equal", null, "ALLOWED", 12.0);

        JsonNode page = search("", "&sort=activity");

        assertThat(names(page)).containsExactly("example.busy", "example.equal", "example.steady",
                "example.quiet", "example.also-new", "example.new");
        assertThat(page.get("items").get(0).get("articlesPerDay").asDouble()).isEqualTo(240.5);
        assertThat(page.get("items").get(3).get("articlesPerDay").asDouble()).isZero();
        assertThat(page.get("items").get(4).get("articlesPerDay").isNull()).isTrue();
        assertThat(page.get("activityKnown").asBoolean()).isTrue();
        assertThat(names(search("", "&sort=activity&size=2&page=1")))
                .containsExactly("example.steady", "example.quiet");
        assertThat(names(search("e", "&sort=activity&size=2"))).as("the search keeps the order")
                .containsExactly("example.busy", "example.equal");
        assertThat(names(search("", "&sort=name"))).isEqualTo(names(search("", ""))).startsWith("example.also-new");
    }

    @Test
    void activityIsKnownOnceOneListedGroupHasAValue() {
        store("example.new", null, "ALLOWED", null);
        store("example.gone", null, "ALLOWED", 3.0);
        database.update(NEWSGROUP).set(NEWSGROUP.REMOVED_AT, 1_795_000_000_000L)
                .where(NEWSGROUP.NAME.eq("example.gone")).execute();

        assertThat(search("", "").get("activityKnown").asBoolean()).isFalse();

        store("example.measured", null, "ALLOWED", 3.0);

        assertThat(search("", "").get("activityKnown").asBoolean()).isTrue();
        assertThat(search("new", "").get("activityKnown").asBoolean())
                .as("it is about the server, not about the page").isTrue();
    }

    // Search

    @Test
    void everyWordOfTheQueryMustAppearInTheNameOrTheDescription() {
        store("comp.lang.java.programmer", "Programming in the Java language.", "ALLOWED");
        store("comp.lang.python", "The Python language.", "ALLOWED");
        store("rec.food.coffee", "Java and other beans.", "ALLOWED");
        store("alt.test", null, "ALLOWED");

        assertThat(names(search("java", ""))).containsExactly("comp.lang.java.programmer", "rec.food.coffee");
        assertThat(names(search("java language", ""))).containsExactly("comp.lang.java.programmer");
        assertThat(names(search("  language   comp ", "")))
                .containsExactly("comp.lang.java.programmer", "comp.lang.python");
        assertThat(names(search("beans food", ""))).as("one word in the description, one in the name")
                .containsExactly("rec.food.coffee");
        assertThat(names(search("ytho", ""))).as("a part of a word").containsExactly("comp.lang.python");
        assertThat(names(search("java cobol", ""))).isEmpty();
        assertThat(names(search("   ", ""))).as("no word: all groups").hasSize(4);
    }

    @Test
    void searchIgnoresCaseForAsciiLettersOnly() {
        store("de.comp.lang.java", "Die Programmiersprache Java, für Anfänger", "ALLOWED");

        assertThat(names(search("JAVA", ""))).containsExactly("de.comp.lang.java");
        assertThat(names(search("programmierSPRACHE De.Comp", ""))).containsExactly("de.comp.lang.java");
        assertThat(names(search("anfänger", ""))).containsExactly("de.comp.lang.java");
        assertThat(names(search("ANFÄNGER", ""))).as("other letters must match exactly").isEmpty();
    }

    @Test
    void percentAndUnderscoreAndBackslashInTheQueryAreMatchedLiterally() {
        store("example.percent", "100% pure", "ALLOWED");
        store("example.under_score", "With an underscore", "ALLOWED");
        store("example.underXscore", "Without one", "ALLOWED");
        store("example.backslash", "A \\ in the text", "ALLOWED");

        assertThat(names(search("%", ""))).containsExactly("example.percent");
        assertThat(names(search("100%", ""))).containsExactly("example.percent");
        assertThat(names(search("under_score", ""))).containsExactly("example.under_score");
        assertThat(names(search("_", ""))).containsExactly("example.under_score");
        assertThat(names(search("\\", ""))).containsExactly("example.backslash");
        assertThat(names(search("'; DROP TABLE newsgroup; --", ""))).isEmpty();
        assertThat(database.fetchCount(NEWSGROUP)).isEqualTo(4);
    }

    // Parameters

    @Test
    void parametersOutsideTheirRangeAreAnsweredWithFieldAndRule() {
        assertInvalid("&page=-1", "page", "The page must be 0 or higher.");
        assertInvalid("&size=0", "size", "The page size must be between 1 and 100.");
        assertInvalid("&size=101", "size", "The page size must be between 1 and 100.");
        assertInvalid("&sort=size", "sort", "The order must be name or activity.");
        assertInvalid("&sort=ACTIVITY", "sort", "The order must be name or activity.");
        assertInvalid("&q=" + "a".repeat(201), "q", "The search text must not be longer than 200 characters.");
        assertThat(browser.get("/api/servers/" + serverId + "/groups?page=first")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(browser.get("/api/servers/" + serverId + "/groups?q=" + "a".repeat(200))).hasStatusOk();
    }

    @Test
    void unknownServerIsNotFound() {
        assertThat(browser.get("/api/servers/" + (serverId + 1) + "/groups")).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.detail").isEqualTo("This server does not exist.");
    }

    private void assertInvalid(String parameters, String field, String message) {
        assertThat(browser.get("/api/servers/" + serverId + "/groups?" + parameters.substring(1)))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().satisfies(body -> {
                    assertThat(body).extractingPath("$.errors[0].field").isEqualTo(field);
                    assertThat(body).extractingPath("$.errors[0].message").isEqualTo(message);
                });
    }

    /** @param furtherParameters for example {@code "&page=1&size=50"} */
    private JsonNode search(String query, String furtherParameters) {
        Map<String, String> queryParameters = new LinkedHashMap<>();
        queryParameters.put("q", query);
        for (String parameter : furtherParameters.split("&")) {
            if (!parameter.isEmpty()) {
                queryParameters.put(parameter.substring(0, parameter.indexOf('=')),
                        parameter.substring(parameter.indexOf('=') + 1));
            }
        }
        MvcTestResult page = browser.get("/api/servers/" + serverId + "/groups", queryParameters);
        assertThat(page).hasStatusOk();
        return body(page);
    }

    private static List<String> names(JsonNode page) {
        List<String> names = new ArrayList<>();
        page.get("items").forEach(item -> names.add(item.get("name").asString()));
        return names;
    }

    private void store(String name, String description, String postingStatus) {
        store(name, description, postingStatus, null);
    }

    private void store(String name, String description, String postingStatus, Double articlesPerDay) {
        database.insertInto(NEWSGROUP)
                .set(NEWSGROUP.SERVER_ID, (int) serverId)
                .set(NEWSGROUP.NAME, name)
                .set(NEWSGROUP.DESCRIPTION, description)
                .set(NEWSGROUP.POSTING_STATUS, postingStatus)
                .set(NEWSGROUP.LOW_WATERMARK, 1L)
                .set(NEWSGROUP.HIGH_WATERMARK, 10L)
                .set(NEWSGROUP.ARTICLES_PER_DAY, articlesPerDay)
                .execute();
    }
}
