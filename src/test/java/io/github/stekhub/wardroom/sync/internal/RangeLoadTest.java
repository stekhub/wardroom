package io.github.stekhub.wardroom.sync.internal;

import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE;
import static io.github.stekhub.wardroom.jooq.Tables.NNTP_SERVER;
import static io.github.stekhub.wardroom.jooq.Tables.READ_STATE;
import static io.github.stekhub.wardroom.jooq.Tables.SYNCED_RANGE;
import static io.github.stekhub.wardroom.sync.internal.SynchronizationTestSupport.TIMEOUT;
import static io.github.stekhub.wardroom.sync.internal.SynchronizationTestSupport.datedOverview;
import static io.github.stekhub.wardroom.sync.internal.SynchronizationTestSupport.overview;
import static io.github.stekhub.wardroom.sync.internal.SynchronizationTestSupport.overviews;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.stekhub.wardroom.IntegrationTest;
import io.github.stekhub.wardroom.TestBrowser;
import io.github.stekhub.wardroom.TestInstallation;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import io.github.stekhub.wardroom.nntp.mock.MockReply;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

/**
 * "Load older", "load more" and jump to date of spec 005, against the whole application, a real SQLite database and
 * the mock NNTP server.
 *
 * <p>The group of these tests has the article numbers 1 to 1,000. With a depth of 50, its first synchronization
 * stores the range 951 to 1,000. The article with the number n is from day n.
 */
@IntegrationTest
@Import(SynchronizationTestSupport.ShortTimeouts.class)
class RangeLoadTest {

    private static final long DAY_MILLIS = Duration.ofDays(1).toMillis();

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DSLContext database;

    @Autowired
    SynchronizationSchedule synchronizationSchedule;

    TestBrowser browser;
    MockNntpServer mockServer;
    long serverId;
    SynchronizationTestSupport support;

    @BeforeEach
    void startWithServer() throws IOException {
        browser = TestInstallation.loggedInOwner(mvc, database);
        mockServer = SynchronizationTestSupport.startMockServer();
        serverId = TestInstallation.addServer(browser, mockServer, 3);
        support = new SynchronizationTestSupport(browser, database, mockServer, serverId);
        support.setInitialDepth(50);
        mockServer.on("GROUP example.talk").reply("211 1000 1 1000 example.talk");
    }

    @AfterEach
    void removeServer() throws IOException {
        await().atMost(TIMEOUT).until(() -> !synchronizationSchedule.isRunInProgress());
        support.awaitNoSynchronization();
        assertThat(browser.delete("/api/servers/" + serverId)).hasStatus(HttpStatus.NO_CONTENT);
        mockServer.close();
    }

    // Load older

    @Test
    void loadOlderExtendsTheRangeDownwardsByTheDepthAndItsArticlesArriveRead() {
        int groupId = synchronizedGroup();
        mockServer.on("OVER 901-950").reply(overviews(
                overview(910, "<old-1@example.org>"),
                overview(950, "<old-2@example.org>")));

        MvcTestResult answer = extend(groupId, newestRangeId(groupId), "OLDER");

        assertThat(answer).hasStatusOk();
        JsonNode range = TestInstallation.body(answer);
        assertThat(range.get("rangeId").asLong()).isEqualTo(newestRangeId(groupId));
        assertThat(range.get("firstNumber").asLong()).isEqualTo(901);
        assertThat(range.get("lastNumber").asLong()).isEqualTo(1000);
        assertThat(range.get("atOldest").asBoolean()).isFalse();
        assertThat(range.get("atNewest").asBoolean()).isTrue();
        assertThat(support.ranges(groupId)).containsExactly("901-1000");
        assertThat(support.locationNumbers(groupId)).containsExactly(910L, 950L, 960L);
        assertThat(readMessageIds()).containsExactly("<old-1@example.org>", "<old-2@example.org>");
        assertThat(support.subscription(groupId).get("unreadCount").asInt()).isEqualTo(1);
    }

