package io.github.stekhub.wardroom.sync.internal;

import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE;
import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE_LOCATION;
import static io.github.stekhub.wardroom.jooq.Tables.NEWSGROUP;
import static io.github.stekhub.wardroom.jooq.Tables.NNTP_SERVER;
import static io.github.stekhub.wardroom.jooq.Tables.READ_STATE;
import static io.github.stekhub.wardroom.jooq.Tables.SUBSCRIPTION;
import static io.github.stekhub.wardroom.sync.internal.SynchronizationTestSupport.OVERVIEW_DATE_MILLIS;
import static io.github.stekhub.wardroom.sync.internal.SynchronizationTestSupport.TIMEOUT;
import static io.github.stekhub.wardroom.sync.internal.SynchronizationTestSupport.overview;
import static io.github.stekhub.wardroom.sync.internal.SynchronizationTestSupport.overviews;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.stekhub.wardroom.IntegrationTest;
import io.github.stekhub.wardroom.TestBrowser;
import io.github.stekhub.wardroom.TestInstallation;
import io.github.stekhub.wardroom.jooq.tables.records.ArticleRecord;
import io.github.stekhub.wardroom.jooq.tables.records.NewsgroupRecord;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import io.github.stekhub.wardroom.nntp.mock.MockReply;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/**
 * The synchronization requirements of spec 005 that do not need loading or the cache, against the whole
 * application, a real SQLite database and the mock NNTP server. Nothing synchronizes here unless a test asks for
 * it: with the refresh button, or by starting a run of the schedule.
 */
@IntegrationTest
@Import(SynchronizationTestSupport.ShortTimeouts.class)
@ExtendWith(OutputCaptureExtension.class)
class SynchronizationTest {

    /** The server allows three connections: two for background work and one for interactive actions. */
    private static final int MAX_CONNECTIONS = 3;

