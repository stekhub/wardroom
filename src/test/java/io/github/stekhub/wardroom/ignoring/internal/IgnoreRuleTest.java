package io.github.stekhub.wardroom.ignoring.internal;

import static io.github.stekhub.wardroom.TestInstallation.body;
import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE;
import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE_LOCATION;
import static io.github.stekhub.wardroom.jooq.Tables.IGNORE_RULE;
import static io.github.stekhub.wardroom.jooq.Tables.NEWSGROUP;
import static io.github.stekhub.wardroom.jooq.Tables.READ_STATE;
import static io.github.stekhub.wardroom.jooq.Tables.SUBSCRIPTION;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.IntegrationTest;
import io.github.stekhub.wardroom.TestBrowser;
import io.github.stekhub.wardroom.TestInstallation;
import io.github.stekhub.wardroom.articles.AuthorKey;
import io.github.stekhub.wardroom.articles.Threads;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import io.github.stekhub.wardroom.nntp.mock.MockReply;
import java.io.IOException;
import java.util.ArrayList;
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
 * Ignoring authors and threads (spec 010), against the whole application and a real SQLite database: the rule
 * endpoints, and what the thread list, the thread page and the unread counts make of the rules. The articles are
 * stored as the synchronization does and threaded by the application.
 */
@IntegrationTest
class IgnoreRuleTest {

    private static final String ADA = "Ada <ada@example.org>";
    private static final String BOB = "\"Bob B.\" <Bob@Example.org>";
    private static final long DAY = 86_400_000L;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DSLContext database;

    @Autowired
    Threads threads;

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

    // Ignored threads

    @Test
    void anIgnoredThreadIsLeftOutOfTheListAndTheUnreadCountAlsoAfterNewRepliesAndARootChange() {
        // The root of the thread is a stub: <a> is referenced and not stored.
        int replyId = article(groupId, 1, "<b>", "Re: Ignore me", "<a>", DAY, ADA);
        article(groupId, 2, "<k>", "Keep me", null, 2 * DAY, ADA);
        threadAll();
        assertThat(cardSubjects()).containsExactly("Keep me", "Re: Ignore me");
        assertThat(unreadCount(groupId)).isEqualTo(2);

        JsonNode rule = ignore("THREAD", replyId);

        assertThat(rule.get("kind").asString()).isEqualTo("THREAD");
        // The root has no subject; the article the owner chose stands in.
        assertThat(rule.get("label").asString()).isEqualTo("Re: Ignore me");
        assertThat(cardSubjects()).containsExactly("Keep me");
        assertThat(unreadCount(groupId)).isEqualTo(1);

        // A new reply arrives.
        article(groupId, 3, "<c>", "Re: Ignore me", "<a> <b>", 3 * DAY, ADA);
        threadAll();
        assertThat(cardSubjects()).containsExactly("Keep me");
        assertThat(unreadCount(groupId)).isEqualTo(1);

        // The stub is filled in, and its own References give the thread a new root.
        int formerRootId = idOf("<a>");
        database.update(ARTICLE)
                .set(ARTICLE.IS_STUB, false)
                .setNull(ARTICLE.THREAD_ROOT_ID)
                .set(ARTICLE.SUBJECT, "Re: Ignore me")
                .set(ARTICLE.FROM, ADA)
                .set(ARTICLE.AUTHOR_KEY, AuthorKey.of(ADA))
                .set(ARTICLE.REFERENCES, "<origin>")
                .set(ARTICLE.DATE, DAY / 2)
                .where(ARTICLE.ID.eq(formerRootId))
                .execute();
        locate(formerRootId, groupId, 4);
        threadAll();
        assertThat(thread(replyId).get("rootId").asInt()).isEqualTo(idOf("<origin>"));

        assertThat(cardSubjects()).containsExactly("Keep me");
        assertThat(unreadCount(groupId)).isEqualTo(1);
        JsonNode ignoredThread = thread(replyId);
        assertThat(ignoredThread.get("ignored").asBoolean()).isTrue();
        assertThat(ignoredThread.get("ignoreRuleIds")).hasSize(1);
        assertThat(ignoredThread.get("ignoreRuleIds").get(0).asLong()).isEqualTo(rule.get("id").asLong());
        assertThat(ignoredThread.get("articles")).hasSize(4);
    }

    @Test
    void aThreadThatIsNotIgnoredSaysSo() {
        int articleId = article(groupId, 1, "<a>", "Hello", null, DAY, ADA);
        threadAll();

        JsonNode shownThread = thread(articleId);

        assertThat(shownThread.get("ignored").asBoolean()).isFalse();
        assertThat(shownThread.get("ignoreRuleIds")).isEmpty();
        assertThat(shownThread.get("articles").get(0).get("ignoredAuthor").asBoolean()).isFalse();
    }

    // Ignored authors

