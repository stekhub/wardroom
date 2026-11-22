package io.github.stekhub.wardroom.sync.internal;

import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE;
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
import io.github.stekhub.wardroom.groups.ThreadRecomputation;
import io.github.stekhub.wardroom.jooq.tables.Article;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Where threads are recomputed (spec 006, "Recomputation"): after a synchronization, after "load older" and a
 * jump, after unsubscribing and after a renumbering. Against the whole application, a real SQLite database and the
 * mock NNTP server.
 */
@IntegrationTest
@Import(SynchronizationTestSupport.ShortTimeouts.class)
class ThreadRecomputationTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DSLContext database;

    @Autowired
    SynchronizationSchedule synchronizationSchedule;

    @Autowired
    ThreadRecomputation threadRecomputation;

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
    }

    @AfterEach
    void removeServer() throws IOException {
        await().atMost(TIMEOUT).until(() -> !synchronizationSchedule.isRunInProgress());
        support.awaitNoSynchronization();
        assertThat(browser.delete("/api/servers/" + serverId)).hasStatus(HttpStatus.NO_CONTENT);
        mockServer.close();
    }

    @Test
    void aSynchronizationThreadsTheArticlesItStores() {
        int groupId = support.subscribe("example.talk");
        mockServer.on("GROUP example.talk").reply("211 1000 1 1000 example.talk");
        mockServer.on("OVER 951-1000").reply(overviews(
                overview(960, "Hello", "<a@example.org>", ""),
                overview(970, "Re: Hello", "<b@example.org>", "<a@example.org>"),
                overview(980, "Re: Hello", "<d@example.org>", "<a@example.org> <c@example.org>")));

        support.synchronize(groupId);

        assertThat(structure()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "<a@example.org>", "null root <a@example.org>",
                "<b@example.org>", "<a@example.org> root <a@example.org>",
                "<c@example.org>", "<a@example.org> root <a@example.org> stub",
                "<d@example.org>", "<c@example.org> root <a@example.org>"));
    }

    @Test
    void aSynchronizationThatFetchesNothingThreadsTheArticlesThatHaveNoPlace() {
        int groupId = support.subscribe("example.talk");
        mockServer.on("GROUP example.talk").reply("211 1000 1 1000 example.talk");
        mockServer.on("OVER 951-1000").reply(overviews(
                overview(960, "Hello", "<a@example.org>", ""),
                overview(970, "Re: Hello", "<b@example.org>", "<a@example.org>")));
        support.synchronize(groupId);
        // As after a threading that failed, or for articles that were stored before threads existed.
        database.update(ARTICLE).setNull(ARTICLE.PARENT_ID).setNull(ARTICLE.THREAD_ROOT_ID).execute();

        support.synchronize(groupId);

        assertThat(structure()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "<a@example.org>", "null root <a@example.org>",
                "<b@example.org>", "<a@example.org> root <a@example.org>"));
    }

    @Test
    void loadOlderFillsAStubAndBringsTheOriginalPostWhichBecomesTheRoot() {
        int groupId = support.subscribe("example.talk");
        mockServer.on("GROUP example.talk").reply("211 1000 1 1000 example.talk");
        mockServer.on("OVER 951-1000").reply(overviews(
                overview(960, "Re: Hello", "<c@example.org>", "<b@example.org>"),
                overview(970, "Re: Hello", "<d@example.org>", "<b@example.org> <c@example.org>")));
        support.synchronize(groupId);
        assertThat(structure()).containsEntry("<b@example.org>", "null root <b@example.org> stub")
                .containsEntry("<d@example.org>", "<c@example.org> root <b@example.org>");
        mockServer.on("OVER 901-950").reply(overviews(
                overview(910, "Hello", "<a@example.org>", ""),
                overview(920, "Re: Hello", "<b@example.org>", "<a@example.org>")));

        assertThat(browser.post("/api/groups/" + groupId + "/ranges/" + newestRangeId(groupId) + "/extend"))
                .hasStatusOk();

        assertThat(structure()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "<a@example.org>", "null root <a@example.org>",
                "<b@example.org>", "<a@example.org> root <a@example.org>",
                "<c@example.org>", "<b@example.org> root <a@example.org>",
                "<d@example.org>", "<c@example.org> root <a@example.org>"));
    }

    @Test
    void aJumpThreadsItsArticlesAndTheSubjectFallbackFindsARootThatWasLoadedLater() {
        int groupId = support.subscribe("example.talk");
        mockServer.on("GROUP example.talk").reply("211 1000 1 1000 example.talk");
        long day = 86_400_000L;
        // A reply from a client that set no References.
        mockServer.on("OVER 951-1000").reply(overviews(
                datedOverview(960, "<reply@example.org>", 20 * day).replace("Subject 960", "Re: Hello")));
        support.synchronize(groupId);
        assertThat(structure()).containsEntry("<reply@example.org>", "null root <reply@example.org>");
        // The oldest article is before the chosen time, and every article the search looks at is after it.
        mockServer.on("OVER 1-20").reply(overviews(
                datedOverview(1, "<original@example.org>", 10 * day).replace("Subject 1", "Hello")));
        for (int windowStart : new int[] {501, 251, 126, 63, 32, 16}) {
            mockServer.on("OVER " + windowStart + "-" + (windowStart + 19)).reply(overviews(
                    datedOverview(windowStart, "<probe-" + windowStart + "@example.org>", 16 * day)));
        }
        mockServer.on("OVER 1-15").reply(overviews(
                datedOverview(1, "<original@example.org>", 10 * day).replace("Subject 1", "Hello")));

        assertThat(browser.post("/api/groups/" + groupId + "/jump", "{\"until\": " + (15 * day) + "}"))
                .hasStatusOk();

        assertThat(structure()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "<original@example.org>", "null root <original@example.org>",
                "<reply@example.org>", "<original@example.org> root <original@example.org>"));
    }

    @Test
    void afterUnsubscribingTheThreadsOfTheOtherGroupsAndTheStubsTheyReferenceAreIntact() {
        int firstGroupId = support.subscribe("example.first");
        int secondGroupId = support.subscribe("example.second");
        mockServer.on("GROUP example.first").reply("211 10 1 10 example.first");
        mockServer.on("GROUP example.second").reply("211 10 101 110 example.second");
        mockServer.on("OVER 1-10").reply(overviews(
                overview(1, "Hello", "<root@example.org>", ""),
                overview(2, "Re: Hello", "<crossposted@example.org>", "<root@example.org>"),
                overview(3, "Re: Other", "<first-only@example.org>", "<missing@example.org>")));
        mockServer.on("OVER 101-110").reply(overviews(
                overview(101, "Re: Hello", "<crossposted@example.org>", "<root@example.org>"),
                overview(102, "Re: Hello", "<reply@example.org>",
                        "<root@example.org> <crossposted@example.org>"),
                overview(103, "Re: Other", "<second-only@example.org>", "<missing@example.org>")));
        support.synchronize(firstGroupId);
        support.synchronize(secondGroupId);
        assertThat(structure()).containsEntry("<reply@example.org>", "<crossposted@example.org> root <root@example.org>")
                .containsEntry("<root@example.org>", "null root <root@example.org>")
                .containsEntry("<missing@example.org>", "null root <missing@example.org> stub");

        assertThat(browser.delete("/api/groups/" + firstGroupId + "/subscription")).hasStatus(HttpStatus.NO_CONTENT);

        // The root was located only in the first group: it is a stub again, and still holds the thread together.
        assertThat(structure()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "<root@example.org>", "null root <root@example.org> stub",
                "<crossposted@example.org>", "<root@example.org> root <root@example.org>",
                "<reply@example.org>", "<crossposted@example.org> root <root@example.org>",
                "<missing@example.org>", "null root <missing@example.org> stub",
                "<second-only@example.org>", "<missing@example.org> root <missing@example.org>"));
    }

    @Test
    void unsubscribingLeavesTheSameThreadsAsACompleteRecomputationAndDoesNotTouchOtherThreads() {
        int firstGroupId = support.subscribe("example.first");
        int secondGroupId = support.subscribe("example.second");
        long day = 86_400_000L;
        mockServer.on("GROUP example.first").reply("211 10 1 10 example.first");
        mockServer.on("GROUP example.second").reply("211 10 101 110 example.second");
        mockServer.on("OVER 1-10").reply(overviews(
                overview(1, "Hello", "<root@example.org>", ""),
                overview(2, "Re: Hello", "<crossposted@example.org>", "<root@example.org>"),
                overview(3, "Re: Other", "<first-only@example.org>", "<missing@example.org>"),
                datedOverview(4, "<topic@example.org>", 10 * day).replace("Subject 4", "Topic"),
                overview(5, "Re: Deep", "<deep-first@example.org>",
                        "<gone-a@example.org> <gone-b@example.org>")));
        mockServer.on("OVER 101-110").reply(overviews(
                overview(101, "Re: Hello", "<crossposted@example.org>", "<root@example.org>"),
                overview(102, "Re: Hello", "<reply@example.org>",
                        "<root@example.org> <crossposted@example.org>"),
                overview(103, "Re: Other", "<second-only@example.org>", "<missing@example.org>"),
                datedOverview(104, "<topic@example.org>", 10 * day).replace("Subject 104", "Topic"),
                // A reply from a client that set no References; the subject fallback attaches it.
                datedOverview(105, "<topic-reply@example.org>", 11 * day).replace("Subject 105", "Re: Topic"),
                overview(106, "Re: Deep", "<deep-second@example.org>",
                        "<gone-a@example.org> <gone-b@example.org> <deep-first@example.org>"),
                overview(107, "Re: Third", "<third@example.org>", "<elsewhere@example.org>")));
        support.synchronize(firstGroupId);
        support.synchronize(secondGroupId);
        assertThat(structure()).containsEntry("<topic-reply@example.org>",
                "<topic@example.org> root <topic@example.org>");
        // A stub has no subject. The mark shows whether the stub was deleted and created again.
        database.update(ARTICLE).set(ARTICLE.SUBJECT, "marked")
                .where(ARTICLE.MESSAGE_ID.in("<elsewhere@example.org>", "<missing@example.org>")).execute();

        assertThat(browser.delete("/api/groups/" + firstGroupId + "/subscription")).hasStatus(HttpStatus.NO_CONTENT);

        Map<String, String> afterUnsubscribing = structure();
        assertThat(afterUnsubscribing).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("<root@example.org>", "null root <root@example.org> stub"),
                Map.entry("<crossposted@example.org>", "<root@example.org> root <root@example.org>"),
                Map.entry("<reply@example.org>", "<crossposted@example.org> root <root@example.org>"),
                Map.entry("<missing@example.org>", "null root <missing@example.org> stub"),
                Map.entry("<second-only@example.org>", "<missing@example.org> root <missing@example.org>"),
                Map.entry("<topic@example.org>", "null root <topic@example.org>"),
                Map.entry("<topic-reply@example.org>", "<topic@example.org> root <topic@example.org>"),
                Map.entry("<gone-a@example.org>", "null root <gone-a@example.org> stub"),
                Map.entry("<gone-b@example.org>", "<gone-a@example.org> root <gone-a@example.org> stub"),
                Map.entry("<deep-first@example.org>", "<gone-b@example.org> root <gone-a@example.org> stub"),
                Map.entry("<deep-second@example.org>", "<deep-first@example.org> root <gone-a@example.org>"),
                Map.entry("<elsewhere@example.org>", "null root <elsewhere@example.org> stub"),
                Map.entry("<third@example.org>", "<elsewhere@example.org> root <elsewhere@example.org>")));
        // The thread of "Third" had no article in the first group; the thread of "Other" had one.
        assertThat(subjectOf("<elsewhere@example.org>")).isEqualTo("marked");
        assertThat(subjectOf("<missing@example.org>")).isNull();

        database.transaction(configuration -> threadRecomputation.recomputeAllThreads());

        assertThat(structure()).isEqualTo(afterUnsubscribing);
    }

    @Test
    void afterARenumberingTheGroupIsThreadedAgainFromWhatTheServerHasNow() {
        int groupId = support.subscribe("example.talk");
        mockServer.on("GROUP example.talk").reply("211 1000 1 1000 example.talk");
        mockServer.on("OVER 951-1000").reply(overviews(
                overview(960, "Re: Hello", "<b@example.org>", "<a@example.org>")));
        support.synchronize(groupId);
        assertThat(structure()).containsKey("<a@example.org>");
        mockServer.on("GROUP example.talk").reply("211 30 1 30 example.talk");
        mockServer.on("OVER 1-30").reply(overviews(
                overview(7, "Re: News", "<y@example.org>", "<x@example.org>")));

        support.synchronize(groupId);

        assertThat(structure()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "<x@example.org>", "null root <x@example.org> stub",
                "<y@example.org>", "<x@example.org> root <x@example.org>"));
    }

    private String subjectOf(String messageId) {
        return database.select(ARTICLE.SUBJECT).from(ARTICLE).where(ARTICLE.MESSAGE_ID.eq(messageId))
                .fetchSingle(ARTICLE.SUBJECT);
    }

    private long newestRangeId(int groupId) {
        return database.select(SYNCED_RANGE.ID).from(SYNCED_RANGE)
                .where(SYNCED_RANGE.NEWSGROUP_ID.eq(groupId))
                .orderBy(SYNCED_RANGE.LAST_NUMBER.desc())
                .limit(1)
                .fetchSingle(SYNCED_RANGE.ID);
    }

    /**
     * @return every article and stub by Message-ID, as {@code "<parent> root <root>"}, followed by {@code " stub"}
     *         for a stub
     */
    private Map<String, String> structure() {
        Article parent = ARTICLE.as("parent");
        Article root = ARTICLE.as("root");
        Map<String, String> structure = new TreeMap<>();
        database.select(ARTICLE.MESSAGE_ID, parent.MESSAGE_ID, root.MESSAGE_ID, ARTICLE.IS_STUB)
                .from(ARTICLE)
                .leftJoin(parent).on(parent.ID.eq(ARTICLE.PARENT_ID))
                .leftJoin(root).on(root.ID.eq(ARTICLE.THREAD_ROOT_ID))
                .forEach(row -> structure.put(row.value1(), row.value2() + " root " + row.value3()
                        + (Boolean.TRUE.equals(row.value4()) ? " stub" : "")));
        return structure;
    }
}