    @Test
    void loadOlderStopsAtTheLowWatermark() {
        mockServer.on("GROUP example.talk").reply("211 80 921 1000 example.talk");
        int groupId = synchronizedGroup();

        JsonNode range = TestInstallation.body(extend(groupId, newestRangeId(groupId), "OLDER"));

        assertThat(support.commands("OVER")).containsExactly("OVER 951-1000", "OVER 921-950");
        assertThat(range.get("firstNumber").asLong()).isEqualTo(921);
        assertThat(range.get("atOldest").asBoolean()).isTrue();

        JsonNode sameRange = TestInstallation.body(extend(groupId, newestRangeId(groupId), "OLDER"));

        assertThat(support.commands("OVER")).hasSize(2);
        assertThat(sameRange.get("firstNumber").asLong()).isEqualTo(921);
        assertThat(sameRange.get("atOldest").asBoolean()).isTrue();
    }

    @Test
    void loadOlderStopsAtTheNeighboringRangeAndMergesWithIt() {
        int groupId = synchronizedGroup();
        insertRange(groupId, 880, 930);
        long rangeId = newestRangeId(groupId);

        JsonNode range = TestInstallation.body(extend(groupId, rangeId, "OLDER"));

        assertThat(support.commands("OVER")).containsExactly("OVER 951-1000", "OVER 931-950");
        assertThat(support.ranges(groupId)).containsExactly("880-1000");
        assertThat(range.get("rangeId").asLong()).isEqualTo(rangeId);
        assertThat(range.get("firstNumber").asLong()).isEqualTo(880);
        assertThat(range.get("atOldest").asBoolean()).isFalse();
    }

    @Test
    void articlesThatWereStoredBeforeKeepTheirReadStateAndAFilledStubIsRead() {
        int groupId = synchronizedGroup();
        database.insertInto(ARTICLE).set(ARTICLE.MESSAGE_ID, "<stub@example.org>").set(ARTICLE.IS_STUB, true)
                .execute();
        database.insertInto(ARTICLE).set(ARTICLE.MESSAGE_ID, "<known@example.org>").set(ARTICLE.IS_STUB, false)
                .set(ARTICLE.SUBJECT, "Stored before").execute();
        mockServer.on("OVER 901-950").reply(overviews(
                overview(920, "<stub@example.org>"),
                overview(930, "<known@example.org>"),
                overview(940, "<old@example.org>")));

        assertThat(extend(groupId, newestRangeId(groupId), "OLDER")).hasStatusOk();

        assertThat(readMessageIds()).containsExactly("<old@example.org>", "<stub@example.org>");
        assertThat(database.fetchValue(ARTICLE.IS_STUB, ARTICLE.MESSAGE_ID.eq("<stub@example.org>"))).isFalse();
        assertThat(database.fetchValue(ARTICLE.SUBJECT, ARTICLE.MESSAGE_ID.eq("<known@example.org>")))
                .isEqualTo("Stored before");
        assertThat(support.locationNumbers(groupId)).containsExactly(920L, 930L, 940L, 960L);
        // The article of the first synchronization and the one that was known before.
        assertThat(support.subscription(groupId).get("unreadCount").asInt()).isEqualTo(2);
    }

    // Load more

    @Test
    void loadMoreExtendsARangeUpwardsStopsAtTheNeighboringRangeAndMergesWithIt() {
        int groupId = synchronizedGroup();
        long rangeId = insertRange(groupId, 850, 870);
        mockServer.on("OVER 871-920").reply(overviews(overview(900, "<more@example.org>")));

        JsonNode range = TestInstallation.body(extend(groupId, rangeId, "NEWER"));

        assertThat(range.get("firstNumber").asLong()).isEqualTo(850);
        assertThat(range.get("lastNumber").asLong()).isEqualTo(920);
        assertThat(range.get("atNewest").asBoolean()).isFalse();
        assertThat(support.ranges(groupId)).containsExactly("850-920", "951-1000");
        assertThat(readMessageIds()).containsExactly("<more@example.org>");

        JsonNode mergedRange = TestInstallation.body(extend(groupId, rangeId, "NEWER"));

        assertThat(support.commands("OVER")).containsExactly("OVER 951-1000", "OVER 871-920", "OVER 921-950");
        assertThat(support.ranges(groupId)).containsExactly("850-1000");
        assertThat(mergedRange.get("rangeId").asLong()).isEqualTo(rangeId);
        assertThat(mergedRange.get("lastNumber").asLong()).isEqualTo(1000);
        assertThat(mergedRange.get("atNewest").asBoolean()).isTrue();
    }

