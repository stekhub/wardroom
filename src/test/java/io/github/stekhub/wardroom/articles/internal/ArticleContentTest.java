package io.github.stekhub.wardroom.articles.internal;

import static io.github.stekhub.wardroom.TestInstallation.body;
import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE;
import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE_LOCATION;
import static io.github.stekhub.wardroom.jooq.Tables.NEWSGROUP;
import static io.github.stekhub.wardroom.jooq.Tables.NNTP_SERVER;
import static io.github.stekhub.wardroom.jooq.Tables.READ_STATE;
import static io.github.stekhub.wardroom.jooq.Tables.SUBSCRIPTION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.stekhub.wardroom.IntegrationTest;
import io.github.stekhub.wardroom.TestBrowser;
import io.github.stekhub.wardroom.TestInstallation;
import io.github.stekhub.wardroom.jooq.tables.Article;
import io.github.stekhub.wardroom.jooq.tables.records.ArticleRecord;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import io.github.stekhub.wardroom.nntp.mock.MockReply;
import java.io.IOException;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * Fetching, caching and reading articles (spec 007), against the whole application, a real SQLite database and
 * the mock NNTP server.
 */
@IntegrationTest
class ArticleContentTest {

    private static final long DAY = 86_400_000L;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

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

    @BeforeEach
    void startWithGroup() throws IOException {
        browser = TestInstallation.loggedInOwner(mvc, database);
        mockServer = MockNntpServer.start();
        mockServer.on("CAPABILITIES").reply(MockReply.fixture("capabilities-reader.txt"));
        mockServer.on("ARTICLE").reply("430 No such article");
        serverId = TestInstallation.addServer(browser, mockServer, 2);
        groupId = group("example.talk");
    }

    @AfterEach
    void removeServer() throws IOException {
        assertThat(browser.delete("/api/servers/" + serverId)).hasStatus(HttpStatus.NO_CONTENT);
        mockServer.close();
    }

    // Fetching and caching

    @Test
    void contentIsFetchedOnceAndThenServedFromTheCache() {
        int articleId = article(1, "<a@example.org>", "Hello", null, DAY);
        mockServer.on("ARTICLE <a@example.org>").reply("220 0 <a@example.org>", "Subject: Hello",
                "From: Ada <ada@example.org>", "", "First line", "", "> a quote", ".");

        JsonNode fetched = body(expectOk(browser.get(contentPath(articleId))));
        JsonNode cached = body(expectOk(browser.get(contentPath(articleId))));

        assertThat(fetched.get("articleId").asInt()).isEqualTo(articleId);
        assertThat(fetched.get("body").asString()).isEqualTo("First line\n\n> a quote");
        assertThat(fetched.get("rawHeaders").asString()).isEqualTo("Subject: Hello\r\nFrom: Ada <ada@example.org>");
        assertThat(fetched.get("flowed").asBoolean()).isFalse();
        assertThat(fetched.get("delSp").asBoolean()).isFalse();
        assertThat(fetched.get("truncated").asBoolean()).isFalse();
        assertThat(fetched.get("omittedParts")).isEmpty();
        assertThat(cached).isEqualTo(fetched);
        assertThat(articleCommands()).containsExactly("ARTICLE <a@example.org>");
        assertThat(stored(articleId).getContentFetchedAt()).isNotNull();
    }

    @Test
    void requestsForTheSameArticleAtTheSameTimeCauseOneFetch() throws Exception {
        int articleId = article(1, "<a@example.org>", "Hello", null, DAY);
        CountDownLatch gate = new CountDownLatch(1);
        mockServer.on("ARTICLE <a@example.org>").reply(MockReply.inTwoParts(
                new String[] {"220 0 <a@example.org>", "Subject: Hello", ""}, gate, new String[] {"Text", "."}));

        try (ExecutorService requests = Executors.newFixedThreadPool(3)) {
            List<Future<MvcTestResult>> results = new ArrayList<>();
            for (int request = 0; request < 3; request++) {
                results.add(requests.submit(() -> browser.get(contentPath(articleId))));
            }
            await().atMost(TIMEOUT).until(() -> !articleCommands().isEmpty());
            // The other requests have time to arrive while the first one waits for the rest of the article.
            Thread.sleep(300);
            gate.countDown();

            for (Future<MvcTestResult> result : results) {
                assertThat(body(expectOk(result.get())).get("body").asString()).isEqualTo("Text");
            }
        }
        assertThat(articleCommands()).hasSize(1);
    }

