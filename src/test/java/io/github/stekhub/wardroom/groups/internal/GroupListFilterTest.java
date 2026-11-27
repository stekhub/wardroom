package io.github.stekhub.wardroom.groups.internal;

import static io.github.stekhub.wardroom.groups.internal.GroupListTestSupport.body;
import static io.github.stekhub.wardroom.jooq.Tables.NEWSGROUP;
import static io.github.stekhub.wardroom.jooq.Tables.SUBSCRIPTION;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.IntegrationTest;
import io.github.stekhub.wardroom.TestBrowser;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import io.github.stekhub.wardroom.nntp.mock.MockReply;
import java.io.IOException;
import java.util.ArrayList;
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

/**
 * The group list without binary groups and without ignored groups (spec 022), against the whole application and a
 * real SQLite database.
 */
@IntegrationTest
class GroupListFilterTest {

    private static final String SETTINGS = "{\"syncIntervalMinutes\": 15, \"initialDepth\": 500,"
            + " \"groupListIntervalHours\": 24, \"cacheLimitEnabled\": true, \"maxCacheSizeGb\": 5,"
            + " \"hideBinaryGroups\": %s}";

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

    // Binary groups by name

    @Test
    void binaryGroupsAreFoundByTheirNames() {
        List<String> binaryGroups = List.of("alt.binaries.x", "x.binary", "binaries", "alt.bin.x", "x.mp3.y",
                "a.b.x", "de.alt.binaries.bilder", "alt.warez", "ALT.BINARIES.UPPER", "alt.binariespw",
                "alt.binariespw-required", "dk.binaer.film", "x.binaere.y", "es.binarios.hd", "it.binari.x",
                "fr.binaires.x", "alt.multimedia", "alt.multimedia.movies", "3b.test", "alt.asian-movies",
                "ab.alt.movies", "4u.alt.movies");
        List<String> discussionGroups = List.of("alt.notbinaries", "comp.lang.binary-trees", "ab.x", "alt.a.b.x",
                "comp.lang.java", "alt.multimediax", "x.3b.y", "rec.arts.movies.current-films", "alt.movies",
                "x.binaerx");
        binaryGroups.forEach(name -> store(name, null));
        discussionGroups.forEach(name -> store(name, null));

        assertThat(database.select(NEWSGROUP.NAME).from(NEWSGROUP)
                .where(BinaryGroups.isBinary(NEWSGROUP.NAME))
                .fetch(NEWSGROUP.NAME))
                .containsExactlyInAnyOrderElementsOf(binaryGroups);
    }

    @Test
    void aGroupOnTheBuiltInListIsABinaryGroup() {
        store("example.files", null);
        store("example.talk", null);

        assertThat(database.select(NEWSGROUP.NAME).from(NEWSGROUP)
                .where(BinaryGroups.isBinary(NEWSGROUP.NAME, List.of("Example.Files")))
                .fetch(NEWSGROUP.NAME))
                .containsExactly("example.files");
    }

    // The setting

    @Test
    void binaryGroupsAreHiddenInTheListTheSearchAndTheOrderByActivityUnlessTheSettingIsOff() {
        store("alt.binaries.linux", 900.0);
        store("comp.os.linux", 20.0);
        store("comp.lang.java", 5.0);

        assertThat(names(page(Map.of()))).containsExactly("comp.lang.java", "comp.os.linux");
        assertThat(page(Map.of()).get("total").asInt()).isEqualTo(2);
        assertThat(names(page(Map.of("q", "linux")))).containsExactly("comp.os.linux");
        assertThat(page(Map.of("q", "linux")).get("total").asInt()).isEqualTo(1);
        assertThat(names(page(Map.of("sort", "activity")))).containsExactly("comp.os.linux", "comp.lang.java");

        hideBinaryGroups(false);

        assertThat(names(page(Map.of()))).containsExactly("alt.binaries.linux", "comp.lang.java", "comp.os.linux");
        assertThat(page(Map.of()).get("total").asInt()).isEqualTo(3);
        assertThat(names(page(Map.of("q", "linux")))).containsExactly("alt.binaries.linux", "comp.os.linux");
        assertThat(names(page(Map.of("sort", "activity"))))
                .containsExactly("alt.binaries.linux", "comp.os.linux", "comp.lang.java");

        hideBinaryGroups(true);

        assertThat(names(page(Map.of()))).containsExactly("comp.lang.java", "comp.os.linux");
    }

