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
import io.github.stekhub.wardroom.ignoring.SubjectWords;
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
 * Ignored words in subjects (spec 023) and the rule endpoints for ignored groups (spec 022), against the whole
 * application and a real SQLite database. The articles are stored as the synchronization does and threaded by the
 * application. What the group list makes of an ignored group is tested with the group list.
 */
@IntegrationTest
class SubjectAndGroupRuleTest {

    private static final String ADA = "Ada <ada@example.org>";
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
    boolean serverRemoved;
    int groupId;
    int otherGroupId;

    @BeforeEach
    void startWithGroups() throws IOException {
        browser = TestInstallation.loggedInOwner(mvc, database);
        mockServer = MockNntpServer.start();
        mockServer.on("CAPABILITIES").reply(MockReply.fixture("capabilities-reader.txt"));
        serverId = TestInstallation.addServer(browser, mockServer, 2);
        serverRemoved = false;
        groupId = group("example.talk", true);
        otherGroupId = group("example.other", true);
    }

    @AfterEach
    void removeServer() throws IOException {
        if (!serverRemoved) {
            assertThat(browser.delete("/api/servers/" + serverId)).hasStatus(HttpStatus.NO_CONTENT);
        }
        mockServer.close();
    }

    // Words in subjects: effect

    @Test
    void aThreadWithTheWordInTheSubjectOfItsCardArticleIsHiddenAndNotCounted() {
        article(groupId, 1, "<j>", "Java 25 released", null, DAY);
        article(groupId, 2, "<j2>", "Re: Java 25 released", "<j>", 2 * DAY);
        article(groupId, 3, "<s>", "JavaScript tips", null, 3 * DAY);
        article(groupId, 4, "<q>", "[java] question", null, 4 * DAY);
        threadAll();
        assertThat(unreadCount(groupId)).isEqualTo(4);

        JsonNode rule = ignoreWord("Java", null);

        assertThat(rule.get("kind").asString()).isEqualTo("SUBJECT");
        assertThat(rule.get("label").asString()).isEqualTo("Java");
        assertThat(rule.get("groupId").isNull()).isTrue();
        assertThat(rule.get("groupName").isNull()).isTrue();
        assertThat(cardSubjects(groupId)).containsExactly("JavaScript tips");
        assertThat(unreadCount(groupId)).isEqualTo(1);
    }

    @Test
    void aPhraseMatchesItsWordsInThisOrderOnly() {
        article(groupId, 1, "<a>", "FOR SALE: bicycle", null, DAY);
        article(groupId, 2, "<b>", "Sale for members", null, 2 * DAY);
        threadAll();

        ignoreWord("for sale", null);

        assertThat(cardSubjects(groupId)).containsExactly("Sale for members");
    }

    @Test
    void lettersOutsideAsciiMatchWithoutRegardToCase() {
        article(groupId, 1, "<a>", "ÄPFEL im Angebot", null, DAY);
        article(groupId, 2, "<b>", "Birnen", null, 2 * DAY);
        threadAll();

        ignoreWord("äpfel", null);

        assertThat(cardSubjects(groupId)).containsExactly("Birnen");
    }

    @Test
    void aRuleOfOneGroupAppliesOnlyThere() {
        article(groupId, 1, "<a>", "Weekly FAQ", null, DAY);
        article(otherGroupId, 1, "<b>", "Weekly FAQ", null, DAY);
        threadAll();

        JsonNode rule = ignoreWord("FAQ", groupId);

        assertThat(rule.get("groupId").asInt()).isEqualTo(groupId);
        assertThat(rule.get("groupName").asString()).isEqualTo("example.talk");
        assertThat(cardSubjects(groupId)).isEmpty();
        assertThat(unreadCount(groupId)).isZero();
        assertThat(cardSubjects(otherGroupId)).containsExactly("Weekly FAQ");
        assertThat(unreadCount(otherGroupId)).isEqualTo(1);
    }

    @Test
    void onlyTheCardArticleDecidesNotAReplyWithTheWord() {
        article(groupId, 1, "<a>", "Holiday plans", null, DAY);
        article(groupId, 2, "<b>", "Election (was: Holiday plans)", "<a>", 2 * DAY);
        threadAll();

        ignoreWord("election", null);

        assertThat(cardSubjects(groupId)).containsExactly("Holiday plans");
        assertThat(unreadCount(groupId)).isEqualTo(2);
    }