    @Test
    void loadMoreLeavesWhatIsAboveTheNewestRangeToTheSynchronization() {
        int groupId = synchronizedGroup();
        mockServer.on("GROUP example.talk").reply("211 1100 1 1100 example.talk");
        mockServer.on("OVER 1001-1100").reply(overviews(overview(1050, "<new@example.org>")));

        JsonNode range = TestInstallation.body(extend(groupId, newestRangeId(groupId), "NEWER"));

        assertThat(support.commands("OVER")).containsExactly("OVER 951-1000");
        assertThat(range.get("lastNumber").asLong()).isEqualTo(1000);
        assertThat(range.get("atNewest").asBoolean()).isTrue();

        support.synchronize(groupId);

        assertThat(support.ranges(groupId)).containsExactly("951-1100");
        assertThat(readMessageIds()).isEmpty();
        assertThat(support.subscription(groupId).get("unreadCount").asInt()).isEqualTo(2);
    }

    @Test
    void theSynchronizationExtendsOnlyTheNewestRange() {
        int groupId = synchronizedGroup();
        insertRange(groupId, 100, 150);
        mockServer.on("GROUP example.talk").reply("211 1100 1 1100 example.talk");

        support.synchronize(groupId);

        assertThat(support.ranges(groupId)).containsExactly("100-150", "951-1100");
    }

    // Jump to date

    @Test
    void aJumpFindsTheStartFetchesFromThereAndStoresNoProbe() {
        int groupId = synchronizedGroup();
        scriptProbes(1, 501, 251, 376, 313, 282);
        mockServer.on("OVER 283-332").reply(overviews(
                datedOverview(283, "<day-283@example.org>", 283 * DAY_MILLIS),
                datedOverview(300, "<day-300@example.org>", 300 * DAY_MILLIS),
                datedOverview(332, "<day-332@example.org>", 332 * DAY_MILLIS)));

        MvcTestResult answer = jump(groupId, 300 * DAY_MILLIS);

        assertThat(answer).hasStatusOk();
        JsonNode jumpResult = TestInstallation.body(answer);
        assertThat(jumpResult.get("startNumber").asLong()).isEqualTo(283);
        assertThat(jumpResult.get("startDate").asLong()).isEqualTo(283 * DAY_MILLIS);
        assertThat(jumpResult.get("clampedToOldest").asBoolean()).isFalse();
        assertThat(jumpResult.get("range").get("firstNumber").asLong()).isEqualTo(283);
        assertThat(jumpResult.get("range").get("lastNumber").asLong()).isEqualTo(332);
        assertThat(jumpResult.get("range").get("atOldest").asBoolean()).isFalse();
        assertThat(jumpResult.get("range").get("atNewest").asBoolean()).isFalse();
        assertThat(support.commands("OVER")).containsExactly("OVER 951-1000", "OVER 1-20", "OVER 501-520",
                "OVER 251-270", "OVER 376-395", "OVER 313-332", "OVER 282-301", "OVER 283-332");
        assertThat(support.ranges(groupId)).containsExactly("283-332", "951-1000");
        assertThat(support.locationNumbers(groupId)).containsExactly(283L, 300L, 332L, 960L);
        assertThat(readMessageIds()).containsExactly("<day-283@example.org>", "<day-300@example.org>",
                "<day-332@example.org>");
        assertThat(support.subscription(groupId).get("unreadCount").asInt()).isEqualTo(1);
    }

    @Test
    void aJumpBeforeTheOldestArticleStartsAtThatArticleAndSaysSo() {
        int groupId = synchronizedGroup();
        scriptProbes(1);
        mockServer.on("OVER 1-50").reply(overviews(datedOverview(1, "<day-1@example.org>", DAY_MILLIS)));

        JsonNode jumpResult = TestInstallation.body(jump(groupId, -5 * DAY_MILLIS));

        assertThat(jumpResult.get("startNumber").asLong()).isEqualTo(1);
        assertThat(jumpResult.get("startDate").asLong()).isEqualTo(DAY_MILLIS);
        assertThat(jumpResult.get("clampedToOldest").asBoolean()).isTrue();
        assertThat(jumpResult.get("range").get("atOldest").asBoolean()).isTrue();
        assertThat(support.ranges(groupId)).containsExactly("1-50", "951-1000");
    }