    @Test
    void aThreadWhoseCardArticleIsByAnIgnoredAuthorIsLeftOutAndNoneOfItsArticlesCountAsUnread() {
        int bobsStartId = article(groupId, 1, "<a>", "By Bob", null, DAY, BOB);
        article(groupId, 2, "<a2>", "Re: By Bob", "<a>", 2 * DAY, ADA);
        article(groupId, 3, "<k>", "By Ada", null, 3 * DAY, ADA);
        // In the other group the card article of the same thread is the reply by Ada: the root is not located there.
        int crosspostedReplyId = article(otherGroupId, 1, "<a3>", "Re: By Bob", "<a>", 4 * DAY, ADA);
        threadAll();
        assertThat(unreadCount(groupId)).isEqualTo(3);

        JsonNode rule = ignore("AUTHOR", bobsStartId);

        assertThat(rule.get("kind").asString()).isEqualTo("AUTHOR");
        assertThat(rule.get("label").asString()).isEqualTo("Bob B.");
        assertThat(cardSubjects()).containsExactly("By Ada");
        assertThat(unreadCount(groupId)).isEqualTo(1);
        assertThat(subjects(threads(otherGroupId))).containsExactly("Re: By Bob");
        assertThat(unreadCount(otherGroupId)).isEqualTo(1);
        assertThat(thread(otherGroupId, crosspostedReplyId).get("ignored").asBoolean()).isFalse();
    }

    @Test
    void articlesByAnIgnoredAuthorDoNotCountAsUnreadAndAreFlaggedInTheThread() {
        int startId = article(groupId, 1, "<a>", "Hello", null, DAY, ADA);
        int bobsReplyId = article(groupId, 2, "<b>", "Re: Hello", "<a>", 2 * DAY, BOB);
        article(groupId, 3, "<c>", "Re: Hello", "<a> <b>", 3 * DAY, ADA);
        // The same author with another display name and another spelling of the address.
        article(groupId, 4, "<d>", "Re: Hello", "<a>", 4 * DAY, "bob@EXAMPLE.org (Robert)");
        threadAll();

        ignore("AUTHOR", bobsReplyId);

        JsonNode card = threads(groupId).get("items").get(0);
        assertThat(card.get("messageCount").asInt()).isEqualTo(4);
        assertThat(card.get("unreadCount").asInt()).isEqualTo(2);
        assertThat(unreadCount(groupId)).isEqualTo(2);
        List<Boolean> ignoredAuthors = new ArrayList<>();
        thread(startId).get("articles").forEach(entry -> ignoredAuthors.add(entry.get("ignoredAuthor").asBoolean()));
        // In display order: the start, the reply by Bob, the reply to it by Ada, the second reply by Bob.
        assertThat(ignoredAuthors).containsExactly(false, true, false, true);
    }

    // A filter, not a change