    @Test
    void nextUnreadSkipsAHiddenThread() {
        article(groupId, 1, "<a>", "Election night", null, 2 * DAY);
        int keptId = article(groupId, 2, "<b>", "Gardening", null, DAY);
        threadAll();
        ignoreWord("election", null);

        MvcTestResult next = browser.get("/api/groups/" + groupId + "/threads/next-unread");

        assertThat(next).hasStatusOk();
        assertThat(body(next).get("threadArticleId").asInt()).isEqualTo(keptId);
    }

    @Test
    void aHiddenThreadOpenedByLinkSaysWhichWordHidesIt() {
        int hiddenId = article(groupId, 1, "<a>", "Election night", null, DAY);
        int shownId = article(groupId, 2, "<b>", "Gardening", null, 2 * DAY);
        threadAll();
        ignoreWord("  Election ", null);

        assertThat(thread(hiddenId).get("ignoredWord").asString()).isEqualTo("Election");
        assertThat(thread(hiddenId).get("ignored").asBoolean()).isFalse();
        assertThat(thread(shownId).get("ignoredWord").isNull()).isTrue();
    }

    @Test
    void removingTheRuleRestoresListsAndCountsAndNoReadStateWasWritten() {
        article(groupId, 1, "<a>", "Election night", null, DAY);
        threadAll();
        JsonNode rule = ignoreWord("election", null);
        assertThat(cardSubjects(groupId)).isEmpty();

        assertThat(browser.delete("/api/ignore-rules/" + rule.get("id").asInt())).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(cardSubjects(groupId)).containsExactly("Election night");
        assertThat(unreadCount(groupId)).isEqualTo(1);
        assertThat(database.fetchCount(READ_STATE)).isZero();
    }

    // Words in subjects: rules

    @Test
    void aWordThatIsIgnoredAlreadyForTheSameGroupsKeepsTheExistingRule() {
        JsonNode first = ignoreWord("Re-Post", null);
        JsonNode sameNormalized = ignoreWord("re post", null);
        JsonNode forOneGroup = ignoreWord("re post", groupId);
        JsonNode forOneGroupAgain = ignoreWord("RE POST", groupId);

        assertThat(sameNormalized).isEqualTo(first);
        assertThat(forOneGroup.get("id").asInt()).isNotEqualTo(first.get("id").asInt());
        assertThat(forOneGroupAgain).isEqualTo(forOneGroup);
        assertThat(database.fetchCount(IGNORE_RULE)).isEqualTo(2);
        assertThat(database.select(IGNORE_RULE.VALUE).from(IGNORE_RULE).fetchSet(IGNORE_RULE.VALUE))
                .containsExactly("re post");
    }

    @Test
    void aTextThatBreaksARuleIsRejectedWithItsField() {
        assertRejected(wordRequest("", null), "text");
        assertRejected(wordRequest("   ", null), "text");
        assertRejected(wordRequest("?!", null), "text");
        assertRejected(wordRequest("x".repeat(101), null), "text");
        assertRejected("{\"kind\":\"SUBJECT\"}", "text");
        assertThat(browser.post("/api/ignore-rules", wordRequest("x".repeat(100), null)))
                .hasStatus(HttpStatus.CREATED);
        assertThat(database.fetchCount(IGNORE_RULE)).isEqualTo(1);
    }

    @Test
    void theGroupOfAWordMustExistAndBeSubscribed() {
        int notSubscribedId = group("example.unread", false);

        assertRejected(wordRequest("faq", notSubscribedId), "groupId");
        assertThat(browser.post("/api/ignore-rules", wordRequest("faq", 987_654)))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(database.fetchCount(IGNORE_RULE)).isZero();
    }