    @Test
    void aJumpWhoseStartLiesInARangeFetchesNothing() {
        int groupId = synchronizedGroup();
        scriptProbes(1, 501, 751, 876, 938, 969);

        JsonNode jumpResult = TestInstallation.body(jump(groupId, 5_000 * DAY_MILLIS));

        assertThat(support.commands("OVER")).containsExactly("OVER 951-1000", "OVER 1-20", "OVER 501-520",
                "OVER 751-770", "OVER 876-895", "OVER 938-957", "OVER 969-988");
        assertThat(jumpResult.get("startNumber").asLong()).isEqualTo(970);
        assertThat(jumpResult.get("clampedToOldest").asBoolean()).isFalse();
        assertThat(jumpResult.get("range").get("rangeId").asLong()).isEqualTo(newestRangeId(groupId));
        assertThat(jumpResult.get("range").get("atNewest").asBoolean()).isTrue();
        assertThat(support.ranges(groupId)).containsExactly("951-1000");
        assertThat(support.locationNumbers(groupId)).containsExactly(960L);
    }

    @Test
    void aJumpNextToARangeMergesWithIt() {
        int groupId = synchronizedGroup();
        insertRange(groupId, 300, 340);
        scriptProbes(1, 501, 251, 376, 313, 282);

        JsonNode jumpResult = TestInstallation.body(jump(groupId, 300 * DAY_MILLIS));

        assertThat(support.commands("OVER")).endsWith("OVER 283-299");
        assertThat(support.ranges(groupId)).containsExactly("283-340", "951-1000");
        assertThat(jumpResult.get("range").get("firstNumber").asLong()).isEqualTo(283);
        assertThat(jumpResult.get("range").get("lastNumber").asLong()).isEqualTo(340);
    }

    @Test
    void aJumpPassesOverEmptyWindowsAndOverviewsWithoutADate() {
        int groupId = synchronizedGroup();
        scriptProbes(1, 251, 376, 313, 282);
        // The window at the first midpoint is empty, and the next one starts with an article without a date.
        mockServer.on("OVER 521-540").reply(overviews(
                "525\tSubject\tAda <ada@example.org>\tnot a date\t<undated@example.org>\t\t900\t12",
                datedOverview(530, "<probe-530@example.org>", 530 * DAY_MILLIS)));

        JsonNode jumpResult = TestInstallation.body(jump(groupId, 300 * DAY_MILLIS));

        assertThat(support.commands("OVER")).contains("OVER 501-520", "OVER 521-540");
        assertThat(jumpResult.get("startNumber").asLong()).isEqualTo(283);
    }

    @Test
    void aJumpInAGroupWhoseDatesCannotBeReadFailsAndStoresNothing() {
        int groupId = synchronizedGroup();
        scriptProbes(1);

        MvcTestResult answer = jump(groupId, 300 * DAY_MILLIS);

        assertThat(answer).hasStatus(HttpStatus.BAD_GATEWAY)
                .bodyJson().extractingPath("$.detail")
                .isEqualTo("The dates of the articles in this group could not be read.");
        assertThat(support.commands("OVER")).endsWith("OVER 501-520", "OVER 521-540", "OVER 541-560",
                "OVER 561-580", "OVER 581-600");
        assertThat(support.ranges(groupId)).containsExactly("951-1000");
        assertThat(support.locationNumbers(groupId)).containsExactly(960L);
    }

    // Failures

    @Test
    void aStepThatFailsOnTheServerIsABadGatewayAndStoresNothing() {
        int groupId = synchronizedGroup();
        mockServer.on("OVER 901-950").reply(MockReply.cutOff("224 Overview information follows",
                overview(910, "<old@example.org>")));

        MvcTestResult answer = extend(groupId, newestRangeId(groupId), "OLDER");

        assertThat(answer).hasStatus(HttpStatus.BAD_GATEWAY);
        assertThat(TestInstallation.body(answer).get("detail").asString()).isNotBlank();
        assertThat(support.commands("OVER 901-950")).hasSize(1);
        assertThat(support.ranges(groupId)).containsExactly("951-1000");
        assertThat(support.locationNumbers(groupId)).containsExactly(960L);
        assertThat(support.subscription(groupId).get("sync").get("error").isNull()).isTrue();
    }