    @Test
    void removingARuleRestoresListsAndCountsAndNoReadStateWasWritten() {
        int bobsStartId = article(groupId, 1, "<a>", "By Bob", null, DAY, BOB);
        int ignoredThreadId = article(groupId, 2, "<t>", "Ignored thread", null, 2 * DAY, ADA);
        article(groupId, 3, "<k>", "Kept", null, 3 * DAY, ADA);
        threadAll();
        long authorRuleId = ignore("AUTHOR", bobsStartId).get("id").asLong();
        long threadRuleId = ignore("THREAD", ignoredThreadId).get("id").asLong();
        assertThat(cardSubjects()).containsExactly("Kept");
        assertThat(unreadCount(groupId)).isEqualTo(1);

        assertThat(browser.delete("/api/ignore-rules/" + authorRuleId)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(browser.delete("/api/ignore-rules/" + threadRuleId)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(cardSubjects()).containsExactly("Kept", "Ignored thread", "By Bob");
        assertThat(unreadCount(groupId)).isEqualTo(3);
        assertThat(database.fetchCount(READ_STATE)).isZero();
        assertThat(rules()).isEmpty();
        // A rule that is removed already.
        assertThat(browser.delete("/api/ignore-rules/" + authorRuleId)).hasStatus(HttpStatus.NO_CONTENT);
    }

    // Rules

    @Test
    void creatingARuleThatExistsReturnsItUnchanged() {
        int bobsStartId = article(groupId, 1, "<a>", "First subject", null, DAY, BOB);
        int bobsReplyId = article(groupId, 2, "<b>", "Re: First subject", "<a>", 2 * DAY, "Robert <bob@example.org>");
        threadAll();
        JsonNode authorRule = ignore("AUTHOR", bobsStartId);
        JsonNode threadRule = ignore("THREAD", bobsStartId);

        // The same author by another article with another display name; the same thread by another article.
        JsonNode sameAuthorRule = ignore("AUTHOR", bobsReplyId);
        JsonNode sameThreadRule = ignore("THREAD", bobsReplyId);

        assertThat(sameAuthorRule).isEqualTo(authorRule);
        assertThat(sameThreadRule).isEqualTo(threadRule);
        assertThat(threadRule.get("label").asString()).isEqualTo("First subject");
        assertThat(database.fetchCount(IGNORE_RULE)).isEqualTo(2);
    }

    @Test
    void theRulesAreListedNewestFirst() {
        int bobsStartId = article(groupId, 1, "<a>", "Hello", null, DAY, BOB);
        int addressOnlyId = article(groupId, 2, "<b>", "Other", null, 2 * DAY, "carol@example.org");
        threadAll();
        ignore("AUTHOR", bobsStartId);
        ignore("THREAD", bobsStartId);
        ignore("AUTHOR", addressOnlyId);
        database.update(IGNORE_RULE).set(IGNORE_RULE.CREATED_AT, IGNORE_RULE.ID.cast(Long.class)).execute();

        JsonNode rules = rules();

        assertThat(rules).hasSize(3);
        assertThat(rules.get(0).get("label").asString()).isEqualTo("carol@example.org");
        assertThat(rules.get(0).get("kind").asString()).isEqualTo("AUTHOR");
        assertThat(rules.get(1).get("label").asString()).isEqualTo("Hello");
        assertThat(rules.get(1).get("kind").asString()).isEqualTo("THREAD");
        assertThat(rules.get(2).get("label").asString()).isEqualTo("Bob B.");
        assertThat(rules.get(2).get("createdAt").asLong()).isEqualTo(rules.get(2).get("id").asLong());
    }

    @Test
    void theAuthorOfAStubCannotBeIgnoredButItsThreadCan() {
        article(groupId, 1, "<b>", "Re: Lost start", "<a>", DAY, ADA);
        threadAll();
        int stubId = idOf("<a>");

        MvcTestResult refused = browser.post("/api/ignore-rules", request("AUTHOR", stubId));
        assertThat(refused).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(body(refused).get("errors").get(0).get("field").asString()).isEqualTo("articleId");

        ignore("THREAD", stubId);
        assertThat(cardSubjects()).isEmpty();
    }

    @Test
    void anUnknownArticleAndAnInvalidRequestAreRejected() {
        assertThat(browser.post("/api/ignore-rules", request("AUTHOR", 987_654))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(browser.post("/api/ignore-rules", "{\"kind\":\"SUBJECT\",\"articleId\":1}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(browser.post("/api/ignore-rules", "{\"kind\":\"AUTHOR\"}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(browser.post("/api/ignore-rules", "{\"articleId\":1}")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(database.fetchCount(IGNORE_RULE)).isZero();
    }

    private JsonNode ignore(String kind, int articleId) {
        MvcTestResult result = browser.post("/api/ignore-rules", request(kind, articleId));
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return body(result);
    }

    private static String request(String kind, int articleId) {
        return "{\"kind\":\"" + kind + "\",\"articleId\":" + articleId + "}";
    }

    private JsonNode rules() {
        return body(expectOk(browser.get("/api/ignore-rules")));
    }

    private void threadAll() {
        threads.recomputeGroup(groupId);
        threads.recomputeGroup(otherGroupId);
    }

    private JsonNode threads(int ofGroupId) {
        return body(expectOk(browser.get("/api/groups/" + ofGroupId + "/threads")));
    }

    private JsonNode thread(int articleId) {
        return thread(groupId, articleId);
    }

    private JsonNode thread(int ofGroupId, int articleId) {
        return body(expectOk(browser.get("/api/groups/" + ofGroupId + "/threads/" + articleId)));
    }

    /** The subjects of the cards of the group, by latest activity, the newest first. */
    private List<String> cardSubjects() {
        return subjects(threads(groupId));
    }

    private static List<String> subjects(JsonNode page) {
        List<String> subjects = new ArrayList<>();
        page.get("items").forEach(item -> subjects.add(item.get("subject").asString()));
        return subjects;
    }

    private int unreadCount(int ofGroupId) {
        for (JsonNode subscription : body(expectOk(browser.get("/api/subscriptions")))) {
            if (subscription.get("groupId").asInt() == ofGroupId) {
                return subscription.get("unreadCount").asInt();
            }
        }
        throw new AssertionError("The group is not subscribed");
    }

    private static MvcTestResult expectOk(MvcTestResult result) {
        assertThat(result).hasStatusOk();
        return result;
    }

    private int subscribedGroup(String name) {
        int newGroupId = database.insertInto(NEWSGROUP)
                .set(NEWSGROUP.SERVER_ID, (int) serverId)
                .set(NEWSGROUP.NAME, name)
                .set(NEWSGROUP.POSTING_STATUS, "ALLOWED")
                .set(NEWSGROUP.LOW_WATERMARK, 0L)
                .set(NEWSGROUP.HIGH_WATERMARK, 0L)
                .returning(NEWSGROUP.ID)
                .fetchSingle().getId();
        database.insertInto(SUBSCRIPTION).set(SUBSCRIPTION.NEWSGROUP_ID, newGroupId).execute();
        return newGroupId;
    }

    /** Stores an article as the synchronization does: located in the group, and without a place in a thread. */
    private int article(int inGroupId, long number, String messageId, String subject, String references, Long date,
            String from) {
        int articleId = database.insertInto(ARTICLE)
                .set(ARTICLE.MESSAGE_ID, messageId)
                .set(ARTICLE.IS_STUB, false)
                .set(ARTICLE.SUBJECT, subject)
                .set(ARTICLE.FROM, from)
                .set(ARTICLE.AUTHOR_KEY, AuthorKey.of(from))
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