    @Test
    void aRuleOfOneGroupSurvivesAnUnsubscribeAndIsDeletedWithTheServer() {
        ignoreWord("faq", groupId);
        ignoreWord("election", null);

        assertThat(browser.delete("/api/groups/" + groupId + "/subscription")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(database.fetchCount(IGNORE_RULE)).isEqualTo(2);

        assertThat(browser.delete("/api/servers/" + serverId)).hasStatus(HttpStatus.NO_CONTENT);
        serverRemoved = true;
        assertThat(database.select(IGNORE_RULE.LABEL).from(IGNORE_RULE).fetch(IGNORE_RULE.LABEL))
                .as("the rule for all groups stays")
                .containsExactly("election");
    }

    // Ignored groups: rules

    @Test
    void aGroupThatIsNotSubscribedCanBeIgnoredAndTheRuleHasItsName() {
        int notSubscribedId = group("example.noise", false);

        JsonNode rule = ignoreGroup(notSubscribedId);
        JsonNode again = ignoreGroup(notSubscribedId);

        assertThat(rule.get("kind").asString()).isEqualTo("GROUP");
        assertThat(rule.get("label").asString()).isEqualTo("example.noise");
        assertThat(again).isEqualTo(rule);
        assertThat(body(browser.get("/api/ignore-rules")).get(0)).isEqualTo(rule);
    }

    @Test
    void aSubscribedGroupAndAnUnknownGroupCannotBeIgnored() {
        assertRejected("{\"kind\":\"GROUP\",\"groupId\":" + groupId + "}", "groupId");
        assertRejected("{\"kind\":\"GROUP\"}", "groupId");
        assertThat(browser.post("/api/ignore-rules", "{\"kind\":\"GROUP\",\"groupId\":987654}"))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(database.fetchCount(IGNORE_RULE)).isZero();
    }

    private void assertRejected(String request, String field) {
        assertThat(browser.post("/api/ignore-rules", request))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.errors[0].field")
                .isEqualTo(field);
    }

    private JsonNode ignoreWord(String text, Integer ofGroupId) {
        MvcTestResult result = browser.post("/api/ignore-rules", wordRequest(text, ofGroupId));
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return body(result);
    }

    private static String wordRequest(String text, Integer ofGroupId) {
        return "{\"kind\":\"SUBJECT\",\"text\":\"" + text + "\",\"groupId\":" + ofGroupId + "}";
    }

    private JsonNode ignoreGroup(int ofGroupId) {
        MvcTestResult result = browser.post("/api/ignore-rules", "{\"kind\":\"GROUP\",\"groupId\":" + ofGroupId + "}");
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return body(result);
    }

    private void threadAll() {
        threads.recomputeGroup(groupId);
        threads.recomputeGroup(otherGroupId);
    }

    private JsonNode thread(int articleId) {
        return body(expectOk(browser.get("/api/groups/" + groupId + "/threads/" + articleId)));
    }

    /** The subjects of the cards of the group, by latest activity, the newest first. */
    private List<String> cardSubjects(int ofGroupId) {
        List<String> subjects = new ArrayList<>();
        body(expectOk(browser.get("/api/groups/" + ofGroupId + "/threads"))).get("items")
                .forEach(item -> subjects.add(item.get("subject").asString()));
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

    private int group(String name, boolean subscribed) {
        int newGroupId = database.insertInto(NEWSGROUP)
                .set(NEWSGROUP.SERVER_ID, (int) serverId)
                .set(NEWSGROUP.NAME, name)
                .set(NEWSGROUP.POSTING_STATUS, "ALLOWED")
                .set(NEWSGROUP.LOW_WATERMARK, 0L)
                .set(NEWSGROUP.HIGH_WATERMARK, 0L)
                .returning(NEWSGROUP.ID)
                .fetchSingle().getId();
        if (subscribed) {
            database.insertInto(SUBSCRIPTION).set(SUBSCRIPTION.NEWSGROUP_ID, newGroupId).execute();
        }
        return newGroupId;
    }

    /** Stores an article as the synchronization does: located in the group, and without a place in a thread. */
    private int article(int inGroupId, long number, String messageId, String subject, String references, Long date) {
        int articleId = database.insertInto(ARTICLE)
                .set(ARTICLE.MESSAGE_ID, messageId)
                .set(ARTICLE.IS_STUB, false)
                .set(ARTICLE.SUBJECT, subject)
                .set(ARTICLE.SUBJECT_WORDS, SubjectWords.of(subject))
                .set(ARTICLE.FROM, ADA)
                .set(ARTICLE.AUTHOR_KEY, AuthorKey.of(ADA))
                .set(ARTICLE.REFERENCES, references)
                .set(ARTICLE.DATE, date)
                .returning(ARTICLE.ID)
                .fetchSingle().getId();
        database.insertInto(ARTICLE_LOCATION)
                .set(ARTICLE_LOCATION.NEWSGROUP_ID, inGroupId)
                .set(ARTICLE_LOCATION.ARTICLE_NUMBER, number)
                .set(ARTICLE_LOCATION.ARTICLE_ID, articleId)
                .execute();
        return articleId;
    }
}