    @Test
    void flowedTextAndItsMarksAreStoredAndReturned() {
        int articleId = article(1, "<a@example.org>", "Hello", null, DAY);
        mockServer.on("ARTICLE <a@example.org>").reply("220 0 <a@example.org>",
                "Content-Type: text/plain; charset=UTF-8; format=flowed; delsp=yes", "", "A long line that ",
                "goes on.", ".");

        expectOk(browser.get(contentPath(articleId)));
        JsonNode cached = body(expectOk(browser.get(contentPath(articleId))));

        assertThat(cached.get("body").asString()).isEqualTo("A long line that \ngoes on.");
        assertThat(cached.get("flowed").asBoolean()).isTrue();
        assertThat(cached.get("delSp").asBoolean()).isTrue();
    }

    @Test
    void anArticleNobodyKnowsIsNotFound() {
        assertThat(browser.get(contentPath(4711))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(browser.post(contentPath(4711) + "/retry")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(browser.put("/api/articles/4711/read")).hasStatus(HttpStatus.NOT_FOUND);
    }

    // Parts and character sets

    @Test
    void ofAMultipartArticleOnlyTheTextIsStoredAndTheOtherPartsAreListed() {
        int articleId = article(1, "<a@example.org>", "Photo", null, DAY);
        mockServer.on("ARTICLE <a@example.org>").reply("220 0 <a@example.org>", "Subject: Photo",
                "Content-Type: multipart/mixed; boundary=\"frontier\"", "", "--frontier",
                "Content-Type: text/plain; charset=UTF-8", "", "The text.", "--frontier",
                "Content-Type: text/html; charset=UTF-8", "", "<p>SECRET-HTML</p>", "--frontier",
                "Content-Type: image/jpeg; name=\"photo.jpg\"", "Content-Transfer-Encoding: base64", "",
                "U0VDUkVULUlNQUdF", "--frontier--", ".");

        expectOk(browser.get(contentPath(articleId)));
        JsonNode content = body(expectOk(browser.get(contentPath(articleId))));

        assertThat(content.get("body").asString()).isEqualTo("The text.");
        assertThat(content.get("omittedParts")).hasSize(2);
        assertThat(content.get("omittedParts").get(0).get("mediaType").asString()).isEqualTo("text/html");
        assertThat(content.get("omittedParts").get(0).get("name").isNull()).isTrue();
        assertThat(content.get("omittedParts").get(1).get("name").asString()).isEqualTo("photo.jpg");
        assertThat(content.get("omittedParts").get(1).get("mediaType").asString()).isEqualTo("image/jpeg");
        assertThat(content.get("omittedParts").get(1).get("size").asLong()).isEqualTo(12);
        ArticleRecord stored = stored(articleId);
        assertThat(stored.getBodyText() + stored.getOmittedParts() + stored.getRawHeaders())
                .doesNotContain("SECRET").doesNotContain("U0VDUkVU");
    }

    @Test
    void anArticleThatIsOnlyHtmlHasNoTextAndListsTheHtml() {
        int articleId = article(1, "<a@example.org>", "Hello", null, DAY);
        mockServer.on("ARTICLE <a@example.org>").reply("220 0 <a@example.org>",
                "Content-Type: text/html; charset=UTF-8", "", "<p>Hello</p>", ".");

        JsonNode content = body(expectOk(browser.get(contentPath(articleId))));

        assertThat(content.get("body").asString()).isEmpty();
        assertThat(content.get("omittedParts")).hasSize(1);
        assertThat(content.get("omittedParts").get(0).get("mediaType").asString()).isEqualTo("text/html");
        assertThat(stored(articleId).getBodyText()).isEmpty();
    }

    @Test
    void textWithoutACharacterSetThatIsNotUtf8IsReadAsWindows1252() {
        int articleId = article(1, "<a@example.org>", "Hello", null, DAY);
        mockServer.on("ARTICLE <a@example.org>").reply(MockReply.raw(
                "220 0 <a@example.org>\r\nSubject: Hello\r\n\r\nGrüße – 5 €\r\n.\r\n"
                        .getBytes(Charset.forName("windows-1252"))));

        JsonNode content = body(expectOk(browser.get(contentPath(articleId))));

        assertThat(content.get("body").asString()).isEqualTo("Grüße – 5 €");
    }

    @Test
    void textLongerThanTheLimitIsCutOffAndMarked() {
        int articleId = article(1, "<a@example.org>", "Long", null, DAY);
        List<String> lines = new ArrayList<>(List.of("220 0 <a@example.org>", "Subject: Long", ""));
        String line = "x".repeat(998);
        for (int number = 0; number < 1_200; number++) {
            lines.add(line);
        }
        lines.add(".");
        mockServer.on("ARTICLE <a@example.org>").reply(lines.toArray(String[]::new));

        JsonNode content = body(expectOk(browser.get(contentPath(articleId))));

        assertThat(content.get("truncated").asBoolean()).isTrue();
        assertThat(content.get("body").asString().length()).isBetween(1_000_000, 1_048_576);
        assertThat(body(expectOk(browser.get(contentPath(articleId)))).get("truncated").asBoolean()).isTrue();
    }

    // Stubs and threads

    @Test
    void aStubIsFilledInFromItsHeadersAndItsThreadIsComputedAgain() {
        article(1, "<c@example.org>", "Re: Hello", "<b@example.org>", 3 * DAY);
        threadService.recomputeGroup(groupId);
        assertThat(structure()).containsEntry("<b@example.org>", "null root <b@example.org> stub");
        mockServer.on("ARTICLE <b@example.org>").reply("220 0 <b@example.org>",
                "Subject: =?UTF-8?Q?Re:_Gr=C3=BC=C3=9Fe?=", "From: Bob <bob@example.org>",
                "Date: Sat, 03 Jan 1970 00:00:00 +0000", "References: <a@example.org>", "", "The missing reply.",
                ".");
        int stubId = idOf("<b@example.org>");

        JsonNode content = body(expectOk(browser.get(contentPath(stubId))));

        assertThat(content.get("body").asString()).isEqualTo("The missing reply.");
        ArticleRecord filled = stored(stubId);
        assertThat(filled.getIsStub()).isFalse();
        assertThat(filled.getSubject()).isEqualTo("Re: Grüße");
        assertThat(filled.getFrom()).isEqualTo("Bob <bob@example.org>");
        assertThat(filled.getDate()).isEqualTo(2 * DAY);
        assertThat(filled.getReferences()).isEqualTo("<a@example.org>");
        assertThat(structure()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "<a@example.org>", "null root <a@example.org> stub",
                "<b@example.org>", "<a@example.org> root <a@example.org>",
                "<c@example.org>", "<b@example.org> root <a@example.org>"));
    }

    @Test
    void theInReplyToOfAnArticleWithoutReferencesDecidesItsPlaceOnceItIsKnown() {
        int originalId = article(1, "<original@example.org>", "Hello", null, DAY);
        article(2, "<other@example.org>", "Something else", null, DAY);
        int replyId = article(3, "<reply@example.org>", "Re: Hello", null, 2 * DAY);
        threadService.recomputeGroup(groupId);
        // Without References, the subject decides.
        assertThat(structure()).containsEntry("<reply@example.org>",
                "<original@example.org> root <original@example.org>");
        mockServer.on("ARTICLE <reply@example.org>").reply("220 0 <reply@example.org>", "Subject: Re: Hello",
                "In-Reply-To: <other@example.org>", "", "Text", ".");
        mockServer.on("ARTICLE <original@example.org>").reply("220 0 <original@example.org>", "Subject: Hello", "",
                "Text", ".");

        expectOk(browser.get(contentPath(replyId)));
        expectOk(browser.get(contentPath(originalId)));

        assertThat(structure()).containsEntry("<reply@example.org>", "<other@example.org> root <other@example.org>")
                .containsEntry("<original@example.org>", "null root <original@example.org>");
        // The subject fallback leaves the article alone from now on.
        threadService.recomputeGroup(groupId);
        assertThat(structure()).containsEntry("<reply@example.org>", "<other@example.org> root <other@example.org>");
    }

    @Test
    void unsubscribingFromAnotherGroupRemovesAFilledStubAndKeepsItsThreadTogether() {
        article(1, "<c@example.org>", "Re: Hello", "<a@example.org> <b@example.org>", 3 * DAY);
        threadService.recomputeGroup(groupId);
        mockServer.on("ARTICLE <b@example.org>").reply("220 0 <b@example.org>", "Subject: Re: Hello",
                "References: <a@example.org>", "", "The missing reply.", ".");
        expectOk(browser.get(contentPath(idOf("<b@example.org>"))));
        int otherGroupId = group("example.other");
        database.insertInto(SUBSCRIPTION).set(SUBSCRIPTION.NEWSGROUP_ID, otherGroupId).execute();

        assertThat(browser.delete("/api/groups/" + otherGroupId + "/subscription"))
                .hasStatus(HttpStatus.NO_CONTENT);

        // An article without a location goes with every unsubscribe cleanup (data model).
        assertThat(structure()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "<a@example.org>", "null root <a@example.org> stub",
                "<b@example.org>", "<a@example.org> root <a@example.org> stub",
                "<c@example.org>", "<b@example.org> root <a@example.org>"));
    }

