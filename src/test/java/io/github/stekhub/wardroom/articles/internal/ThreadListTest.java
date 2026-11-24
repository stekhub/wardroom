package io.github.stekhub.wardroom.articles.internal;

import static io.github.stekhub.wardroom.TestInstallation.body;
import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE;
import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE_LOCATION;
import static io.github.stekhub.wardroom.jooq.Tables.IGNORE_RULE;
import static io.github.stekhub.wardroom.jooq.Tables.NEWSGROUP;
import static io.github.stekhub.wardroom.jooq.Tables.READ_STATE;
import static io.github.stekhub.wardroom.jooq.Tables.SUBSCRIPTION;
import static io.github.stekhub.wardroom.jooq.Tables.SYNCED_RANGE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.IntegrationTest;
import io.github.stekhub.wardroom.TestBrowser;
import io.github.stekhub.wardroom.TestInstallation;
import io.github.stekhub.wardroom.ignoring.IgnoreFilters;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import io.github.stekhub.wardroom.nntp.mock.MockReply;
import java.io.IOException;
import java.time.Instant;
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

/**
 * The thread list, the thread page and "mark thread as read" (spec 008), "next unread" and "mark as unread"
 * (spec 014), against the whole application and a real
 * SQLite database. The articles are stored as the synchronization does and threaded by the application.
 */
@IntegrationTest
class ThreadListTest {

    private static final long DAY = 86_400_000L;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DSLContext database;

    @Autowired
    ThreadService threadService;

    TestBrowser browser;
    MockNntpServer mockServer;
    long serverId;
    int groupId;
    int otherGroupId;

    @BeforeEach
    void startWithGroups() throws IOException {
        browser = TestInstallation.loggedInOwner(mvc, database);
        mockServer = MockNntpServer.start();
        mockServer.on("CAPABILITIES").reply(MockReply.fixture("capabilities-reader.txt"));
        serverId = TestInstallation.addServer(browser, mockServer, 2);
        groupId = subscribedGroup("example.talk");
        otherGroupId = subscribedGroup("example.other");
    }

    @AfterEach
    void removeServer() throws IOException {
        assertThat(browser.delete("/api/servers/" + serverId)).hasStatus(HttpStatus.NO_CONTENT);
        mockServer.close();
    }

    // Thread list

    @Test
    void aGroupShowsTheThreadsWithAnArticleOfItsOwnAndCountsOnlyItsOwnArticles() {
        int firstId = article(groupId, 1, "<a>", "Hello", null, DAY);
        article(groupId, 2, "<b>", "Re: Hello", "<a>", 2 * DAY);
        article(otherGroupId, 1, "<c>", "Re: Hello", "<a>", 9 * DAY);
        article(otherGroupId, 2, "<x>", "Elsewhere", null, 3 * DAY);
        int replyToMissingId = article(groupId, 3, "<r>", "Re: Lost", "<missing>", 4 * DAY);
        threadAll();
        markRead(firstId);

        JsonNode page = threads(Map.of());

        assertThat(page.get("items")).hasSize(2);
        JsonNode lostThread = page.get("items").get(0);
        assertThat(lostThread.get("rootId").asInt()).isEqualTo(idOf("<missing>"));
        assertThat(lostThread.get("cardArticleId").asInt()).isEqualTo(replyToMissingId);
        assertThat(lostThread.get("subject").asString()).isEqualTo("Re: Lost");
        assertThat(lostThread.get("messageCount").asInt()).isEqualTo(1);
        assertThat(lostThread.get("unreadCount").asInt()).isEqualTo(1);
        JsonNode helloThread = page.get("items").get(1);
        assertThat(helloThread.get("rootId").asInt()).isEqualTo(firstId);
        assertThat(helloThread.get("cardArticleId").asInt()).isEqualTo(firstId);
        assertThat(helloThread.get("messageCount").asInt()).isEqualTo(2);
        assertThat(helloThread.get("latestActivity").asLong()).isEqualTo(2 * DAY);
        assertThat(helloThread.get("unreadCount").asInt()).isEqualTo(1);
        assertThat(page.get("nextCursor").isNull()).isTrue();
        assertThat(page.get("rangeId").isNull()).isTrue();
    }