    /** Every test that asks the schedule whether a run is due gets times of its own. */
    private static final AtomicLong SCHEDULE_TESTS = new AtomicLong();

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
        serverId = TestInstallation.addServer(browser, mockServer, MAX_CONNECTIONS);
        support = new SynchronizationTestSupport(browser, database, mockServer, serverId);
        support.setInitialDepth(50);
    }

    /** Removing the server also closes its connections and its circuit. */
    @AfterEach
    void removeServer() throws IOException {
        awaitRunEnd();
        support.awaitNoSynchronization();
        assertThat(browser.delete("/api/servers/" + serverId)).hasStatus(HttpStatus.NO_CONTENT);
        mockServer.close();
    }

    // The endpoint

    @Test
    void theEndpointNeedsASessionAndTheCsrfToken() {
        int groupId = support.subscribe("example.talk");

        assertThat(new TestBrowser(mvc).post(syncPath(groupId))).hasStatus(HttpStatus.UNAUTHORIZED);
        TestBrowser secondBrowser = new TestBrowser(mvc);
        assertThat(secondBrowser.post("/api/auth/login",
                TestBrowser.json("username", "owner", "password", "correct horse battery")))
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(secondBrowser.withoutCsrfToken().post(syncPath(groupId))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(support.commands("GROUP")).isEmpty();
    }

    @Test
    void anUnknownGroupIsNotFoundAndAGroupThatIsNotSubscribedIsAConflict() {
        int groupId = support.subscribe("example.talk");
        assertThat(browser.delete("/api/groups/" + groupId + "/subscription")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(browser.post(syncPath(groupId + 1))).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.detail").isEqualTo("This group does not exist.");
        assertThat(browser.post(syncPath(groupId))).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.detail").isEqualTo("This group is not subscribed.");
        assertThat(support.commands("GROUP")).isEmpty();
    }

    @Test
    void withInvalidCredentialsNothingIsSynchronized() {
        int groupId = support.subscribe("example.talk");
        database.update(NNTP_SERVER).set(NNTP_SERVER.CREDENTIALS_INVALID, true).execute();

        assertThat(browser.post(syncPath(groupId))).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.detail").asString().startsWith("Wardroom cannot log in to the server.");
        assertThat(synchronizationSchedule.startRun()).isTrue();
        awaitRunEnd();

        assertThat(support.commands("GROUP")).isEmpty();
        assertThat(support.subscription(groupId).get("sync").get("error").isNull()).isTrue();
    }

    // First synchronization

    @Test
    void theFirstSynchronizationFetchesTheNewestOverviewsUpToTheDepth() {
        int groupId = support.subscribe("example.talk");
        mockServer.on("GROUP example.talk").reply("211 200 1 200 example.talk");
        mockServer.on("OVER 151-200").reply(overviews(
                overview(160, "Hello", "<a@example.org>", ""),
                overview(170, "Re: Hello", "<b@example.org>", "<a@example.org>"),
                overview(200, "=?UTF-8?Q?Gr=C3=BC=C3=9Fe?=", "<c@example.org>", "")));
        long before = System.currentTimeMillis();

        JsonNode subscription = support.synchronize(groupId);

        assertThat(support.commands("OVER")).containsExactly("OVER 151-200");
        assertThat(support.ranges(groupId)).containsExactly("151-200");
        assertThat(support.locationNumbers(groupId)).containsExactly(160L, 170L, 200L);
        assertThat(support.messageIds()).containsExactly("<a@example.org>", "<b@example.org>", "<c@example.org>");
        ArticleRecord reply = database.selectFrom(ARTICLE).where(ARTICLE.MESSAGE_ID.eq("<b@example.org>"))
                .fetchSingle();
        assertThat(reply.getIsStub()).isFalse();
        assertThat(reply.getSubject()).isEqualTo("Re: Hello");
        assertThat(reply.getFrom()).isEqualTo("Ada <ada@example.org>");
        assertThat(reply.getAuthorKey()).isEqualTo("ada@example.org");
        assertThat(reply.getDate()).isEqualTo(OVERVIEW_DATE_MILLIS);
        assertThat(reply.get(ARTICLE.REFERENCES)).isEqualTo("<a@example.org>");
        assertThat(reply.getBytes()).isEqualTo(900L);
        assertThat(reply.getLines()).isEqualTo(12L);
        assertThat(database.selectFrom(ARTICLE).where(ARTICLE.MESSAGE_ID.eq("<c@example.org>")).fetchSingle()
                .getSubject()).isEqualTo("Grüße");
        assertThat(database.selectFrom(ARTICLE).where(ARTICLE.MESSAGE_ID.eq("<a@example.org>")).fetchSingle()
                .get(ARTICLE.REFERENCES)).isNull();
        NewsgroupRecord group = group(groupId);
        assertThat(group.getLowWatermark()).isEqualTo(1);
        assertThat(group.getHighWatermark()).isEqualTo(200);
        assertThat(group.getLastSyncedAt()).isBetween(before, System.currentTimeMillis());
        assertThat(group.getLastSyncError()).isNull();
        assertThat(subscription.get("unreadCount").asInt()).isEqualTo(3);
        assertThat(subscription.get("sync").get("lastSyncedAt").asLong()).isEqualTo(group.getLastSyncedAt());
        assertThat(subscription.get("sync").get("error").isNull()).isTrue();
    }

    @Test
    void aGroupWithFewerArticlesThanTheDepthIsFetchedCompletely() {
        int groupId = support.subscribe("example.talk");
        mockServer.on("GROUP example.talk").reply("211 20 5 24 example.talk");
        mockServer.on("OVER 5-24").reply(overviews(overview(5, "<a@example.org>"), overview(24, "<b@example.org>")));

        support.synchronize(groupId);

        assertThat(support.commands("OVER")).containsExactly("OVER 5-24");
        assertThat(support.ranges(groupId)).containsExactly("5-24");
        assertThat(support.locationNumbers(groupId)).containsExactly(5L, 24L);
    }

    @Test
    void anEmptyGroupIsSynchronizedWithoutAskingForOverviews() {
        int groupId = support.subscribe("example.talk");
        mockServer.on("GROUP example.talk").reply("211 0 1 0 example.talk");

        JsonNode subscription = support.synchronize(groupId);

        assertThat(support.commands("OVER")).isEmpty();
        assertThat(support.ranges(groupId)).isEmpty();
        assertThat(subscription.get("sync").get("lastSyncedAt").isNull()).isFalse();
    }

    // Incremental synchronization

    @Test
    void anIncrementalSynchronizationFetchesOnlyNewNumbersInChunks() {
        int groupId = support.subscribe("example.talk");
        mockServer.on("GROUP example.talk").once(MockReply.lines("211 100 1 100 example.talk"))
                .reply("211 2300 1 2300 example.talk");
        mockServer.on("OVER 51-100").reply(overviews(overview(60, "<a@example.org>")));
        mockServer.on("OVER 101-1100").reply(overviews(overview(500, "<b@example.org>")));
        mockServer.on("OVER 1101-2100").reply(overviews(overview(1500, "<c@example.org>")));
        mockServer.on("OVER 2101-2300").reply(overviews(overview(2300, "<d@example.org>")));
        support.synchronize(groupId);
        assertThat(support.ranges(groupId)).containsExactly("51-100");

        support.synchronize(groupId);

        assertThat(support.commands("OVER"))
                .containsExactly("OVER 51-100", "OVER 101-1100", "OVER 1101-2100", "OVER 2101-2300");
        assertThat(support.ranges(groupId)).containsExactly("51-2300");
        assertThat(support.locationNumbers(groupId)).containsExactly(60L, 500L, 1500L, 2300L);

        // Nothing is new: the next synchronization asks for no overviews.
        support.synchronize(groupId);
        assertThat(support.commands("OVER")).hasSize(4);
    }

    @Test
    void anInterruptedSynchronizationKeepsItsChunksAndTheNextOneContinuesAfterThem() {
        int groupId = support.subscribe("example.talk");
        mockServer.on("GROUP example.talk").once(MockReply.lines("211 100 1 100 example.talk"))
                .reply("211 2300 1 2300 example.talk");
        mockServer.on("OVER 101-1100").reply(overviews(overview(500, "<b@example.org>")));
        // Three attempts fail at the second chunk (ADR 0026); then the server answers again.
        mockServer.on("OVER 1101-2100").once(MockReply.drop()).once(MockReply.drop()).once(MockReply.drop())
                .reply(overviews(overview(1500, "<c@example.org>")));
        long firstSyncedAt = support.synchronize(groupId).get("sync").get("lastSyncedAt").asLong();

        JsonNode interrupted = support.synchronize(groupId);

        assertThat(interrupted.get("sync").get("error").asString()).isEqualTo("The server closed the connection");
        assertThat(interrupted.get("sync").get("lastSyncedAt").asLong()).isEqualTo(firstSyncedAt);
        assertThat(support.ranges(groupId)).containsExactly("51-1100");
        assertThat(support.locationNumbers(groupId)).containsExactly(500L);
        // The attempts after the first one continued after the stored chunk as well.
        assertThat(support.commands("OVER 101-1100")).hasSize(1);
        assertThat(support.commands("OVER 1101-2100")).hasSize(3);

        JsonNode continued = support.synchronize(groupId);

        assertThat(continued.get("sync").get("error").isNull()).isTrue();
        assertThat(continued.get("sync").get("lastSyncedAt").asLong()).isGreaterThanOrEqualTo(firstSyncedAt);
        assertThat(support.commands("OVER 101-1100")).hasSize(1);
        assertThat(support.commands("OVER 1101-2100")).hasSize(4);
        assertThat(support.ranges(groupId)).containsExactly("51-2300");
        assertThat(support.locationNumbers(groupId)).containsExactly(500L, 1500L);
    }

    // Known articles

    @Test
    void aCrosspostedArticleGetsASecondLocation() {
        int talk = support.subscribe("example.talk");
        int misc = support.subscribe("example.misc");
        mockServer.on("GROUP example.talk").reply("211 10 1 10 example.talk");
        mockServer.on("GROUP example.misc").reply("211 30 1 30 example.misc");
        mockServer.on("OVER 1-10").reply(overviews(overview(7, "Crossposted", "<x@example.org>", "")));
        mockServer.on("OVER 1-30").reply(overviews(overview(22, "Crossposted", "<x@example.org>", "")));

        support.synchronize(talk);
        support.synchronize(misc);

        assertThat(support.messageIds()).containsExactly("<x@example.org>");
        assertThat(support.locationNumbers(talk)).containsExactly(7L);
        assertThat(support.locationNumbers(misc)).containsExactly(22L);
        assertThat(support.subscription(talk).get("unreadCount").asInt()).isEqualTo(1);
        assertThat(support.subscription(misc).get("unreadCount").asInt()).isEqualTo(1);
    }

    @Test
    void aStubIsFilledIn() {
        int groupId = support.subscribe("example.talk");
        database.insertInto(ARTICLE).set(ARTICLE.MESSAGE_ID, "<a@example.org>").set(ARTICLE.IS_STUB, true).execute();
        mockServer.on("GROUP example.talk").reply("211 10 1 10 example.talk");
        mockServer.on("OVER 1-10").reply(overviews(overview(3, "Hello", "<a@example.org>", "")));

        support.synchronize(groupId);

        ArticleRecord article = database.selectFrom(ARTICLE).fetchSingle();
        assertThat(article.getIsStub()).isFalse();
        assertThat(article.getSubject()).isEqualTo("Hello");
        assertThat(article.getAuthorKey()).isEqualTo("ada@example.org");
        assertThat(article.getDate()).isEqualTo(OVERVIEW_DATE_MILLIS);
        assertThat(support.locationNumbers(groupId)).containsExactly(3L);
        assertThat(support.subscription(groupId).get("unreadCount").asInt()).isEqualTo(1);
    }

    @Test
    void aKnownArticleUnderAnotherNumberInTheSameGroupKeepsOneLocationWithTheNewNumber() {
        int groupId = support.subscribe("example.talk");
        mockServer.on("GROUP example.talk").once(MockReply.lines("211 100 1 100 example.talk"))
                .reply("211 200 1 200 example.talk");
        mockServer.on("OVER 51-100").reply(overviews(overview(60, "<a@example.org>"),
                overview(70, "<b@example.org>")));
        mockServer.on("OVER 101-200").reply(overviews(overview(150, "<a@example.org>")));
        support.synchronize(groupId);
        int articleId = database.select(ARTICLE.ID).from(ARTICLE).where(ARTICLE.MESSAGE_ID.eq("<a@example.org>"))
                .fetchSingle().value1();
        database.insertInto(READ_STATE).set(READ_STATE.ARTICLE_ID, articleId).set(READ_STATE.READ_AT, 1L).execute();

        support.synchronize(groupId);

        assertThat(support.messageIds()).containsExactly("<a@example.org>", "<b@example.org>");
        assertThat(support.locationNumbers(groupId)).containsExactly(70L, 150L);
        assertThat(database.select(ARTICLE_LOCATION.ARTICLE_NUMBER).from(ARTICLE_LOCATION)
                .where(ARTICLE_LOCATION.ARTICLE_ID.eq(articleId)).fetch(ARTICLE_LOCATION.ARTICLE_NUMBER))
                .containsExactly(150L);
        // The article is the same, so it stays read.
        assertThat(support.subscription(groupId).get("unreadCount").asInt()).isEqualTo(1);
    }

    // Expiry and renumbering

    @Test
    void aRisenLowWatermarkMarksTheLocationsBelowItAsExpired() {
        int groupId = support.subscribe("example.talk");
        mockServer.on("GROUP example.talk").once(MockReply.lines("211 50 51 100 example.talk"))
                .reply("211 21 80 100 example.talk");
        mockServer.on("OVER 51-100").reply(overviews(overview(60, "<a@example.org>"),
                overview(79, "<b@example.org>"), overview(80, "<c@example.org>")));
        support.synchronize(groupId);
        assertThat(database.fetchCount(ARTICLE_LOCATION, ARTICLE_LOCATION.EXPIRED_AT.isNotNull())).isZero();
        long before = System.currentTimeMillis();

        JsonNode subscription = support.synchronize(groupId);

        assertThat(database.select(ARTICLE_LOCATION.ARTICLE_NUMBER).from(ARTICLE_LOCATION)
                .where(ARTICLE_LOCATION.EXPIRED_AT.between(before, System.currentTimeMillis()))
                .orderBy(ARTICLE_LOCATION.ARTICLE_NUMBER).fetch(ARTICLE_LOCATION.ARTICLE_NUMBER))
                .containsExactly(60L, 79L);
        assertThat(group(groupId).getLowWatermark()).isEqualTo(80);
        // Expired articles stay, and still count as unread (spec 004).
        assertThat(support.messageIds()).hasSize(3);
        assertThat(subscription.get("unreadCount").asInt()).isEqualTo(3);
        assertThat(support.commands("OVER")).containsExactly("OVER 51-100");
    }

    @Test
    void renumberingResetsTheGroupAndRunsAFirstSynchronization(CapturedOutput output) {
        int groupId = support.subscribe("example.talk");
        int other = support.subscribe("example.misc");
        mockServer.on("GROUP example.misc").reply("211 10 1 10 example.misc");
        mockServer.on("OVER 1-10").reply(overviews(overview(4, "<kept@example.org>"),
                overview(5, "<b@example.org>")));
        mockServer.on("GROUP example.talk").once(MockReply.lines("211 100 1 100 example.talk"))
                .reply("211 30 1 30 example.talk");
        mockServer.on("OVER 51-100").reply(overviews(overview(60, "<a@example.org>"),
                overview(90, "<b@example.org>")));
        mockServer.on("OVER 1-30").reply(overviews(overview(10, "<b@example.org>"),
                overview(20, "<c@example.org>")));
        support.synchronize(other);
        support.synchronize(groupId);
        assertThat(browser.post("/api/groups/" + groupId + "/mark-all-read")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(database.fetchCount(READ_STATE)).isEqualTo(2);

        JsonNode subscription = support.synchronize(groupId);

        assertThat(support.commands("OVER")).containsExactly("OVER 1-10", "OVER 51-100", "OVER 1-30");
        assertThat(support.ranges(groupId)).containsExactly("1-30");
        assertThat(support.locationNumbers(groupId)).containsExactly(10L, 20L);
        // <a> is gone with its read state. <b> also has a location in the other group, so it was kept, and read.
        assertThat(support.messageIds())
                .containsExactly("<b@example.org>", "<c@example.org>", "<kept@example.org>");
        assertThat(subscription.get("unreadCount").asInt()).isEqualTo(1);
        assertThat(database.fetchCount(SUBSCRIPTION)).isEqualTo(2);
        assertThat(support.locationNumbers(other)).containsExactly(4L, 5L);
        assertThat(subscription.get("sync").get("error").isNull()).isTrue();
        assertThat(output).contains("The server has renumbered the group example.talk");
    }

    // Failures

    @Test
    void aGroupTheServerDoesNotHaveGetsAnErrorAndTheOtherGroupsContinue() {
        int gone = support.subscribe("example.gone");
        int talk = support.subscribe("example.talk");
        mockServer.on("GROUP example.gone").reply("411 No such newsgroup");
        mockServer.on("GROUP example.talk").reply("211 10 1 10 example.talk");
        mockServer.on("OVER 1-10").reply(overviews(overview(3, "<a@example.org>")));

        assertThat(synchronizationSchedule.startRun()).isTrue();
        awaitRunEnd();

        assertThat(support.subscription(gone).get("sync").get("error").asString())
                .isEqualTo("The group is not available on the server.");
        assertThat(support.subscription(gone).get("sync").get("lastSyncedAt").isNull()).isTrue();
        assertThat(support.subscription(talk).get("sync").get("error").isNull()).isTrue();
        assertThat(support.subscription(talk).get("sync").get("lastSyncedAt").isNull()).isFalse();
        assertThat(support.locationNumbers(talk)).containsExactly(3L);
    }

    @Test
    void aServerThatDoesNotAnswerInTimeIsTriedThreeTimesAndThenTheGroupGetsTheError() {
        int groupId = support.subscribe("example.talk");
        mockServer.on("GROUP example.talk").reply("211 10 1 10 example.talk");
        mockServer.on("OVER 1-10").reply(MockReply.silence());

        JsonNode subscription = support.synchronize(groupId);

        assertThat(subscription.get("sync").get("error").asString()).contains("did not answer in time");
        assertThat(subscription.get("sync").get("lastSyncedAt").isNull()).isTrue();
        assertThat(support.commands("OVER 1-10")).hasSize(3);
        assertThat(support.ranges(groupId)).isEmpty();
    }

    @Test
    void aRunSkipsAServerWhoseCircuitIsOpenAndItsGroupsKeepTheirStatus(CapturedOutput output) {
        int failing = support.subscribe("example.failing");
        mockServer.on("GROUP").reply(MockReply.drop());
        // Two refreshes with three attempts each: the circuit opens after the fifth failure (ADR 0026).
        support.synchronize(failing);
        JsonNode afterOpening = support.synchronize(failing);
        assertThat(afterOpening.get("sync").get("error").asString())
                .isEqualTo("The server failed repeatedly and is not contacted for a while");
        int waiting = support.subscribe("example.waiting");
        int commandsBefore = mockServer.commands().size();
        int connectionsBefore = mockServer.connectionCount();

        assertThat(synchronizationSchedule.startRun()).isTrue();
        awaitRunEnd();

        assertThat(mockServer.commands()).hasSize(commandsBefore);
        assertThat(mockServer.connectionCount()).isEqualTo(connectionsBefore);
        assertThat(support.subscription(waiting).get("sync").get("error").isNull()).isTrue();
        assertThat(support.subscription(waiting).get("sync").get("lastSyncedAt").isNull()).isTrue();
        assertThat(support.subscription(failing).get("sync").get("error").asString())
                .isEqualTo("The server failed repeatedly and is not contacted for a while");
        assertThat(output).contains("Synchronization run stops for server " + serverId);
    }

    // Schedule

    @Test
    void aRunUsesAllConnectionsButOneAndAnInteractiveActionGetsTheRemainingOne() {
        List<Integer> groupIds = List.of(support.subscribe("example.a"), support.subscribe("example.b"),
                support.subscribe("example.c"), support.subscribe("example.d"));
        mockServer.on("GROUP").reply("211 10 1 10 group");
        // Every synchronization holds its connection for a while, but not as long as the read timeout.
        mockServer.on("OVER 1-10").reply(overviews(overview(3, "<a@example.org>")).after(Duration.ofMillis(300)));
        int connectionsBefore = mockServer.connectionCount();

        assertThat(synchronizationSchedule.startRun()).isTrue();
        // The last group of the run waits for one of the two background connections.
        int lastGroup = groupIds.get(3);
        assertThat(browser.post(syncPath(lastGroup))).hasStatus(HttpStatus.ACCEPTED);
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(5))
                .until(() -> mockServer.openConnections() == MAX_CONNECTIONS);
        awaitRunEnd();
        support.awaitNoSynchronization();

        // Two connections served the run, however many groups there were; the third served the refresh.
        assertThat(mockServer.connectionCount() - connectionsBefore).isEqualTo(MAX_CONNECTIONS);
        assertThat(support.subscriptions())
                .allSatisfy(subscription -> assertThat(subscription.get("sync").get("lastSyncedAt").isNull())
                        .isFalse());
        assertThat(support.commands("OVER 1-10")).hasSize(4);
    }

    @Test
    void aRunThatIsStillGoingCausesTheNextOneToBeSkipped(CapturedOutput output) {
        int groupId = support.subscribe("example.talk");
        mockServer.on("GROUP example.talk").reply("211 10 1 10 example.talk");
        mockServer.on("OVER 1-10").reply(overviews(overview(3, "<a@example.org>")).after(Duration.ofMillis(300)));
        long start = scheduleTestStart();
        long minute = Duration.ofMinutes(1).toMillis();

        assertThat(synchronizationSchedule.runIfDue(start)).isTrue();
        assertThat(synchronizationSchedule.isRunInProgress()).isTrue();
        // Not due yet, then due while the first run is still going.
        assertThat(synchronizationSchedule.runIfDue(start + 14 * minute)).isFalse();
        assertThat(output).doesNotContain("Synchronization run skipped");
        assertThat(synchronizationSchedule.runIfDue(start + 15 * minute)).isFalse();
        assertThat(output).contains("Synchronization run skipped: the previous run is still going");
        awaitRunEnd();
        assertThat(support.commands("GROUP")).hasSize(1);

        // The skipped run is not made up for; the next one is due an interval later.
        assertThat(synchronizationSchedule.runIfDue(start + 29 * minute)).isFalse();
        assertThat(synchronizationSchedule.runIfDue(start + 30 * minute)).isTrue();
        awaitRunEnd();
        assertThat(support.commands("GROUP")).hasSize(2);
        assertThat(support.subscription(groupId).get("sync").get("error").isNull()).isTrue();
    }

    @Test
    void theScheduleFollowsTheIntervalOfTheSettings() {
        support.subscribe("example.talk");
        mockServer.on("GROUP example.talk").reply("211 0 1 0 example.talk");
        assertThat(browser.put("/api/settings",
                "{\"syncIntervalMinutes\": 5, \"initialDepth\": 50, \"groupListIntervalHours\": 24}")).hasStatusOk();
        long start = scheduleTestStart();
        long minute = Duration.ofMinutes(1).toMillis();

        assertThat(synchronizationSchedule.runIfDue(start)).isTrue();
        awaitRunEnd();
        assertThat(synchronizationSchedule.runIfDue(start + 4 * minute)).isFalse();
        assertThat(synchronizationSchedule.runIfDue(start + 5 * minute)).isTrue();
        awaitRunEnd();

        assertThat(support.commands("GROUP")).hasSize(2);
    }

    private NewsgroupRecord group(int groupId) {
        return database.selectFrom(NEWSGROUP).where(NEWSGROUP.ID.eq(groupId)).fetchSingle();
    }

    private void awaitRunEnd() {
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(20))
                .until(() -> !synchronizationSchedule.isRunInProgress());
    }

    /** The schedule remembers when a run was last due; every test starts well after the one before. */
    private static long scheduleTestStart() {
        return System.currentTimeMillis() + SCHEDULE_TESTS.incrementAndGet() * Duration.ofDays(30).toMillis();
    }

    private static String syncPath(int groupId) {
        return "/api/groups/" + groupId + "/sync";
    }
}