    // Unavailable articles and failures

    @Test
    void anArticleTheServerNoLongerHasIsGoneAndNotAskedForAgainUntilARetry() {
        int articleId = article(1, "<a@example.org>", "Hello", null, DAY);

        assertThat(browser.get(contentPath(articleId))).hasStatus(HttpStatus.GONE)
                .bodyJson().extractingPath("$.detail").asString().contains("no longer available");
        assertThat(stored(articleId).getContentUnavailableAt()).isNotNull();
        assertThat(browser.get(contentPath(articleId))).hasStatus(HttpStatus.GONE);
        assertThat(articleCommands()).hasSize(1);

        assertThat(browser.post(contentPath(articleId) + "/retry")).hasStatus(HttpStatus.GONE);
        assertThat(articleCommands()).hasSize(2);
        assertThat(stored(articleId).getContentUnavailableAt()).isNotNull();

        mockServer.on("ARTICLE <a@example.org>").reply("220 0 <a@example.org>", "Subject: Hello", "", "Back.", ".");
        JsonNode content = body(expectOk(browser.post(contentPath(articleId) + "/retry")));

        assertThat(content.get("body").asString()).isEqualTo("Back.");
        assertThat(stored(articleId).getContentUnavailableAt()).isNull();
        assertThat(body(expectOk(browser.get(contentPath(articleId)))).get("body").asString()).isEqualTo("Back.");
        assertThat(articleCommands()).hasSize(3);
    }