    @Test
    void theActivityIsKnownAlsoWhenOnlyAHiddenGroupHasIt() {
        store("alt.binaries.linux", 900.0);
        store("comp.os.linux", null);

        assertThat(page(Map.of()).get("activityKnown").asBoolean()).isTrue();
    }

    @Test
    void aSubscribedBinaryGroupStaysInTheSubscriptions() {
        int groupId = store("alt.binaries.linux", null);
        database.insertInto(SUBSCRIPTION).set(SUBSCRIPTION.NEWSGROUP_ID, groupId).execute();

        MvcTestResult subscriptions = browser.get("/api/subscriptions");

        assertThat(subscriptions).hasStatusOk();
        assertThat(body(subscriptions).get(0).get("name").asString()).isEqualTo("alt.binaries.linux");
        assertThat(names(page(Map.of()))).isEmpty();
    }

    // Ignored groups

    @Test
    void anIgnoredGroupIsHiddenWhateverTheSettingAndComesBackWhenTheRuleIsRemoved() {
        int noiseId = store("example.noise", 50.0);
        store("example.talk", 10.0);

        MvcTestResult rule = browser.post("/api/ignore-rules", "{\"kind\":\"GROUP\",\"groupId\":" + noiseId + "}");

        assertThat(rule).hasStatus(HttpStatus.CREATED);
        assertThat(names(page(Map.of()))).containsExactly("example.talk");
        assertThat(page(Map.of()).get("total").asInt()).isEqualTo(1);
        assertThat(names(page(Map.of("q", "noise")))).isEmpty();
        assertThat(names(page(Map.of("sort", "activity")))).containsExactly("example.talk");
        hideBinaryGroups(false);
        assertThat(names(page(Map.of()))).containsExactly("example.talk");

        assertThat(browser.delete("/api/ignore-rules/" + body(rule).get("id").asInt()))
                .hasStatus(HttpStatus.NO_CONTENT);

        assertThat(names(page(Map.of()))).containsExactly("example.noise", "example.talk");
    }

    @Test
    void aPageBeyondTheLastOneIsEmptyAndStillSaysHowManyGroupsThereAre() {
        store("example.one", null);
        store("example.two", null);

        JsonNode page = page(Map.of("page", "7", "size", "1"));

        assertThat(names(page)).isEmpty();
        assertThat(page.get("total").asInt()).isEqualTo(2);
    }

    private void hideBinaryGroups(boolean hide) {
        assertThat(browser.put("/api/settings", SETTINGS.formatted(hide))).hasStatusOk();
    }

    private JsonNode page(Map<String, String> parameters) {
        MvcTestResult page = browser.get("/api/servers/" + serverId + "/groups", parameters);
        assertThat(page).hasStatusOk();
        return body(page);
    }

    private static List<String> names(JsonNode page) {
        List<String> names = new ArrayList<>();
        page.get("items").forEach(item -> names.add(item.get("name").asString()));
        return names;
    }

    private int store(String name, Double articlesPerDay) {
        return database.insertInto(NEWSGROUP)
                .set(NEWSGROUP.SERVER_ID, (int) serverId)
                .set(NEWSGROUP.NAME, name)
                .set(NEWSGROUP.POSTING_STATUS, "ALLOWED")
                .set(NEWSGROUP.LOW_WATERMARK, 1L)
                .set(NEWSGROUP.HIGH_WATERMARK, 10L)
                .set(NEWSGROUP.ARTICLES_PER_DAY, articlesPerDay)
                .returning(NEWSGROUP.ID)
                .fetchSingle().getId();
    }
}
