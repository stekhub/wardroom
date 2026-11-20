package io.github.stekhub.wardroom.groups.internal;

import static io.github.stekhub.wardroom.groups.internal.GroupListTestSupport.body;
import static io.github.stekhub.wardroom.groups.internal.GroupListTestSupport.refreshAndWait;
import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE;
import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE_LOCATION;
import static io.github.stekhub.wardroom.jooq.Tables.NEWSGROUP;
import static io.github.stekhub.wardroom.jooq.Tables.READ_STATE;
import static io.github.stekhub.wardroom.jooq.Tables.SUBSCRIPTION;
import static io.github.stekhub.wardroom.jooq.Tables.SYNCED_RANGE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.IntegrationTest;
import io.github.stekhub.wardroom.TestBrowser;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import io.github.stekhub.wardroom.nntp.mock.MockReply;
import java.io.IOException;
import java.util.List;
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
 * Subscribing, unsubscribing, unread counts and marking a group as read (spec 004), against the whole application
 * and a real SQLite database. The articles are put into the database by the tests. Nothing is synchronized here;
 * what subscribing and unsubscribing do with the synchronization is in {@code SynchronizationAutomaticTest}.
 */
@IntegrationTest
class SubscriptionTest {

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
        mockServer.on("LIST ACTIVE").reply(MockReply.fixture("list-active.txt"));
        mockServer.on("LIST NEWSGROUPS").reply(MockReply.fixture("list-newsgroups.txt"));
        serverId = GroupListTestSupport.addServer(browser, mockServer);
    }

    @AfterEach
    void removeServer() throws IOException {
        assertThat(browser.delete("/api/servers/" + serverId)).hasStatus(HttpStatus.NO_CONTENT);
        mockServer.close();
    }

    // Access

    @Test
    void endpointsNeedASessionAndTheCsrfToken() {
        int groupId = storeGroup("example.talk");

        assertThat(new TestBrowser(mvc).get("/api/subscriptions")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(new TestBrowser(mvc).put(subscriptionPath(groupId))).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(new TestBrowser(mvc).delete(subscriptionPath(groupId))).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(new TestBrowser(mvc).post(markAllReadPath(groupId))).hasStatus(HttpStatus.UNAUTHORIZED);
        TestBrowser secondBrowser = new TestBrowser(mvc);
        assertThat(secondBrowser.post("/api/auth/login",
                TestBrowser.json("username", "owner", "password", "correct horse battery")))
                .hasStatus(HttpStatus.NO_CONTENT);
        secondBrowser.withoutCsrfToken();
        assertThat(secondBrowser.put(subscriptionPath(groupId))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(secondBrowser.delete(subscriptionPath(groupId))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(secondBrowser.post(markAllReadPath(groupId))).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(database.fetchCount(SUBSCRIPTION)).isZero();
    }

    @Test
    void unknownGroupIsNotFound() {
        int unknownGroupId = storeGroup("example.talk") + 1;

        for (MvcTestResult answer : List.of(browser.put(subscriptionPath(unknownGroupId)),
                browser.delete(subscriptionPath(unknownGroupId)), browser.post(markAllReadPath(unknownGroupId)))) {
            assertThat(answer).hasStatus(HttpStatus.NOT_FOUND)
                    .bodyJson().extractingPath("$.detail").isEqualTo("This group does not exist.");
        }
    }

    // Subscribing

    @Test
    void subscribingStoresTheSubscriptionAndRepeatingItChangesNothing() {
        int groupId = storeGroup("comp.lang.java.programmer");
        database.update(NEWSGROUP).set(NEWSGROUP.DESCRIPTION, "Programming in the Java language.")
                .set(NEWSGROUP.POSTING_STATUS, "MODERATED").where(NEWSGROUP.ID.eq(groupId)).execute();
        assertThat(subscriptions()).isEmpty();

        assertThat(browser.put(subscriptionPath(groupId))).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(browser.put(subscriptionPath(groupId))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(database.fetchCount(SUBSCRIPTION)).isEqualTo(1);
        assertThat(browser.get("/api/subscriptions")).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                [{"groupId": %d, "serverId": %d, "name": "comp.lang.java.programmer",
                  "description": "Programming in the Java language.", "postingStatus": "MODERATED",
                  "removed": false, "unreadCount": 0,
                  "sync": {"running": false, "lastSyncedAt": null, "error": null}}]
                """.formatted(groupId, serverId));
        assertThat(browser.get("/api/servers/" + serverId + "/groups")).hasStatusOk().bodyJson()
                .extractingPath("$.items[0].subscribed").isEqualTo(true);
    }

    @Test
    void subscriptionsAreListedByNameAndOnlyTheSubscribedGroupsCarryTheFlag() {
        int talk = storeGroup("example.talk");
        storeGroup("example.misc");
        int announce = storeGroup("example.announce");
        subscribe(talk);
        subscribe(announce);

        assertThat(names(subscriptions())).containsExactly("example.announce", "example.talk");
        assertThat(browser.get("/api/servers/" + serverId + "/groups")).hasStatusOk().bodyJson()
                .extractingPath("$.items[*].subscribed").asArray().containsExactly(true, false, true);
    }

    @Test
    void subscribingToARemovedGroupIsRefused() {
        int groupId = storeGroup("example.gone");
        database.update(NEWSGROUP).set(NEWSGROUP.REMOVED_AT, 1_795_000_000_000L).execute();

        assertThat(browser.put(subscriptionPath(groupId))).hasStatus(HttpStatus.CONFLICT).bodyJson()
                .extractingPath("$.detail")
                .isEqualTo("The server no longer has this group, so it cannot be subscribed to.");
        assertThat(database.fetchCount(SUBSCRIPTION)).isZero();
    }

    @Test
    void subscriptionShowsHowTheLastSynchronizationEnded() {
        int groupId = storeGroup("example.talk");
        subscribe(groupId);
        database.update(NEWSGROUP).set(NEWSGROUP.LAST_SYNCED_AT, 1_795_000_000_000L)
                .set(NEWSGROUP.LAST_SYNC_ERROR, "The server did not answer in time").execute();

        JsonNode sync = subscriptions().get(0).get("sync");

        assertThat(sync.get("running").asBoolean()).isFalse();
        assertThat(sync.get("lastSyncedAt").asLong()).isEqualTo(1_795_000_000_000L);
        assertThat(sync.get("error").asString()).isEqualTo("The server did not answer in time");
    }

    // Removed groups

    @Test
    void subscribedGroupThatTheServerRemovesStaysInTheListAsRemoved() {
        refreshAndWait(browser, serverId);
        int announce = groupId("example.announce");
        int talk = groupId("example.talk");
        subscribe(announce);
        subscribe(talk);
        int article = storeArticle("<1@example.test>", false);
        storeLocation(announce, 1, article);
        mockServer.on("LIST ACTIVE").reply("215 Newsgroups follow", "example.talk 20 1 y", ".");

        refreshAndWait(browser, serverId);

        JsonNode subscriptions = subscriptions();
        assertThat(names(subscriptions)).containsExactly("example.announce", "example.talk");
        assertThat(subscriptions.get(0).get("removed").asBoolean()).isTrue();
        assertThat(subscriptions.get(0).get("unreadCount").asInt()).as("the cached articles stay").isEqualTo(1);
        assertThat(subscriptions.get(1).get("removed").asBoolean()).isFalse();
        assertThat(browser.put(subscriptionPath(announce))).as("subscribed already, so nothing to refuse")
                .hasStatus(HttpStatus.NO_CONTENT);
    }

    // Unread count

    @Test
    void unreadCountLeavesOutStubsAndReadArticlesAndCountsExpiredLocations() {
        int groupId = storeGroup("example.talk");
        int otherGroupId = storeGroup("example.misc");
        subscribe(groupId);
        subscribe(otherGroupId);
        storeLocation(groupId, 1, storeArticle("<unread@example.test>", false));
        int readArticle = storeArticle("<read@example.test>", false);
        storeLocation(groupId, 2, readArticle);
        markRead(readArticle);
        storeLocation(groupId, 3, storeArticle("<stub@example.test>", true));
        int expiredArticle = storeArticle("<expired@example.test>", false);
        storeLocation(groupId, 4, expiredArticle);
        database.update(ARTICLE_LOCATION).set(ARTICLE_LOCATION.EXPIRED_AT, 1_795_000_000_000L)
                .where(ARTICLE_LOCATION.ARTICLE_ID.eq(expiredArticle)).execute();
        storeLocation(otherGroupId, 1, storeArticle("<elsewhere@example.test>", false));
        storeLocation(otherGroupId, 2, expiredArticle);

        JsonNode subscriptions = subscriptions();

        assertThat(names(subscriptions)).containsExactly("example.misc", "example.talk");
        assertThat(subscriptions.get(1).get("unreadCount").asInt()).as("unread and expired").isEqualTo(2);
        assertThat(subscriptions.get(0).get("unreadCount").asInt()).as("its own and the crosspost").isEqualTo(2);
    }

    // Mark all as read

    @Test
    void markAllReadMarksEveryArticleOfTheGroupThatIsNotAStubAlsoInItsOtherGroups() {
        int groupId = storeGroup("example.talk");
        int otherGroupId = storeGroup("example.misc");
        subscribe(groupId);
        subscribe(otherGroupId);
        int plainArticle = storeArticle("<plain@example.test>", false);
        storeLocation(groupId, 1, plainArticle);
        int crosspost = storeArticle("<crosspost@example.test>", false);
        storeLocation(groupId, 2, crosspost);
        storeLocation(otherGroupId, 1, crosspost);
        int readBefore = storeArticle("<read@example.test>", false);
        storeLocation(groupId, 3, readBefore);
        markRead(readBefore);
        int stub = storeArticle("<stub@example.test>", true);
        storeLocation(groupId, 4, stub);
        int elsewhere = storeArticle("<elsewhere@example.test>", false);
        storeLocation(otherGroupId, 2, elsewhere);
        long before = System.currentTimeMillis();

        assertThat(browser.post(markAllReadPath(groupId))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(readArticles()).containsExactlyInAnyOrder(plainArticle, crosspost, readBefore);
        assertThat(database.select(READ_STATE.READ_AT).from(READ_STATE)
                .where(READ_STATE.ARTICLE_ID.eq(plainArticle)).fetchSingle().value1())
                .isBetween(before, System.currentTimeMillis());
        assertThat(database.select(READ_STATE.READ_AT).from(READ_STATE)
                .where(READ_STATE.ARTICLE_ID.eq(readBefore)).fetchSingle().value1())
                .as("an article that was read keeps its time").isEqualTo(1_795_000_000_000L);
        JsonNode subscriptions = subscriptions();
        assertThat(subscriptions.get(1).get("unreadCount").asInt()).isZero();
        assertThat(subscriptions.get(0).get("unreadCount").asInt()).as("the crosspost is read there too")
                .isEqualTo(1);
        assertThat(browser.post(markAllReadPath(groupId))).as("nothing left to mark")
                .hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void markAllReadIsRefusedForAGroupThatIsNotSubscribed() {
        int groupId = storeGroup("example.talk");

        assertThat(browser.post(markAllReadPath(groupId))).hasStatus(HttpStatus.CONFLICT).bodyJson()
                .extractingPath("$.detail").isEqualTo("This group is not subscribed.");
    }

    // Unsubscribing

    @Test
    void unsubscribingRemovesTheCachedArticlesOfTheGroupAndKeepsCrosspostsOfOtherGroups() {
        int groupId = storeGroup("example.talk");
        int otherGroupId = storeGroup("example.misc");
        subscribe(groupId);
        subscribe(otherGroupId);
        int ownArticle = storeArticle("<own@example.test>", false);
        storeLocation(groupId, 1, ownArticle);
        markRead(ownArticle);
        int crosspost = storeArticle("<crosspost@example.test>", false);
        storeLocation(groupId, 2, crosspost);
        storeLocation(otherGroupId, 1, crosspost);
        markRead(crosspost);
        int elsewhere = storeArticle("<elsewhere@example.test>", false);
        storeLocation(otherGroupId, 2, elsewhere);
        int stub = storeArticle("<stub@example.test>", true);
        storeRange(groupId, 1, 2);
        storeRange(otherGroupId, 1, 2);

        assertThat(browser.delete(subscriptionPath(groupId))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(names(subscriptions())).containsExactly("example.misc");
        assertThat(database.fetchCount(ARTICLE_LOCATION, ARTICLE_LOCATION.NEWSGROUP_ID.eq(groupId))).isZero();
        assertThat(database.select(ARTICLE.ID).from(ARTICLE).fetch(ARTICLE.ID))
                .as("the article of the group and the stub without a location are gone")
                .containsExactlyInAnyOrder(crosspost, elsewhere).doesNotContain(ownArticle, stub);
        assertThat(readArticles()).containsExactly(crosspost);
        assertThat(database.select(ARTICLE_LOCATION.ARTICLE_ID).from(ARTICLE_LOCATION)
                .where(ARTICLE_LOCATION.NEWSGROUP_ID.eq(otherGroupId)).fetch(ARTICLE_LOCATION.ARTICLE_ID))
                .containsExactlyInAnyOrder(crosspost, elsewhere);
        assertThat(database.select(SYNCED_RANGE.NEWSGROUP_ID).from(SYNCED_RANGE).fetch(SYNCED_RANGE.NEWSGROUP_ID))
                .containsExactly(otherGroupId);
        assertThat(database.fetchCount(NEWSGROUP)).as("the groups stay in the group list").isEqualTo(2);
        assertThat(browser.get("/api/servers/" + serverId + "/groups")).hasStatusOk().bodyJson()
                .extractingPath("$.items[*].subscribed").asArray().containsExactly(true, false);
    }

    @Test
    void unsubscribingFromAGroupThatIsNotSubscribedChangesNothing() {
        int groupId = storeGroup("example.talk");
        int otherGroupId = storeGroup("example.misc");
        subscribe(otherGroupId);
        storeLocation(otherGroupId, 1, storeArticle("<kept@example.test>", false));

        assertThat(browser.delete(subscriptionPath(groupId))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(names(subscriptions())).containsExactly("example.misc");
        assertThat(database.fetchCount(ARTICLE)).isEqualTo(1);
    }

    // Removing the server (spec 002)

    @Test
    void removingTheServerDeletesSubscriptionsAndCachedArticles() throws IOException {
        int groupId = storeGroup("example.talk");
        subscribe(groupId);
        int article = storeArticle("<1@example.test>", false);
        storeLocation(groupId, 1, article);
        markRead(article);
        storeRange(groupId, 1, 1);

        assertThat(browser.delete("/api/servers/" + serverId)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(database.fetchCount(SUBSCRIPTION)).isZero();
        assertThat(database.fetchCount(ARTICLE_LOCATION)).isZero();
        assertThat(database.fetchCount(ARTICLE)).isZero();
        assertThat(database.fetchCount(READ_STATE)).isZero();
        assertThat(database.fetchCount(SYNCED_RANGE)).isZero();
        assertThat(database.fetchCount(NEWSGROUP)).isZero();
        assertThat(browser.get("/api/subscriptions")).hasStatusOk().bodyJson().isStrictlyEqualTo("[]");
        // The cleanup after the test removes a server; give it one.
        serverId = GroupListTestSupport.addServer(browser, mockServer);
    }

    private static String subscriptionPath(int groupId) {
        return "/api/groups/" + groupId + "/subscription";
    }

    private static String markAllReadPath(int groupId) {
        return "/api/groups/" + groupId + "/mark-all-read";
    }

    private void subscribe(int groupId) {
        assertThat(browser.put(subscriptionPath(groupId))).hasStatus(HttpStatus.NO_CONTENT);
    }

    private JsonNode subscriptions() {
        MvcTestResult subscriptions = browser.get("/api/subscriptions");
        assertThat(subscriptions).hasStatusOk();
        return body(subscriptions);
    }

    private static List<String> names(JsonNode subscriptions) {
        return subscriptions.valueStream().map(subscription -> subscription.get("name").asString()).toList();
    }

    private List<Integer> readArticles() {
        return database.select(READ_STATE.ARTICLE_ID).from(READ_STATE).fetch(READ_STATE.ARTICLE_ID);
    }

    private int groupId(String name) {
        return database.select(NEWSGROUP.ID).from(NEWSGROUP).where(NEWSGROUP.NAME.eq(name)).fetchSingle().value1();
    }

    private int storeGroup(String name) {
        return database.insertInto(NEWSGROUP)
                .set(NEWSGROUP.SERVER_ID, (int) serverId)
                .set(NEWSGROUP.NAME, name)
                .set(NEWSGROUP.POSTING_STATUS, "ALLOWED")
                .set(NEWSGROUP.LOW_WATERMARK, 1L)
                .set(NEWSGROUP.HIGH_WATERMARK, 10L)
                .returning(NEWSGROUP.ID)
                .fetchSingle().getId();
    }

    private int storeArticle(String messageId, boolean stub) {
        return database.insertInto(ARTICLE)
                .set(ARTICLE.MESSAGE_ID, messageId)
                .set(ARTICLE.IS_STUB, stub)
                .returning(ARTICLE.ID)
                .fetchSingle().getId();
    }

    private void storeLocation(int groupId, long articleNumber, int articleId) {
        database.insertInto(ARTICLE_LOCATION)
                .set(ARTICLE_LOCATION.NEWSGROUP_ID, groupId)
                .set(ARTICLE_LOCATION.ARTICLE_NUMBER, articleNumber)
                .set(ARTICLE_LOCATION.ARTICLE_ID, articleId)
                .execute();
    }

    private void storeRange(int groupId, long firstNumber, long lastNumber) {
        database.insertInto(SYNCED_RANGE)
                .set(SYNCED_RANGE.NEWSGROUP_ID, groupId)
                .set(SYNCED_RANGE.FIRST_NUMBER, firstNumber)
                .set(SYNCED_RANGE.LAST_NUMBER, lastNumber)
                .execute();
    }

    private void markRead(int articleId) {
        database.insertInto(READ_STATE)
                .set(READ_STATE.ARTICLE_ID, articleId)
                .set(READ_STATE.READ_AT, 1_795_000_000_000L)
                .execute();
    }
}