    @Test
    void aCardHasSubjectAuthorDateAndTheExcerptOfItsCachedText() {
        int cachedId = article(groupId, 1, "<a>", "Hello", null, DAY);
        int notCachedId = article(groupId, 2, "<b>", "Other", null, 2 * DAY);
        database.update(ARTICLE)
                .set(ARTICLE.FROM, "Jane Doe <Jane@Example.org>")
                .set(ARTICLE.AUTHOR_KEY, "jane@example.org")
                .set(ARTICLE.BODY_TEXT, "> a quote\nI have been   comparing\nthe two.\n-- \nJane")
                .where(ARTICLE.ID.eq(cachedId))
                .execute();
        threadAll();

        JsonNode items = threads(Map.of("sort", "newest")).get("items");

        JsonNode notCached = items.get(0);
        assertThat(notCached.get("cardArticleId").asInt()).isEqualTo(notCachedId);
        assertThat(notCached.get("excerpt").isNull()).isTrue();
        assertThat(notCached.get("authorKey").asString()).isEqualTo("ada@example.org");
        JsonNode cached = items.get(1);
        assertThat(cached.get("subject").asString()).isEqualTo("Hello");
        assertThat(cached.get("from").asString()).isEqualTo("Jane Doe <Jane@Example.org>");
        assertThat(cached.get("authorKey").asString()).isEqualTo("jane@example.org");
        assertThat(cached.get("date").asLong()).isEqualTo(DAY);
        assertThat(cached.get("excerpt").asString()).isEqualTo("I have been comparing the two.");
    }

    @Test
    void eachSortOrderPutsUndatedThreadsLastAndBreaksTiesByLatestActivity() {
        fiveThreads();

        assertThat(cardSubjects(Map.of("sort", "newest"))).containsExactly("B", "A", "E", "C", "D");
        assertThat(cardSubjects(Map.of("sort", "activity"))).containsExactly("C", "B", "A", "E", "D");
        assertThat(cardSubjects(Map.of())).containsExactly("C", "B", "A", "E", "D");
        assertThat(cardSubjects(Map.of("sort", "discussed"))).containsExactly("C", "A", "E", "B", "D");
    }

    @Test
    void pagingReturnsEveryThreadExactlyOnceInEveryOrder() {
        fiveThreads();
        // Threads that are equal in every sort key.
        for (int number = 50; number < 56; number++) {
            article(groupId, number, "<same" + number + ">", "Same " + number, null, 20 * DAY);
        }
        threadAll();

        for (String sort : List.of("newest", "activity", "discussed")) {
            List<String> allAtOnce = cardSubjects(Map.of("sort", sort));
            assertThat(allAtOnce).hasSize(11).doesNotHaveDuplicates();

            List<String> pageByPage = new ArrayList<>();
            String cursor = null;
            int pages = 0;
            do {
                Map<String, String> parameters = new LinkedHashMap<>(Map.of("sort", sort, "limit", "3"));
                if (cursor != null) {
                    parameters.put("cursor", cursor);
                }
                JsonNode page = threads(parameters);
                assertThat(page.get("items").size()).isBetween(1, 3);
                page.get("items").forEach(item -> pageByPage.add(item.get("subject").asString()));
                cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
                pages++;
            } while (cursor != null);

            assertThat(pageByPage).as(sort).isEqualTo(allAtOnce);
            assertThat(pages).isEqualTo(4);
        }
    }

    // Date view and ranges