    @Test
    void aFailureOfTheServerStoresNothingAndTheNextRequestTriesAgain() {
        int articleId = article(1, "<a@example.org>", "Hello", null, DAY);
        mockServer.on("ARTICLE <a@example.org>").once(MockReply.drop())
                .reply("220 0 <a@example.org>", "Subject: Hello", "", "Text", ".");

        assertThat(browser.get(contentPath(articleId))).hasStatus(HttpStatus.BAD_GATEWAY);
        ArticleRecord afterFailure = stored(articleId);
        assertThat(afterFailure.getContentFetchedAt()).isNull();
        assertThat(afterFailure.getContentUnavailableAt()).isNull();
        assertThat(afterFailure.getBodyText()).isNull();

        assertThat(body(expectOk(browser.get(contentPath(articleId)))).get("body").asString()).isEqualTo("Text");
    }

    @Test
    void whileTheCredentialsAreInvalidTheServerIsNotAsked() {
        int articleId = article(1, "<a@example.org>", "Hello", null, DAY);
        database.update(NNTP_SERVER).set(NNTP_SERVER.CREDENTIALS_INVALID, true).execute();

        assertThat(browser.get(contentPath(articleId))).hasStatus(HttpStatus.BAD_GATEWAY)
                .bodyJson().extractingPath("$.detail").asString().contains("server settings");
        assertThat(articleCommands()).isEmpty();
    }

    // Read state

    @Test
    void markingAnArticleAsReadStoresItOnceAndAStubStaysUnread() {
        int articleId = article(1, "<b@example.org>", "Re: Hello", "<a@example.org>", DAY);
        threadService.recomputeGroup(groupId);
        int stubId = idOf("<a@example.org>");

        assertThat(browser.put("/api/articles/" + articleId + "/read")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(browser.put("/api/articles/" + articleId + "/read")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(browser.put("/api/articles/" + stubId + "/read")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(database.select(READ_STATE.ARTICLE_ID).from(READ_STATE).fetch(READ_STATE.ARTICLE_ID))
                .containsExactly(articleId);
    }

    private static MvcTestResult expectOk(MvcTestResult result) {
        assertThat(result).hasStatusOk();
        return result;
    }

    private static String contentPath(int articleId) {
        return "/api/articles/" + articleId + "/content";
    }

    private List<String> articleCommands() {
        return mockServer.commands().stream().filter(command -> command.startsWith("ARTICLE")).toList();
    }

    private ArticleRecord stored(int articleId) {
        return database.selectFrom(ARTICLE).where(ARTICLE.ID.eq(articleId)).fetchSingle();
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
    private int article(long number, String messageId, String subject, String references, Long date) {
        int articleId = database.insertInto(ARTICLE)
                .set(ARTICLE.MESSAGE_ID, messageId)
                .set(ARTICLE.IS_STUB, false)
                .set(ARTICLE.SUBJECT, subject)
                .set(ARTICLE.REFERENCES, references)
                .set(ARTICLE.DATE, date)
                .returning(ARTICLE.ID)
                .fetchSingle().getId();
        database.insertInto(ARTICLE_LOCATION)
                .set(ARTICLE_LOCATION.NEWSGROUP_ID, groupId)
                .set(ARTICLE_LOCATION.ARTICLE_NUMBER, number)
                .set(ARTICLE_LOCATION.ARTICLE_ID, articleId)
                .execute();
        return articleId;
    }

    private int idOf(String messageId) {
        return database.select(ARTICLE.ID).from(ARTICLE).where(ARTICLE.MESSAGE_ID.eq(messageId))
                .fetchSingle(ARTICLE.ID);
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
