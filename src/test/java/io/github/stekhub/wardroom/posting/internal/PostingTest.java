package io.github.stekhub.wardroom.posting.internal;

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
import io.github.stekhub.wardroom.articles.Threads;
import io.github.stekhub.wardroom.jooq.tables.records.ArticleRecord;
import io.github.stekhub.wardroom.nntp.NntpHeaderText;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import io.github.stekhub.wardroom.nntp.mock.MockReply;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Posting (spec 009), against the whole application, a real SQLite database and the mock NNTP server: the posting
 * identity, the article that is sent, what the answers of the server lead to, and the stored post.
 */
@IntegrationTest
class PostingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final MockReply ACCEPTED = MockReply.article("340 Send the article", "240 Article received OK");
    private static final String PARENT_HEADERS = "Subject: Hello\r\nFrom: Ada <ada@example.org>\r\n"
            + "Message-ID: <parent@example.org>\r\n";

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

    @BeforeEach
    void startWithGroupAndIdentity() throws IOException {
        browser = TestInstallation.loggedInOwner(mvc, database);
        mockServer = MockNntpServer.start();
        mockServer.on("CAPABILITIES").reply(MockReply.fixture("capabilities-reader.txt"));
        mockServer.on("LIST OVERVIEW.FMT").reply("215 Order of fields in overview database.", "Subject:", "From:",
                "Date:", "Message-ID:", "References:", ":bytes", ":lines", "Xref:full", ".");
        mockServer.on("POST").reply(ACCEPTED);
        serverId = TestInstallation.addServer(browser, mockServer, 2);
        groupId = subscribedGroup("example.talk", "ALLOWED");
        storeIdentity("Jane Doe", "jane@news.example.org", "Jane\nhttps://example.org/jane");
    }

    @AfterEach
    void removeServer() throws IOException {
        assertThat(browser.delete("/api/servers/" + serverId)).hasStatus(HttpStatus.NO_CONTENT);
        mockServer.close();
    }

    // Posting identity

    @Test
    void aServerWithoutAnIdentityReturnsEmptyFieldsAndAStoredIdentityIsReturned() {
        database.update(NNTP_SERVER).setNull(NNTP_SERVER.POSTING_NAME).setNull(NNTP_SERVER.POSTING_EMAIL)
                .setNull(NNTP_SERVER.POSTING_SIGNATURE).execute();

        JsonNode empty = body(expectOk(browser.get(identityPath())));
        assertThat(empty.get("name").isNull()).isTrue();
        assertThat(empty.get("email").isNull()).isTrue();
        assertThat(empty.get("signature").isNull()).isTrue();

        JsonNode stored = body(expectOk(browser.put(identityPath(),
                identity("  Jürgen Müller ", "j.mueller+news@example.invalid", "  \n "))));
        assertThat(stored.get("name").asString()).isEqualTo("Jürgen Müller");
        assertThat(stored.get("email").asString()).isEqualTo("j.mueller+news@example.invalid");
        // A signature without text is none.
        assertThat(stored.get("signature").isNull()).isTrue();
        assertThat(body(expectOk(browser.get(identityPath())))).isEqualTo(stored);
    }

    @Test
    void anIdentityNeedsANameAndAnEmailAddressOfTheFormLocalAtDomain() {
        assertIdentityRejected(identity(" ", "jane@example.org", null), "name");
        assertIdentityRejected(identity(null, "jane@example.org", null), "name");
        assertIdentityRejected(identity("x".repeat(101), "jane@example.org", null), "name");
        for (String email : List.of("", "jane", "jane@", "@example.org", "jane doe@example.org",
                "Jane <jane@example.org>", "jane@exa mple.org", "jane@example.org>")) {
            assertIdentityRejected(identity("Jane", email, null), "email");
        }
        assertIdentityRejected(identity("Jane", null, null), "email");
        assertIdentityRejected(identity("Jane", "jane@example.org", "x".repeat(2001)), "signature");

        assertThat(browser.get("/api/servers/987654/identity")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(browser.put("/api/servers/987654/identity", identity("Jane", "jane@example.org", null)))
                .hasStatus(HttpStatus.NOT_FOUND);
        // Nothing of this changed the identity.
        assertThat(body(browser.get(identityPath())).get("name").asString()).isEqualTo("Jane Doe");
    }

    @Test
    void controlCharactersAreRemovedFromTheNameAndTheEmailAddressAndTheSignatureKeepsItsLines() {
        JsonNode stored = body(expectOk(browser.put(identityPath(), identity("Jane\r\nBcc: x@example.org\tDoe",
                "jane@example.org\r\n", "First\r\nSecond\u0007\rThird\n\n"))));

        assertThat(stored.get("name").asString()).isEqualTo("JaneBcc: x@example.orgDoe");
        assertThat(stored.get("email").asString()).isEqualTo("jane@example.org");
        assertThat(stored.get("signature").asString()).isEqualTo("First\nSecond\nThird");
    }

    // The article that is sent

    @Test
    void aNewDiscussionIsSentToTheGroupWithTheHeadersOfTheSpecificationAndStoredAsSentAndRead() {
        JsonNode posted = post(draft(null, "Hello 🙂 everyone", "First line\n.second line starts with a dot",
                true, null));

        String article = sentArticle();
        String headers = article.substring(0, article.indexOf("\r\n\r\n") + 2);
        String messageId = posted.get("messageId").asString();
        assertThat(messageId).matches("<[A-Za-z0-9]{20}@news\\.example\\.org>");
        assertThat(headers).isASCII();
        assertThat(headers).contains("From: Jane Doe <jane@news.example.org>\r\n")
                .contains("Newsgroups: example.talk\r\n")
                .contains("Message-ID: " + messageId + "\r\n")
                .contains("MIME-Version: 1.0\r\n")
                .contains("Content-Type: text/plain; charset=UTF-8; format=flowed\r\n")
                .contains("Content-Transfer-Encoding: 8bit\r\n")
                .contains("User-Agent: Wardroom/")
                .doesNotContain("References:");
        assertThat(headers).containsPattern("Date: (Mon|Tue|Wed|Thu|Fri|Sat|Sun), \\d{2} [A-Z][a-z]{2} \\d{4} "
                + "\\d{2}:\\d{2}:\\d{2} \\+0000\r\n");
        assertThat(NntpHeaderText.decode(HeaderBlock.value(headers, "Subject"))).isEqualTo("Hello 🙂 everyone");
        // The mock removes nothing: the dot at the start of a line arrives doubled, lines end with CRLF.
        assertThat(article).endsWith("\r\n\r\nFirst line\r\n..second line starts with a dot\r\n\r\n-- \r\n"
                + "Jane\r\nhttps://example.org/jane\r\n");

        ArticleRecord stored = storedArticle(messageId);
        assertThat(posted.get("articleId").asInt()).isEqualTo(stored.getId());
        assertThat(posted.get("rootId").asInt()).isEqualTo(stored.getId());
        assertThat(stored.getIsStub()).isFalse();
        assertThat(stored.getSubject()).isEqualTo("Hello 🙂 everyone");
        assertThat(stored.getFrom()).isEqualTo("Jane Doe <jane@news.example.org>");
        assertThat(stored.getAuthorKey()).isEqualTo("jane@news.example.org");
        assertThat(stored.getDate()).isCloseTo(System.currentTimeMillis(), org.assertj.core.data.Offset.offset(60_000L));
        assertThat(stored.getReferences()).isNull();
        assertThat(stored.getThreadRootId()).isEqualTo(stored.getId());
        assertThat(stored.getPostedFromNewsgroupId()).isEqualTo(groupId);
        assertThat(stored.getBodyFlowed()).isTrue();
        assertThat(stored.getBodyText()).startsWith("First line\n.second line starts with a dot\n");
        assertThat(stored.getRawHeaders()).isEqualTo(headers);
        assertThat(database.fetchCount(READ_STATE, READ_STATE.ARTICLE_ID.eq(stored.getId()))).isEqualTo(1);

        // The thread page shows the discussion although the server does not list the article yet.
        JsonNode thread = body(expectOk(browser.get(threadPath(stored.getId()))));
        assertThat(thread.get("articles")).hasSize(1);
        assertThat(thread.get("articles").get(0).get("sent").asBoolean()).isTrue();
        assertThat(thread.get("articles").get(0).get("read").asBoolean()).isTrue();
        JsonNode content = body(expectOk(browser.get("/api/articles/" + stored.getId() + "/content")));
        assertThat(content.get("body").asString()).startsWith("First line\n.second line");
        // It is no card and counts nowhere until it has its number in the group.
        assertThat(body(expectOk(browser.get(threadsPath()))).get("items")).isEmpty();
        assertThat(unreadCount()).isZero();
    }

    @Test
    void aNameWithOtherThanAsciiIsEncodedAndTheSignatureCanBeLeftOut() {
        storeIdentity("Jürgen Müller", "j@example.org", "Signature");

        post(draft(null, "Hello", "Text", false, null));

        String article = sentArticle();
        assertThat(article).contains("From: =?").endsWith("\r\n\r\nText\r\n");
        assertThat(NntpHeaderText.decode(HeaderBlock.value(article.substring(0, article.indexOf("\r\n\r\n")), "From")))
                .isEqualTo("Jürgen Müller <j@example.org>");
    }

    @Test
    void theTextIsSentFlowedWithLongLinesBrokenAndQuotedLinesAsTheyAre() {
        String longLine = "word ".repeat(30).strip();
        String quoted = "> " + "quoted ".repeat(20).strip();

        post(draft(null, "Hello", "Ada wrote:\n" + quoted + "\n\n" + longLine + "   \n from here 🙂", false, null));

        String article = sentArticle();
        List<String> bodyLines = List.of(article.substring(article.indexOf("\r\n\r\n") + 4).split("\r\n"));
        assertThat(bodyLines.get(0)).isEqualTo("Ada wrote:");
        assertThat(bodyLines.get(1)).isEqualTo(quoted);
        assertThat(bodyLines.get(2)).isEmpty();
        assertThat(bodyLines.subList(3, 6)).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(72));
        assertThat(bodyLines.get(3)).endsWith(" ");
        assertThat(bodyLines.get(4)).endsWith(" ");
        assertThat(String.join("", bodyLines.subList(3, 6))).isEqualTo(longLine);
        // A line that starts with a space is space-stuffed.
        assertThat(bodyLines.get(6)).isEqualTo("  from here 🙂");
    }

    // Replies

    @Test
    void aReplyHasTheReferencesOfItsParentIsThreadedBelowItAndGoesToItsGroups() {
        int parentId = article("<parent@example.org>", "<root@example.org> <middle@example.org>",
                "Subject: Hello\r\nNewsgroups: example.talk, bad name,example.other\r\n", 1);
        threads.recomputeGroup(groupId);

        JsonNode posted = post(draft(parentId, "Re: Hello", "Ada wrote:\n> Hello\n\nMy answer", false, null));

        String article = sentArticle();
        assertThat(article).contains("References: <root@example.org> <middle@example.org> <parent@example.org>\r\n")
                .contains("Newsgroups: example.talk,example.other\r\n");
        ArticleRecord stored = storedArticle(posted.get("messageId").asString());
        assertThat(stored.getParentId()).isEqualTo(parentId);
        assertThat(stored.getReferences())
                .isEqualTo("<root@example.org> <middle@example.org> <parent@example.org>");
        assertThat(posted.get("rootId").asInt()).isEqualTo(idOf("<root@example.org>"));
        JsonNode thread = body(expectOk(browser.get(threadPath(parentId))));
        JsonNode reply = thread.get("articles").get(thread.get("articles").size() - 1);
        assertThat(reply.get("id").asInt()).isEqualTo(stored.getId());
        assertThat(reply.get("parentId").asInt()).isEqualTo(parentId);
        assertThat(reply.get("sent").asBoolean()).isTrue();
        // The card of the thread does not count the article yet.
        JsonNode card = body(expectOk(browser.get(threadsPath()))).get("items").get(0);
        assertThat(card.get("messageCount").asInt()).isEqualTo(1);
    }

    @Test
    void aReplyGoesToTheGroupsOfFollowupToAndToItsOwnGroupWhenTheParentNamesNoValidGroup() {
        int withFollowupId = article("<a@example.org>", null,
                "Newsgroups: example.talk,example.other\r\nFollowup-To: example.followup\r\n", 1);
        int withoutGroupsId = article("<b@example.org>", null, "Newsgroups: not a group!\r\n", 2);

        post(draft(withFollowupId, "Re: Hello", "Text", false, null));
        post(draft(withoutGroupsId, "Re: Hello", "Text", false, null));

        assertThat(mockServer.postedArticles().get(0)).contains("Newsgroups: example.followup\r\n");
        assertThat(mockServer.postedArticles().get(1)).contains("Newsgroups: example.talk\r\n");
    }

    @Test
    void whatCannotBePostedIsRefusedBeforeTheServerIsAsked() {
        int byEmailId = article("<a@example.org>", null, "Newsgroups: example.talk\r\nFollowup-To: poster\r\n", 1);
        int withoutContentId = article("<b@example.org>", null, null, 2);
        int stubId = database.insertInto(ARTICLE).set(ARTICLE.MESSAGE_ID, "<stub@example.org>")
                .set(ARTICLE.IS_STUB, true).returning(ARTICLE.ID).fetchSingle().getId();
        int readOnlyGroupId = subscribedGroup("example.readonly", "NOT_ALLOWED");
        int moderatedGroupId = subscribedGroup("example.moderated", "MODERATED");
        int notSubscribedGroupId = group("example.unsubscribed", "ALLOWED");

        assertThat(postTo(groupId, draft(byEmailId, "Re: Hello", "Text", true, null))).hasStatus(HttpStatus.CONFLICT);
        assertThat(postTo(groupId, draft(withoutContentId, "Re: Hello", "Text", true, null)))
                .hasStatus(HttpStatus.CONFLICT);
        assertThat(postTo(groupId, draft(stubId, "Re: Hello", "Text", true, null))).hasStatus(HttpStatus.CONFLICT);
        assertThat(postTo(groupId, draft(987_654, "Re: Hello", "Text", true, null))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(postTo(readOnlyGroupId, draft(null, "Hello", "Text", true, null))).hasStatus(HttpStatus.CONFLICT);
        assertThat(postTo(notSubscribedGroupId, draft(null, "Hello", "Text", true, null)))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(postTo(987_654, draft(null, "Hello", "Text", true, null))).hasStatus(HttpStatus.NOT_FOUND);
        assertRejected(draft(null, "  \r\n ", "Text", true, null), "subject");
        assertRejected(draft(null, null, "Text", true, null), "subject");
        assertRejected(draft(null, "Hello", " \n ", true, null), "body");
        assertRejected(draft(null, "Hello", "x".repeat(70_000), true, null), "body");
        assertRejected(draft(null, "Hello", "Text", true, "<abcdefghijklmnopqrst@other.example.org>"), "messageId");
        database.update(NNTP_SERVER).setNull(NNTP_SERVER.POSTING_NAME).setNull(NNTP_SERVER.POSTING_EMAIL).execute();
        assertThat(postTo(groupId, draft(null, "Hello", "Text", true, null))).hasStatus(HttpStatus.CONFLICT);

        assertThat(mockServer.commands()).doesNotContain("POST");
        // A moderated group takes posts.
        storeIdentity("Jane Doe", "jane@news.example.org", null);
        assertThat(postTo(moderatedGroupId, draft(null, "Hello", "Text", true, null))).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void lineBreaksInTheSubjectDoNotAddHeaders() {
        post(draft(null, "Hello\r\nX-Injected: yes\nBcc: someone@example.org", "Text", false, null));

        String article = sentArticle();
        String headers = article.substring(0, article.indexOf("\r\n\r\n"));
        assertThat(headers.lines().filter(line -> !line.startsWith(" "))).noneMatch(
                line -> line.startsWith("X-Injected") || line.startsWith("Bcc"));
        assertThat(headers).contains("Subject: HelloX-Injected: yesBcc: someone@example.org");
    }

    // Answers of the server

    @Test
    void aRefusalOfTheServerIsAnswered502WithItsTypeItsMessageAndTheMessageIdAndNothingIsStored() {
        mockServer.on("POST").once(MockReply.lines("440 Posting not permitted"))
                .once(MockReply.lines("480 Authentication required"))
                .once(MockReply.article("340 Send the article", "441 Too much quoted text"))
                .reply(MockReply.drop());

        assertRefused("POSTING_NOT_ALLOWED", "Posting not permitted");
        assertRefused("AUTH_REQUIRED", "Authentication required");
        assertRefused("POSTING_FAILED", "Too much quoted text");
        assertRefused("UNREACHABLE", null);

        assertThat(database.fetchCount(ARTICLE)).isZero();
    }

    @Test
    void aServerWithASeparatePostingServerGetsThePostOnAConnectionOfItsOwnToThatServer() throws IOException {
        try (MockNntpServer postingServer = MockNntpServer.start()) {
            postingServer.on("CAPABILITIES").reply(MockReply.fixture("capabilities-reader.txt"));
            postingServer.on("POST").once(ACCEPTED).reply(MockReply.lines("440 Posting not permitted"));
            usePostingServer(postingServer.port());

            JsonNode posted = post(draft(null, "Hello", "Text", false, null));

            assertThat(postingServer.postedArticles()).hasSize(1);
            assertThat(postingServer.postedArticles().getFirst()).contains("Message-ID: "
                    + posted.get("messageId").asString());
            assertThat(mockServer.postedArticles()).as("the server that is read from gets no post").isEmpty();
            assertThat(mockServer.commands()).doesNotContain("POST");
            assertThat(storedArticle(posted.get("messageId").asString()).getPostedFromNewsgroupId())
                    .isEqualTo(groupId);

            assertRefused("POSTING_NOT_ALLOWED", "Posting not permitted");
        }
        assertRefused("UNREACHABLE", null);
        assertThat(mockServer.postedArticles()).as("a post is never sent to the other server instead").isEmpty();
    }

    @Test
    void tryingAgainSendsTheSameMessageIdAndADuplicateCountsAsSuccess() {
        mockServer.on("POST").once(MockReply.article("340 Send the article", "441 Try again later"))
                .once(MockReply.article("340 Send the article", "441 435 Duplicate article"));
        MvcTestResult failed = postTo(groupId, draft(null, "Hello", "Text", false, null));
        assertThat(failed).hasStatus(HttpStatus.BAD_GATEWAY);
        String messageId = body(failed).get("messageId").asString();

        // The second attempt is refused as a duplicate: the server has the article of the first one.
        JsonNode posted = post(draft(null, "Hello", "Text", false, messageId));

        assertThat(posted.get("messageId").asString()).isEqualTo(messageId);
        assertThat(mockServer.postedArticles()).hasSize(2).allSatisfy(
                article -> assertThat(article).contains("Message-ID: " + messageId + "\r\n"));
        assertThat(storedArticle(messageId).getId()).isEqualTo(posted.get("articleId").asInt());

        // An attempt for an article that is stored posts nothing and returns the article.
        JsonNode again = post(draft(null, "Hello", "Text", false, messageId));
        assertThat(again).isEqualTo(posted);
        assertThat(mockServer.postedArticles()).hasSize(2);
        assertThat(database.fetchCount(ARTICLE)).isEqualTo(1);
    }

    @Test
    void aDuplicateAtTheFirstAttemptIsAFailure() {
        mockServer.on("POST").reply(MockReply.article("340 Send the article", "441 435 Duplicate article"));

        MvcTestResult failed = postTo(groupId, draft(null, "Hello", "Text", false, null));

        assertThat(failed).hasStatus(HttpStatus.BAD_GATEWAY);
        assertThat(body(failed).get("type").asString()).isEqualTo("POSTING_FAILED");
    }

    // The life of the post

    @Test
    void aSynchronizationThatBringsTheArticleMakesItANormalArticleOfTheGroup() {
        String messageId = post(draft(null, "Hello", "Text", false, null)).get("messageId").asString();
        int articleId = storedArticle(messageId).getId();
        mockServer.on("GROUP example.talk").reply("211 1 7 7 example.talk");
        mockServer.on("OVER 7-7").reply("224 Overview information follows",
                "7\tHello\tJane Doe <jane@news.example.org>\tSun, 22 Nov 2026 19:05:09 +0000\t" + messageId
                        + "\t\t300\t1", ".");

        assertThat(browser.post("/api/groups/" + groupId + "/sync")).hasStatus(HttpStatus.ACCEPTED);
        await().atMost(Duration.ofSeconds(10)).until(() -> database.fetchCount(ARTICLE_LOCATION) == 1);
        await().atMost(Duration.ofSeconds(10)).until(() -> !subscription().get("sync").get("running").asBoolean());

        assertThat(database.fetchCount(ARTICLE)).isEqualTo(1);
        JsonNode thread = body(expectOk(browser.get(threadPath(articleId))));
        assertThat(thread.get("articles").get(0).get("sent").asBoolean()).isFalse();
        JsonNode items = body(expectOk(browser.get(threadsPath()))).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("cardArticleId").asInt()).isEqualTo(articleId);
        // The owner's own article stays read.
        assertThat(unreadCount()).isZero();
    }

    @Test
    void unsubscribingDeletesThePostsOfTheGroupThatHaveNoLocationAndKeepsThoseOfOtherGroups() {
        int otherGroupId = subscribedGroup("example.other", "ALLOWED");
        String keptMessageId = post(draft(null, "Kept", "Text", false, null)).get("messageId").asString();
        MvcTestResult inOtherGroup = postTo(otherGroupId, draft(null, "Gone", "Text", false, null));
        assertThat(inOtherGroup).hasStatus(HttpStatus.CREATED);

        assertThat(browser.delete("/api/groups/" + otherGroupId + "/subscription")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(database.select(ARTICLE.MESSAGE_ID).from(ARTICLE).fetch(ARTICLE.MESSAGE_ID))
                .containsExactly(keptMessageId);
        int keptId = storedArticle(keptMessageId).getId();
        assertThat(body(expectOk(browser.get(threadPath(keptId)))).get("articles").get(0).get("sent").asBoolean())
                .isTrue();

        assertThat(browser.delete("/api/groups/" + groupId + "/subscription")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(database.fetchCount(ARTICLE)).isZero();
    }

    private JsonNode post(String draft) {
        MvcTestResult result = postTo(groupId, draft);
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return body(result);
    }

    private MvcTestResult postTo(int toGroupId, String draft) {
        return browser.post("/api/groups/" + toGroupId + "/articles", draft);
    }

    /** Posts go to a separate server on this port from now on, with the login of the server: none. */
    private void usePostingServer(int port) {
        Map<String, Object> postingServer = new LinkedHashMap<>();
        postingServer.put("host", "localhost");
        postingServer.put("port", port);
        postingServer.put("tlsMode", "NONE");
        postingServer.put("sameLogin", true);
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("name", "Mock server");
        server.put("host", "localhost");
        server.put("port", mockServer.port());
        server.put("tlsMode", "NONE");
        server.put("authMethod", "NONE");
        server.put("maxConnections", 2);
        server.put("postingServer", postingServer);
        expectOk(browser.put("/api/servers/" + serverId, JSON.writeValueAsString(server)));
    }

    private void assertRejected(String draft, String field) {
        MvcTestResult result = postTo(groupId, draft);
        assertThat(result).as(field).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(body(result).get("errors").get(0).get("field").asString()).isEqualTo(field);
    }

    private void assertRefused(String type, String serverMessage) {
        MvcTestResult result = postTo(groupId, draft(null, "Hello", "Text", false, null));
        assertThat(result).as(type).hasStatus(HttpStatus.BAD_GATEWAY);
        JsonNode problem = body(result);
        assertThat(problem.get("type").asString()).isEqualTo(type);
        assertThat(problem.get("messageId").asString()).matches("<[A-Za-z0-9]{20}@news\\.example\\.org>");
        if (serverMessage != null) {
            assertThat(problem.get("detail").asString()).isEqualTo(serverMessage);
        }
    }

    private void assertIdentityRejected(String identity, String field) {
        MvcTestResult result = browser.put(identityPath(), identity);
        assertThat(result).as(identity).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(body(result).get("errors").get(0).get("field").asString()).isEqualTo(field);
    }

    private void storeIdentity(String name, String email, String signature) {
        assertThat(browser.put(identityPath(), identity(name, email, signature))).hasStatusOk();
    }

    private static String identity(String name, String email, String signature) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("name", name);
        identity.put("email", email);
        identity.put("signature", signature);
        return JSON.writeValueAsString(identity);
    }

    private static String draft(Integer parentId, String subject, String text, boolean includeSignature,
            String messageId) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("parentId", parentId);
        draft.put("subject", subject);
        draft.put("body", text);
        draft.put("includeSignature", includeSignature);
        draft.put("messageId", messageId);
        return JSON.writeValueAsString(draft);
    }

    /** The last article the mock received, with its bytes read as UTF-8. */
    private String sentArticle() {
        return new String(mockServer.postedArticles().getLast().getBytes(StandardCharsets.ISO_8859_1),
                StandardCharsets.UTF_8);
    }

    private ArticleRecord storedArticle(String messageId) {
        return database.selectFrom(ARTICLE).where(ARTICLE.MESSAGE_ID.eq(messageId)).fetchSingle();
    }

    private int idOf(String messageId) {
        return storedArticle(messageId).getId();
    }

    private int unreadCount() {
        return subscription().get("unreadCount").asInt();
    }

    private JsonNode subscription() {
        for (JsonNode subscription : body(expectOk(browser.get("/api/subscriptions")))) {
            if (subscription.get("groupId").asInt() == groupId) {
                return subscription;
            }
        }
        throw new AssertionError("The group is not subscribed");
    }

    private String identityPath() {
        return "/api/servers/" + serverId + "/identity";
    }

    private String threadsPath() {
        return "/api/groups/" + groupId + "/threads";
    }

    private String threadPath(int articleId) {
        return threadsPath() + "/" + articleId;
    }

    private static MvcTestResult expectOk(MvcTestResult result) {
        assertThat(result).hasStatusOk();
        return result;
    }

    private int subscribedGroup(String name, String postingStatus) {
        int newGroupId = group(name, postingStatus);
        database.insertInto(SUBSCRIPTION).set(SUBSCRIPTION.NEWSGROUP_ID, newGroupId).execute();
        return newGroupId;
    }

    private int group(String name, String postingStatus) {
        return database.insertInto(NEWSGROUP)
                .set(NEWSGROUP.SERVER_ID, (int) serverId)
                .set(NEWSGROUP.NAME, name)
                .set(NEWSGROUP.POSTING_STATUS, postingStatus)
                .set(NEWSGROUP.LOW_WATERMARK, 0L)
                .set(NEWSGROUP.HIGH_WATERMARK, 0L)
                .returning(NEWSGROUP.ID)
                .fetchSingle().getId();
    }

    /** Stores an article of the group as the synchronization does, with the header block of a fetched content. */
    private int article(String messageId, String references, String rawHeaders, long number) {
        int articleId = database.insertInto(ARTICLE)
                .set(ARTICLE.MESSAGE_ID, messageId)
                .set(ARTICLE.IS_STUB, false)
                .set(ARTICLE.SUBJECT, "Hello")
                .set(ARTICLE.FROM, "Ada <ada@example.org>")
                .set(ARTICLE.AUTHOR_KEY, "ada@example.org")
                .set(ARTICLE.REFERENCES, references)
                .set(ARTICLE.DATE, 86_400_000L)
                .set(ARTICLE.RAW_HEADERS, rawHeaders == null ? null : PARENT_HEADERS + rawHeaders)
                .set(ARTICLE.BODY_TEXT, rawHeaders == null ? null : "Hello")
                .returning(ARTICLE.ID)
                .fetchSingle().getId();
        database.insertInto(ARTICLE_LOCATION)
                .set(ARTICLE_LOCATION.NEWSGROUP_ID, groupId)
                .set(ARTICLE_LOCATION.ARTICLE_NUMBER, number)
                .set(ARTICLE_LOCATION.ARTICLE_ID, articleId)
                .execute();
        return articleId;
    }
}