    @Test
    void theDateViewShowsTheThreadsOfItsRangeBeforeTheTimeNewestFirst() {
        article(groupId, 1, "<n1>", "One", null, DAY);
        article(groupId, 2, "<n2>", "Two", null, 2 * DAY + DAY / 2);
        article(groupId, 3, "<n3>", "Three", null, 3 * DAY);
        article(groupId, 5, "<n5>", "Five", null, 10 * DAY);
        article(groupId, 6, "<n6>", "Six", null, 11 * DAY);
        long olderRangeId = range(groupId, 1, 3);
        long newestRangeId = range(groupId, 5, 6);
        threadAll();
        String until = Instant.ofEpochMilli(3 * DAY).toString();

        JsonNode dateView = threads(Map.of("until", until, "rangeId", String.valueOf(olderRangeId)));
        JsonNode normalView = threads(Map.of());
        JsonNode firstPage = threads(Map.of("until", until, "rangeId", String.valueOf(olderRangeId), "limit", "1"));
        JsonNode secondPage = threads(Map.of("until", until, "rangeId", String.valueOf(olderRangeId), "limit", "1",
                "cursor", firstPage.get("nextCursor").asString()));

        assertThat(subjects(dateView)).containsExactly("Two", "One");
        assertThat(dateView.get("rangeId").asLong()).isEqualTo(olderRangeId);
        // The group has a low watermark of 0, and the range begins at the first article number.
        assertThat(dateView.get("lowWatermarkReached").asBoolean()).isTrue();
        assertThat(dateView.get("highWatermarkReached").asBoolean()).isFalse();
        assertThat(subjects(normalView)).containsExactly("Six", "Five", "Three", "Two", "One");
        assertThat(normalView.get("rangeId").asLong()).isEqualTo(newestRangeId);
        assertThat(normalView.get("lowWatermarkReached").asBoolean()).isFalse();
        assertThat(normalView.get("highWatermarkReached").asBoolean()).isTrue();
        assertThat(subjects(firstPage)).containsExactly("Two");
        assertThat(subjects(secondPage)).containsExactly("One");
        assertThat(secondPage.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void invalidParametersAreRejected() {
        article(groupId, 1, "<a>", "Hello", null, DAY);
        long rangeId = range(groupId, 1, 1);
        long rangeOfOtherGroupId = range(otherGroupId, 1, 1);
        threadAll();
        String until = Instant.ofEpochMilli(0).toString();
        String cursorOfNewest = new ThreadCursor(ThreadOrder.NEWEST, 1, 1, 1).encode();

        assertRejected(Map.of("sort", "oldest"), "sort");
        assertRejected(Map.of("until", "2015-01-01"), "until");
        assertRejected(Map.of("until", until), "rangeId");
        assertRejected(Map.of("until", until, "rangeId", String.valueOf(rangeOfOtherGroupId)), "rangeId");
        assertRejected(Map.of("rangeId", String.valueOf(rangeId)), "rangeId");
        assertRejected(Map.of("until", until, "rangeId", String.valueOf(rangeId), "sort", "newest"), "sort");
        assertRejected(Map.of("cursor", "not a cursor"), "cursor");
        assertRejected(Map.of("cursor", cursorOfNewest), "cursor");
        assertRejected(Map.of("limit", "0"), "limit");
        assertRejected(Map.of("limit", "101"), "limit");
        assertThat(browser.get(threadsPath(groupId), Map.of("limit", "many"))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(browser.get(threadsPath(groupId), Map.of("sort", "newest", "cursor", cursorOfNewest)))
                .hasStatusOk();
    }

    @Test
    void aGroupThatIsNotSubscribedOrUnknownHasNoThreadList() {
        int unsubscribedGroupId = group("example.unsubscribed");

        assertThat(browser.get(threadsPath(unsubscribedGroupId))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(browser.get(threadsPath(-1))).hasStatus(HttpStatus.NOT_FOUND);
    }

    // Thread page

    @Test
    void anyArticleResolvesToTheThreadWithDepthOrderAndTheGroupOfACrosspost() {
        int rootId = article(groupId, 1, "<a>", "Hello", null, DAY);
        int laterReplyId = article(groupId, 2, "<b>", "Re: Hello", "<a>", 3 * DAY);
        int earlierReplyId = article(groupId, 3, "<c>", "Re: Hello", "<a>", 2 * DAY);
        int nestedReplyId = article(groupId, 4, "<d>", "Re: Hello", "<a> <c>", 4 * DAY);
        int replyToMissingId = article(groupId, 5, "<e>", "Re: Hello", "<a> <gone>", 5 * DAY);
        int crosspostId = article(otherGroupId, 1, "<f>", "Re: Hello", "<a>", 2 * DAY + DAY / 2);
        int undatedHigherNumberId = article(groupId, 21, "<u1>", "Re: Hello", "<a>", null);
        int undatedLowerNumberId = article(groupId, 20, "<u2>", "Re: Hello", "<a>", null);
        threadAll();
        int stubId = idOf("<gone>");
        markRead(rootId);

        JsonNode thread = body(expectOk(browser.get(threadPath(groupId, nestedReplyId))));

        assertThat(thread.get("rootId").asInt()).isEqualTo(rootId);
        assertThat(column(thread, "id")).containsExactly(rootId, earlierReplyId, nestedReplyId, crosspostId,
                laterReplyId, undatedLowerNumberId, undatedHigherNumberId, stubId, replyToMissingId);
        assertThat(column(thread, "depth")).containsExactly(0, 1, 2, 1, 1, 1, 1, 1, 2);
        JsonNode articles = thread.get("articles");
        assertThat(articles.get(0).get("parentId").isNull()).isTrue();
        assertThat(articles.get(0).get("read").asBoolean()).isTrue();
        assertThat(articles.get(0).get("authorKey").asString()).isEqualTo("ada@example.org");
        assertThat(articles.get(0).get("otherGroup").isNull()).isTrue();
        assertThat(articles.get(1).get("read").asBoolean()).isFalse();
        assertThat(articles.get(2).get("parentId").asInt()).isEqualTo(earlierReplyId);
        assertThat(articles.get(3).get("otherGroup").asString()).isEqualTo("example.other");
        assertThat(articles.get(7).get("isStub").asBoolean()).isTrue();
        assertThat(articles.get(7).get("authorKey").isNull()).isTrue();
        assertThat(articles.get(7).get("subject").isNull()).isTrue();
        assertThat(articles.get(8).get("parentId").asInt()).isEqualTo(stubId);
        // The stub and the crosspost lead to the same thread.
        assertThat(body(expectOk(browser.get(threadPath(groupId, stubId)))).get("rootId").asInt())
                .isEqualTo(rootId);
        assertThat(body(expectOk(browser.get(threadPath(groupId, crosspostId)))).get("rootId").asInt())
                .isEqualTo(rootId);
    }

    @Test
    void aThreadWithoutAnArticleInTheGroupAndAnUnknownArticleAreNotFound() {
        int elsewhereId = article(otherGroupId, 1, "<x>", "Elsewhere", null, DAY);
        int unsubscribedGroupId = group("example.unsubscribed");
        threadAll();

        assertThat(browser.get(threadPath(groupId, elsewhereId))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(browser.get(threadPath(groupId, -1))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(browser.get(threadPath(unsubscribedGroupId, elsewhereId))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(browser.get(threadPath(otherGroupId, elsewhereId))).hasStatusOk();
    }

    // Mark thread as read

    @Test
    void markingAThreadAsReadMarksItsArticlesOfTheGroupAndNoOthers() {
        int rootId = article(groupId, 1, "<a>", "Hello", null, DAY);
        int replyId = article(groupId, 2, "<b>", "Re: Hello", "<a> <gone>", 2 * DAY);
        article(otherGroupId, 1, "<f>", "Re: Hello", "<a>", 3 * DAY);
        int crosspostedId = article(groupId, 3, "<g>", "Re: Hello", "<a>", 4 * DAY);
        locate(crosspostedId, otherGroupId, 2);
        article(groupId, 4, "<z>", "Another", null, 5 * DAY);
        threadAll();
        markRead(rootId);

        assertThat(browser.post(markReadPath(groupId, replyId))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(readArticleIds()).containsExactlyInAnyOrder(rootId, replyId, crosspostedId);
        assertThat(browser.post(markReadPath(groupId, replyId))).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(readArticleIds()).hasSize(3);
        JsonNode cards = threads(Map.of("sort", "newest")).get("items");
        assertThat(cards.get(0).get("unreadCount").asInt()).isEqualTo(1);
        assertThat(cards.get(1).get("unreadCount").asInt()).isZero();
        // The crossposted article is read in the other group as well; the article located only there is not.
        assertThat(threads(otherGroupId, Map.of()).get("items").get(0).get("unreadCount").asInt()).isEqualTo(1);
    }

    @Test
    void markingAThreadAsReadNeedsASubscribedGroupThatShowsTheThread() {
        int elsewhereId = article(otherGroupId, 1, "<x>", "Elsewhere", null, DAY);
        int unsubscribedGroupId = group("example.unsubscribed");
        threadAll();

        assertThat(browser.post(markReadPath(unsubscribedGroupId, elsewhereId))).hasStatus(HttpStatus.CONFLICT);
        assertThat(browser.post(markReadPath(-1, elsewhereId))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(browser.post(markReadPath(groupId, elsewhereId))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(browser.post(markReadPath(groupId, -1))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(readArticleIds()).isEmpty();
    }

    // Next unread (spec 014)

    @Test
    void nextUnreadFollowsEverySortOrderAndSkipsReadThreads() {
        fiveThreads();
        markRead(idOf("<b>"));
        // In thread A only the reply is unread, in thread E only the root.
        markRead(idOf("<a>"));
        markRead(idOf("<e2>"));

        for (String sort : List.of("newest", "activity", "discussed")) {
            List<Integer> expected = new ArrayList<>();
            threads(Map.of("sort", sort)).get("items").forEach(item -> {
                if (item.get("unreadCount").asInt() > 0) {
                    expected.add(item.get("rootId").asInt());
                }
            });
            assertThat(expected).as(sort).hasSize(4).doesNotContain(idOf("<b>"));

            assertThat(walkNextUnread(Map.of("sort", sort))).as(sort).isEqualTo(expected);
        }
        // Without a sort order, the list is sorted by latest activity.
        assertThat(walkNextUnread(Map.of())).isEqualTo(walkNextUnread(Map.of("sort", "activity")));
    }

    @Test
    void nextUnreadInTheMiddleGoesOnAfterTheCurrentThreadAndAtTheEndFindsNothing() {
        fiveThreads();
        // By latest activity: C, B, A, E, D.
        int threadBId = idOf("<b>");
        int replyOfAId = idOf("<a2>");

        assertThat(nextUnread(Map.of("after", String.valueOf(threadBId)))).isEqualTo(idOf("<a>"));
        // Any article of the thread stands for it.
        assertThat(nextUnread(Map.of("after", String.valueOf(replyOfAId)))).isEqualTo(idOf("<e>"));
        assertThat(browser.get(nextUnreadPath(groupId), Map.of("after", String.valueOf(idOf("<d>")))))
                .hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void nextUnreadInTheDateViewStaysWithinTheView() {
        article(groupId, 1, "<n1>", "One", null, DAY);
        article(groupId, 2, "<n2>", "Two", null, 2 * DAY);
        article(groupId, 3, "<n3>", "Three", null, 4 * DAY);
        article(groupId, 5, "<n5>", "Five", null, 10 * DAY);
        long olderRangeId = range(groupId, 1, 3);
        range(groupId, 5, 5);
        threadAll();
        Map<String, String> dateView = Map.of("until", Instant.ofEpochMilli(3 * DAY).toString(),
                "rangeId", String.valueOf(olderRangeId));

        assertThat(walkNextUnread(dateView)).containsExactly(idOf("<n2>"), idOf("<n1>"));
        assertThat(walkNextUnread(Map.of())).containsExactly(idOf("<n5>"), idOf("<n3>"), idOf("<n2>"),
                idOf("<n1>"));
    }

    @Test
    void nextUnreadSkipsIgnoredThreadsAndAuthorsAndBeginsAtTheTopAfterAThreadTheListDoesNotShow() {
        article(groupId, 1, "<a>", "Kept", null, DAY);
        int ignoredThreadId = article(groupId, 2, "<b>", "Ignored thread", null, 2 * DAY);
        article(groupId, 3, "<b2>", "Re: Ignored thread", "<b>", 3 * DAY);
        int byIgnoredAuthorId = article(groupId, 4, "<c>", "By an ignored author", null, 4 * DAY);
        database.update(ARTICLE)
                .set(ARTICLE.AUTHOR_KEY, "troll@example.org")
                .where(ARTICLE.ID.eq(byIgnoredAuthorId))
                .execute();
        // Thread A has an unread reply by the ignored author only; it does not count.
        int keptRootId = idOf("<a>");
        int ignoredReplyId = article(groupId, 5, "<a2>", "Re: Kept", "<a>", 5 * DAY);
        database.update(ARTICLE)
                .set(ARTICLE.AUTHOR_KEY, "troll@example.org")
                .where(ARTICLE.ID.eq(ignoredReplyId))
                .execute();
        int otherId = article(groupId, 6, "<d>", "Other", null, 6 * DAY);
        threadAll();
        markRead(keptRootId);
        ignoreRule(IgnoreFilters.THREAD, "<b2>");
        ignoreRule(IgnoreFilters.AUTHOR, "troll@example.org");

        assertThat(walkNextUnread(Map.of())).containsExactly(otherId);
        assertThat(nextUnread(Map.of("after", String.valueOf(ignoredThreadId)))).isEqualTo(otherId);
    }

    @Test
    void nextUnreadFindsNothingWhenAllIsReadAndRejectsInvalidParameters() {
        int readId = article(groupId, 1, "<a>", "Hello", null, DAY);
        long rangeId = range(groupId, 1, 1);
        int unsubscribedGroupId = group("example.unsubscribed");
        threadAll();
        markRead(readId);

        assertThat(browser.get(nextUnreadPath(groupId))).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(browser.get(nextUnreadPath(groupId), Map.of("after", String.valueOf(readId))))
                .hasStatus(HttpStatus.NO_CONTENT);
        assertNextUnreadRejected(Map.of("sort", "oldest"), "sort");
        assertNextUnreadRejected(Map.of("after", "-1"), "after");
        assertNextUnreadRejected(Map.of("until", Instant.ofEpochMilli(0).toString()), "rangeId");
        assertNextUnreadRejected(Map.of("rangeId", String.valueOf(rangeId)), "rangeId");
        assertThat(browser.get(nextUnreadPath(unsubscribedGroupId))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(browser.get(nextUnreadPath(-1))).hasStatus(HttpStatus.NOT_FOUND);
    }

    // Mark as unread (spec 014)

    @Test
    void markingAnArticleAsUnreadRemovesItsReadStateInAllGroupsAndChangesTheUnreadCounts() {
        int rootId = article(groupId, 1, "<a>", "Hello", null, DAY);
        int crosspostedId = article(groupId, 2, "<b>", "Re: Hello", "<a>", 2 * DAY);
        locate(crosspostedId, otherGroupId, 1);
        threadAll();
        markRead(rootId);
        markRead(crosspostedId);
        assertThat(unreadCountOfSubscription(groupId)).isZero();

        assertThat(browser.delete(articleReadPath(crosspostedId))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(readArticleIds()).containsExactly(rootId);
        assertThat(threads(Map.of()).get("items").get(0).get("unreadCount").asInt()).isEqualTo(1);
        assertThat(threads(otherGroupId, Map.of()).get("items").get(0).get("unreadCount").asInt()).isEqualTo(1);
        assertThat(unreadCountOfSubscription(groupId)).isEqualTo(1);
        assertThat(unreadCountOfSubscription(otherGroupId)).isEqualTo(1);
        // An article that is unread stays unread; an unknown article is not found.
        assertThat(browser.delete(articleReadPath(crosspostedId))).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(readArticleIds()).containsExactly(rootId);
        assertThat(browser.delete(articleReadPath(-1))).hasStatus(HttpStatus.NOT_FOUND);
    }

    /** Follows "next unread" from the top of the list to its end, as the key N does without reading. */
    private List<Integer> walkNextUnread(Map<String, String> parameters) {
        List<Integer> threadRootIds = new ArrayList<>();
        Integer after = null;
        while (true) {
            Map<String, String> withAfter = new LinkedHashMap<>(parameters);
            if (after != null) {
                withAfter.put("after", String.valueOf(after));
            }
            MvcTestResult result = browser.get(nextUnreadPath(groupId), withAfter);
            if (result.getResponse().getStatus() == HttpStatus.NO_CONTENT.value()) {
                return threadRootIds;
            }
            after = body(expectOk(result)).get("threadArticleId").asInt();
            assertThat(threadRootIds).doesNotContain(after);
            threadRootIds.add(after);
        }
    }

    private int nextUnread(Map<String, String> parameters) {
        return body(expectOk(browser.get(nextUnreadPath(groupId), parameters))).get("threadArticleId").asInt();
    }

    private void assertNextUnreadRejected(Map<String, String> parameters, String field) {
        MvcTestResult result = browser.get(nextUnreadPath(groupId), parameters);
        assertThat(result).as(parameters.toString()).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(body(result).get("errors").get(0).get("field").asString()).isEqualTo(field);
    }

    private int unreadCountOfSubscription(int ofGroupId) {
        for (JsonNode subscription : body(expectOk(browser.get("/api/subscriptions")))) {
            if (subscription.get("groupId").asInt() == ofGroupId) {
                return subscription.get("unreadCount").asInt();
            }
        }
        throw new AssertionError("No subscription of group " + ofGroupId);
    }

    private void ignoreRule(String kind, String value) {
        database.insertInto(IGNORE_RULE)
                .set(IGNORE_RULE.KIND, kind)
                .set(IGNORE_RULE.VALUE, value)
                .set(IGNORE_RULE.LABEL, value)
                .set(IGNORE_RULE.CREATED_AT, 1L)
                .execute();
    }

    private static String nextUnreadPath(int ofGroupId) {
        return threadsPath(ofGroupId) + "/next-unread";
    }

    private static String articleReadPath(int articleId) {
        return "/api/articles/" + articleId + "/read";
    }

    /**
     * Five threads with the subjects A to E: A from day 5 with a reply on day 6; B from day 7; C from day 1 with
     * replies on days 2 and 8; D without a date; E from day 3 with a reply on day 4.
     */
    private void fiveThreads() {
        article(groupId, 1, "<a>", "A", null, 5 * DAY);
        article(groupId, 2, "<a2>", "Re: A", "<a>", 6 * DAY);
        article(groupId, 3, "<b>", "B", null, 7 * DAY);
        article(groupId, 4, "<c>", "C", null, DAY);
        article(groupId, 5, "<c2>", "Re: C", "<c>", 2 * DAY);
        article(groupId, 6, "<c3>", "Re: C", "<c>", 8 * DAY);
        article(groupId, 7, "<d>", "D", null, null);
        article(groupId, 8, "<e>", "E", null, 3 * DAY);
        article(groupId, 9, "<e2>", "Re: E", "<e>", 4 * DAY);
        threadAll();
    }

    private void threadAll() {
        threadService.recomputeGroup(groupId);
        threadService.recomputeGroup(otherGroupId);
    }

    private JsonNode threads(Map<String, String> parameters) {
        return threads(groupId, parameters);
    }

    private JsonNode threads(int ofGroupId, Map<String, String> parameters) {
        return body(expectOk(browser.get(threadsPath(ofGroupId), parameters)));
    }

    private List<String> cardSubjects(Map<String, String> parameters) {
        return subjects(threads(parameters));
    }

    private static List<String> subjects(JsonNode page) {
        List<String> subjects = new ArrayList<>();
        page.get("items").forEach(item -> subjects.add(item.get("subject").asString()));
        return subjects;
    }

    private static List<Integer> column(JsonNode thread, String name) {
        List<Integer> values = new ArrayList<>();
        thread.get("articles").forEach(article -> values.add(article.get(name).asInt()));
        return values;
    }

    private void assertRejected(Map<String, String> parameters, String field) {
        MvcTestResult result = browser.get(threadsPath(groupId), parameters);
        assertThat(result).as(parameters.toString()).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(body(result).get("errors").get(0).get("field").asString()).isEqualTo(field);
    }

    private static MvcTestResult expectOk(MvcTestResult result) {
        assertThat(result).hasStatusOk();
        return result;
    }

    private static String threadsPath(int ofGroupId) {
        return "/api/groups/" + ofGroupId + "/threads";
    }

    private static String threadPath(int ofGroupId, int articleId) {
        return threadsPath(ofGroupId) + "/" + articleId;
    }

    private static String markReadPath(int ofGroupId, int articleId) {
        return threadPath(ofGroupId, articleId) + "/mark-read";
    }

    private List<Integer> readArticleIds() {
        return database.select(READ_STATE.ARTICLE_ID).from(READ_STATE).fetch(READ_STATE.ARTICLE_ID);
    }

    private void markRead(int articleId) {
        database.insertInto(READ_STATE)
                .set(READ_STATE.ARTICLE_ID, articleId)
                .set(READ_STATE.READ_AT, 1L)
                .execute();
    }

    private long range(int ofGroupId, long firstNumber, long lastNumber) {
        return database.insertInto(SYNCED_RANGE)
                .set(SYNCED_RANGE.NEWSGROUP_ID, ofGroupId)
                .set(SYNCED_RANGE.FIRST_NUMBER, firstNumber)
                .set(SYNCED_RANGE.LAST_NUMBER, lastNumber)
                .returning(SYNCED_RANGE.ID)
                .fetchSingle().getId();
    }

    private int subscribedGroup(String name) {
        int newGroupId = group(name);
        database.insertInto(SUBSCRIPTION).set(SUBSCRIPTION.NEWSGROUP_ID, newGroupId).execute();
        return newGroupId;
    }

    private int group(String name) {
        return database.insertInto(NEWSGROUP)
                .set(NEWSGROUP.SERVER_ID, (int) serverId)
                .set(NEWSGROUP.NAME, name)
                .set(NEWSGROUP.POSTING_STATUS, "ALLOWED")
                .set(NEWSGROUP.LOW_WATERMARK, 0L)
                .set(NEWSGROUP.HIGH_WATERMARK, 0L)
                .returning(NEWSGROUP.ID)
                .fetchSingle().getId();
    }

    /** Stores an article as the synchronization does: located in the group, and without a place in a thread. */
    private int article(int inGroupId, long number, String messageId, String subject, String references, Long date) {
        int articleId = database.insertInto(ARTICLE)
                .set(ARTICLE.MESSAGE_ID, messageId)
                .set(ARTICLE.IS_STUB, false)
                .set(ARTICLE.SUBJECT, subject)
                .set(ARTICLE.FROM, "Ada <ada@example.org>")
                .set(ARTICLE.AUTHOR_KEY, "ada@example.org")
                .set(ARTICLE.REFERENCES, references)
                .set(ARTICLE.DATE, date)
                .returning(ARTICLE.ID)
                .fetchSingle().getId();
        locate(articleId, inGroupId, number);
        return articleId;
    }

    private void locate(int articleId, int inGroupId, long number) {
        database.insertInto(ARTICLE_LOCATION)
                .set(ARTICLE_LOCATION.NEWSGROUP_ID, inGroupId)
                .set(ARTICLE_LOCATION.ARTICLE_NUMBER, number)
                .set(ARTICLE_LOCATION.ARTICLE_ID, articleId)
                .execute();
    }

    private int idOf(String messageId) {
        return database.select(ARTICLE.ID).from(ARTICLE).where(ARTICLE.MESSAGE_ID.eq(messageId))
                .fetchSingle(ARTICLE.ID);
    }
}