    @Test
    void aGroupTheServerDoesNotHaveIsABadGateway() {
        int groupId = synchronizedGroup();
        mockServer.on("GROUP example.talk").reply("411 No such newsgroup");

        assertThat(jump(groupId, 300 * DAY_MILLIS)).hasStatus(HttpStatus.BAD_GATEWAY)
                .bodyJson().extractingPath("$.detail").isEqualTo("The group is not available on the server.");
    }

    @Test
    void anUnknownGroupOrRangeIsNotFoundAndAGroupThatIsNotSubscribedIsAConflict() {
        int groupId = synchronizedGroup();
        long rangeId = newestRangeId(groupId);
        int otherGroupId = support.subscribe("example.other");
        long otherRangeId = insertRange(otherGroupId, 1, 10);

        assertThat(jump(groupId + 100, 0)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(extend(groupId + 100, rangeId, "OLDER")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(extend(groupId, otherRangeId, "OLDER")).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.detail").isEqualTo("This group has no such range of articles.");
        assertThat(browser.delete("/api/groups/" + groupId + "/subscription")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(jump(groupId, 0)).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.detail").isEqualTo("This group is not subscribed.");
        assertThat(extend(groupId, rangeId, "OLDER")).hasStatus(HttpStatus.CONFLICT);
        assertThat(support.commands("OVER")).containsExactly("OVER 951-1000");
    }

    @Test
    void aRequestWithoutATimeOrWithAnotherDirectionIsInvalid() {
        int groupId = synchronizedGroup();
        long rangeId = newestRangeId(groupId);

        assertThat(browser.post("/api/groups/" + groupId + "/jump", "{}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(extend(groupId, rangeId, "SIDEWAYS")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(browser.post("/api/groups/" + groupId + "/ranges/" + rangeId + "/extend", "{}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(support.commands("OVER")).containsExactly("OVER 951-1000");
    }

    @Test
    void theEndpointsNeedASessionAndTheCsrfToken() {
        int groupId = synchronizedGroup();
        String jumpPath = "/api/groups/" + groupId + "/jump";

        assertThat(new TestBrowser(mvc).post(jumpPath, "{\"from\": 0}")).hasStatus(HttpStatus.UNAUTHORIZED);
        TestBrowser secondBrowser = new TestBrowser(mvc);
        assertThat(secondBrowser.post("/api/auth/login",
                TestBrowser.json("username", "owner", "password", "correct horse battery")))
                .hasStatus(HttpStatus.NO_CONTENT);
        TestBrowser browserWithoutToken = secondBrowser.withoutCsrfToken();
        assertThat(browserWithoutToken.post(jumpPath, "{\"from\": 0}")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(browserWithoutToken.post("/api/groups/" + groupId + "/ranges/1/extend",
                "{\"direction\": \"OLDER\"}")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(support.commands("OVER")).containsExactly("OVER 951-1000");
    }

    @Test
    void withInvalidCredentialsNothingIsLoaded() {
        int groupId = synchronizedGroup();
        database.update(NNTP_SERVER).set(NNTP_SERVER.CREDENTIALS_INVALID, true).execute();

        assertThat(jump(groupId, 0)).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.detail").asString().startsWith("Wardroom cannot log in to the server.");
        assertThat(extend(groupId, newestRangeId(groupId), "OLDER")).hasStatus(HttpStatus.CONFLICT);
        assertThat(support.commands("GROUP")).hasSize(1);
    }

    @Test
    void aGroupThatWasNeverSynchronizedCannotBeJumpedIn() {
        int groupId = support.subscribe("example.talk");

        assertThat(jump(groupId, 300 * DAY_MILLIS)).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.detail")
                .isEqualTo("This group has not been synchronized yet. Refresh it first.");
        assertThat(support.commands("OVER")).isEmpty();
        assertThat(support.ranges(groupId)).isEmpty();
    }

    @Test
    void aRenumberedGroupIsLeftToTheSynchronization() {
        int groupId = synchronizedGroup();
        mockServer.on("GROUP example.talk").reply("211 40 1 40 example.talk");

        assertThat(extend(groupId, newestRangeId(groupId), "OLDER")).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.detail")
                .isEqualTo("The server has renumbered this group. Refresh it first.");
        assertThat(support.ranges(groupId)).containsExactly("951-1000");
        assertThat(support.locationNumbers(groupId)).containsExactly(960L);
    }

    @Test
    void aStepDoesNotRunTogetherWithASynchronizationOfItsGroup() {
        int groupId = synchronizedGroup();
        mockServer.on("GROUP example.talk").reply("211 1100 1 1100 example.talk");
        mockServer.on("OVER 1001-1100").reply(overviews(overview(1050, "<new@example.org>"))
                .after(Duration.ofMillis(300)));
        assertThat(synchronizationSchedule.startRun()).isTrue();
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(5))
                .until(() -> !support.commands("OVER 1001-1100").isEmpty());

        MvcTestResult answer = extend(groupId, newestRangeId(groupId), "OLDER");

        assertThat(answer).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.detail").asString().startsWith("This group is being synchronized.");
        assertThat(support.commands("OVER 901-950")).isEmpty();
        await().atMost(TIMEOUT).until(() -> !synchronizationSchedule.isRunInProgress());
        assertThat(support.ranges(groupId)).containsExactly("951-1100");

        assertThat(extend(groupId, newestRangeId(groupId), "OLDER")).hasStatusOk();
        assertThat(support.ranges(groupId)).containsExactly("901-1100");
    }

    /** Subscribes to the group and synchronizes it: the range 951 to 1,000 with one unread article. */
    private int synchronizedGroup() {
        int groupId = support.subscribe("example.talk");
        mockServer.on("OVER 951-1000").reply(overviews(overview(960, "<first-sync@example.org>")));
        JsonNode subscription = support.synchronize(groupId);
        assertThat(subscription.get("sync").get("error").isNull()).isTrue();
        assertThat(support.ranges(groupId)).containsExactly("951-1000");
        return groupId;
    }

    /** Lets the window that starts at each number answer with one article, dated by its number. */
    private void scriptProbes(int... windowStarts) {
        for (int windowStart : windowStarts) {
            mockServer.on("OVER " + windowStart + "-" + (windowStart + 19)).reply(overviews(
                    datedOverview(windowStart, "<probe-" + windowStart + "@example.org>", windowStart * DAY_MILLIS)));
        }
    }

    private MvcTestResult jump(int groupId, long from) {
        return browser.post("/api/groups/" + groupId + "/jump", "{\"from\": " + from + "}");
    }

    private MvcTestResult extend(int groupId, long rangeId, String direction) {
        return browser.post("/api/groups/" + groupId + "/ranges/" + rangeId + "/extend",
                "{\"direction\": \"" + direction + "\"}");
    }

    private long newestRangeId(int groupId) {
        return database.select(SYNCED_RANGE.ID).from(SYNCED_RANGE)
                .where(SYNCED_RANGE.NEWSGROUP_ID.eq(groupId))
                .orderBy(SYNCED_RANGE.LAST_NUMBER.desc())
                .limit(1)
                .fetchSingle(SYNCED_RANGE.ID);
    }

    private long insertRange(int groupId, long firstNumber, long lastNumber) {
        return database.insertInto(SYNCED_RANGE)
                .set(SYNCED_RANGE.NEWSGROUP_ID, groupId)
                .set(SYNCED_RANGE.FIRST_NUMBER, firstNumber)
                .set(SYNCED_RANGE.LAST_NUMBER, lastNumber)
                .returning(SYNCED_RANGE.ID)
                .fetchSingle().getId();
    }

    /** @return the Message-IDs of the articles that are read, in order */
    private List<String> readMessageIds() {
        return database.select(ARTICLE.MESSAGE_ID).from(READ_STATE)
                .join(ARTICLE).on(ARTICLE.ID.eq(READ_STATE.ARTICLE_ID))
                .orderBy(ARTICLE.MESSAGE_ID)
                .fetch(ARTICLE.MESSAGE_ID);
    }
}
