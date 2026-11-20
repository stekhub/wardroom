package io.github.stekhub.wardroom.articles.internal;

import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE;
import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE_LOCATION;
import static io.github.stekhub.wardroom.jooq.Tables.NEWSGROUP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.stekhub.wardroom.IntegrationTest;
import io.github.stekhub.wardroom.TestBrowser;
import io.github.stekhub.wardroom.TestInstallation;
import io.github.stekhub.wardroom.articles.internal.ThreadRepository.GroupThread;
import io.github.stekhub.wardroom.jooq.tables.Article;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import io.github.stekhub.wardroom.nntp.mock.MockReply;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Threading against a real SQLite database (spec 006). The tests put articles into the database as the
 * synchronization does: without a place in a thread.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ThreadServiceTest {

    private static final long DAY = 86_400_000L;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DSLContext database;

    @Autowired
    ThreadService threadService;

    @Autowired
    ThreadRepository threadRepository;

    TestBrowser browser;
    MockNntpServer mockServer;
    long serverId;
    int groupId;

    @BeforeEach
    void startWithGroup() throws IOException {
        browser = TestInstallation.loggedInOwner(mvc, database);
        mockServer = MockNntpServer.start();
        mockServer.on("CAPABILITIES").reply(MockReply.fixture("capabilities-reader.txt"));
        serverId = TestInstallation.addServer(browser, mockServer, 2);
        groupId = group("example.talk");
    }

    @AfterEach
    void removeServer() throws IOException {
        assertThat(browser.delete("/api/servers/" + serverId)).hasStatus(HttpStatus.NO_CONTENT);
        mockServer.close();
    }

    // Parents, stubs and roots

    @Test
    void threadingAGroupGivesItsNewArticlesTheirParentCreatesStubsAndSetsTheRoots() {
        article(groupId, 1, "<a>", "Hello", null, DAY);
        article(groupId, 2, "<b>", "Re: Hello", "<a>", 2 * DAY);
        article(groupId, 3, "<d>", "Re: Hello", "<a> <b> <c>", 3 * DAY);
        article(groupId, 4, "<f>", "Other", "<e>", 4 * DAY);

        threadService.recomputeGroup(groupId);

        assertThat(structure()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "<a>", "null root <a>",
                "<b>", "<a> root <a>",
                "<c>", "<b> root <a> stub",
                "<d>", "<c> root <a>",
                "<e>", "null root <e> stub",
                "<f>", "<e> root <e>"));
    }

    @Test
    void anArticleWhoseParentHasAHigherNumberIsThreadedCorrectly() {
        article(groupId, 1, "<b>", "Re: Hello", "<a>", 2 * DAY);
        article(groupId, 2, "<a>", "Hello", "<o>", DAY);

        threadService.recomputeGroup(groupId);

        assertThat(structure()).containsEntry("<b>", "<a> root <o>").containsEntry("<a>", "<o> root <o>");
    }

    @Test
    void aStubThatIsFilledInIsThreadedByItsOwnReferencesAndItsRepliesFollow() {
        article(groupId, 10, "<c>", "Re: Hello", "<a> <b>", 3 * DAY);
        article(groupId, 11, "<d>", "Re: Hello", "<a> <b> <c>", 4 * DAY);
        threadService.recomputeGroup(groupId);
        assertThat(structure()).containsEntry("<b>", "<a> root <a> stub").containsEntry("<d>", "<c> root <a>");

        int stubId = idOf("<b>");
        article(groupId, 5, "<b>", "Re: Hello", "<x>", 2 * DAY);
        threadService.recomputeGroup(groupId);

        assertThat(structure()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "<a>", "null root <a> stub",
                "<x>", "null root <x> stub",
                "<b>", "<x> root <x>",
                "<c>", "<b> root <x>",
                "<d>", "<c> root <x>"));
        assertThat(idOf("<b>")).isEqualTo(stubId);
    }

    @Test
    void aCycleIsIgnoredAndLogged(CapturedOutput output) {
        article(groupId, 2, "<b>", "Re: Hello", "<a>", 2 * DAY);
        threadService.recomputeGroup(groupId);
        article(groupId, 1, "<a>", "Hello", "<b>", DAY);

        threadService.recomputeGroup(groupId);

        assertThat(structure()).containsEntry("<a>", "null root <a>").containsEntry("<b>", "<a> root <a>");
        assertThat(output.getOut()).contains("references.cycle");
    }

    @Test
    void aGroupWithMoreArticlesThanOnePageIsThreadedCompletely() {
        int articleCount = 2 * ThreadService.PAGE_SIZE + 150;
        article(groupId, 1, "<1>", "Long thread", null, DAY);
        for (int number = 2; number <= articleCount; number++) {
            article(groupId, number, "<" + number + ">", "Re: Long thread",
                    "<1> <" + (number - 1) + ">", DAY + number);
        }

        threadService.recomputeGroup(groupId);

        int rootId = idOf("<1>");
        assertThat(database.fetchCount(ARTICLE, ARTICLE.THREAD_ROOT_ID.eq(rootId))).isEqualTo(articleCount);
        assertThat(database.fetchCount(ARTICLE, ARTICLE.IS_STUB.isTrue())).isZero();
        assertThat(threadRepository.findThreads(groupId))
                .containsExactly(new GroupThread(rootId, rootId, articleCount, DAY + articleCount));
    }

    // Subject fallback

    @Test
    void anOriginalPostThatIsLoadedLaterBecomesTheRootAndTheOldRootResolvesToIt() {
        article(groupId, 50, "<b>", "Re: Hello", null, 5 * DAY);
        article(groupId, 51, "<c>", "Re: Hello", "<b>", 6 * DAY);
        threadService.recomputeGroup(groupId);
        assertThat(structure()).containsEntry("<b>", "null root <b>").containsEntry("<c>", "<b> root <b>");

        article(groupId, 10, "<a>", "Hello", null, DAY);
        threadService.recomputeGroup(groupId);

        assertThat(structure()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "<a>", "null root <a>",
                "<b>", "<a> root <a>",
                "<c>", "<b> root <a>"));
        assertThat(threadRepository.findThreadRootId(idOf("<b>"))).contains((long) idOf("<a>"));
        assertThat(threadRepository.findThreadRootId(idOf("<c>"))).contains((long) idOf("<a>"));
        assertThat(threadRepository.findThreadRootId(idOf("<a>"))).contains((long) idOf("<a>"));
        assertThat(threadRepository.findThreadRootId(-1)).isEmpty();
    }

    @Test
    void theFallbackLooksOnlyAtDatedThreadRootsOfTheSameGroupThatAreNoStubs() {
        int otherGroupId = group("example.other");
        article(otherGroupId, 1, "<elsewhere>", "Hello", null, DAY);
        article(groupId, 1, "<undated>", "Hello", null, null);
        // The stub <s> is a thread root, and has no subject to match.
        article(groupId, 2, "<with-references>", "Hello", "<s>", 2 * DAY);
        article(groupId, 3, "<too-old>", "Hello", null, 3 * DAY);
        article(groupId, 9, "<reply>", "Re: Hello", null, 40 * DAY);

        threadService.recomputeGroup(otherGroupId);
        threadService.recomputeGroup(groupId);

        assertThat(structure()).containsEntry("<reply>", "null root <reply>");

        article(groupId, 4, "<match>", "HELLO", null, 10 * DAY);
        threadService.recomputeGroup(groupId);

        assertThat(structure()).containsEntry("<reply>", "<match> root <match>");
    }

    @Test
    void aCrosspostedReplyWithoutReferencesIsDecidedInOneOfItsGroupsOnly() {
        int secondGroupId = group("example.second");
        int replyId = article(groupId, 5, "<reply>", "Re: Hello", null, 5 * DAY);
        locate(replyId, secondGroupId, 500);
        article(secondGroupId, 400, "<root-in-second>", "Hello", null, 4 * DAY);

        threadService.recomputeGroup(groupId);
        threadService.recomputeGroup(secondGroupId);

        // The group with the lower ID decides, and it has no matching thread root.
        assertThat(structure()).containsEntry("<reply>", "null root <reply>");

        article(groupId, 3, "<root-in-first>", "Hello", null, 3 * DAY);
        threadService.recomputeGroup(groupId);
        threadService.recomputeGroup(secondGroupId);

        assertThat(structure()).containsEntry("<reply>", "<root-in-first> root <root-in-first>");
    }

    // A thread in a group

    @Test
    void theFiguresOfAThreadCountOnlyTheArticlesLocatedInTheGroup() {
        int otherGroupId = group("example.other");
        int rootId = article(groupId, 1, "<a>", "Hello", null, 100L);
        int replyId = article(groupId, 2, "<b>", "Re: Hello", "<a>", 300L);
        // A reply that is stored, but located only in another group.
        int replyElsewhereId = article(otherGroupId, 7, "<c>", "Re: Hello", "<a> <b>", 900L);
        article(groupId, 3, "<d>", "Re: Hello", "<a>", null);
        // A thread whose root is a stub: the oldest article stands for it.
        int laterId = article(groupId, 4, "<e>", "Re: Lost", "<s>", 50L);
        int oldestId = article(groupId, 5, "<f>", "Re: Lost", "<s>", 40L);
        database.update(ARTICLE_LOCATION).set(ARTICLE_LOCATION.EXPIRED_AT, 1L)
                .where(ARTICLE_LOCATION.ARTICLE_ID.eq(replyId)).execute();
        threadService.recomputeGroup(groupId);
        threadService.recomputeGroup(otherGroupId);
        int stubId = idOf("<s>");

        assertThat(threadRepository.findThreads(groupId)).containsExactlyInAnyOrder(
                // The undated article counts, and is left out of the latest activity. The expired one counts.
                new GroupThread(rootId, rootId, 3, 300L),
                new GroupThread(stubId, oldestId, 2, 50L));
        assertThat(threadRepository.findThreads(otherGroupId)).containsExactly(
                new GroupThread(rootId, replyElsewhereId, 1, 900L));
        assertThat(laterId).isNotEqualTo(oldestId);
    }

    @Test
    void aThreadWithoutAnyDateHasNoLatestActivityAndItsFirstArticleByNumberStandsForIt() {
        article(groupId, 8, "<later>", "Re: Lost", "<s>", null);
        int firstId = article(groupId, 3, "<first>", "Re: Lost", "<s>", null);
        threadService.recomputeGroup(groupId);

        assertThat(threadRepository.findThreads(groupId))
                .containsExactly(new GroupThread(idOf("<s>"), firstId, 2, null));
    }

    @Test
    void anArticleWithoutAPlaceIsAThreadOfItsOwnUntilItIsThreaded() {
        int articleId = article(groupId, 1, "<a>", "Hello", null, 100L);

        assertThat(threadRepository.findThreads(groupId))
                .containsExactly(new GroupThread(articleId, articleId, 1, 100L));
        assertThat(threadRepository.findThreadRootId(articleId)).contains((long) articleId);
    }

    // Complete recomputation

    @Test
    void aCompleteRecomputationCreatesTheStubsAgainAndGivesTheSameResultTwice() {
        int otherGroupId = group("example.other");
        article(groupId, 1, "<a>", "Hello", null, DAY);
        article(groupId, 2, "<c>", "Re: Hello", "<a> <b>", 3 * DAY);
        article(otherGroupId, 1, "<d>", "Re: Hello", "<a> <b> <c>", 4 * DAY);
        article(groupId, 3, "<reply>", "Re: Hello", null, 5 * DAY);
        threadService.recomputeGroup(groupId);
        threadService.recomputeGroup(otherGroupId);
        Map<String, String> threadedStepByStep = structure();
        assertThat(threadedStepByStep).containsEntry("<b>", "<a> root <a> stub")
                .containsEntry("<reply>", "<a> root <a>");

        threadService.recomputeAllThreads();

        assertThat(structure()).isEqualTo(threadedStepByStep);

        threadService.recomputeAllThreads();

        assertThat(structure()).isEqualTo(threadedStepByStep);
        assertThat(database.fetchCount(ARTICLE, ARTICLE.IS_STUB.isTrue())).isEqualTo(1);
    }

    @Test
    void aCompleteRecomputationDoesNotDependOnTheOrderInWhichTheArticlesWereStored() {
        // Two articles list different Message-IDs before the missing <m>: the lower article number decides.
        record Stored(long number, String messageId, String references) {
        }
        List<Stored> storedArticles = List.of(
                new Stored(1, "<a>", null),
                new Stored(2, "<b>", "<a>"),
                new Stored(3, "<c>", "<a> <m>"),
                new Stored(4, "<d>", "<b> <m>"),
                new Stored(5, "<e>", "<b> <m> <d>"));
        for (Stored stored : storedArticles) {
            article(groupId, stored.number(), stored.messageId(), "Subject", stored.references(), DAY);
        }
        threadService.recomputeAllThreads();
        Map<String, String> expected = structure();
        assertThat(expected).containsEntry("<m>", "<a> root <a> stub");

        database.deleteFrom(ARTICLE_LOCATION).execute();
        database.deleteFrom(ARTICLE).execute();
        // Newest first, as a group that is read backwards with "load older".
        for (Stored stored : storedArticles.reversed()) {
            article(groupId, stored.number(), stored.messageId(), "Subject", stored.references(), DAY);
            threadService.recomputeGroup(groupId);
        }
        // Step by step, the article that came first decided.
        assertThat(structure()).containsEntry("<m>", "<b> root <a> stub");

        threadService.recomputeAllThreads();

        assertThat(structure()).isEqualTo(expected);
    }

    // Failures

    @Test
    void aFailedRecomputationOfAGroupIsLoggedAndNotPassedOn(CapturedOutput output) {
        ThreadRepository failingRepository = mock(ThreadRepository.class);
        when(failingRepository.findUnthreaded(anyLong(), anyInt())).thenThrow(new IllegalStateException("broken"));
        when(failingRepository.findGroupsWithArticles()).thenReturn(List.of(1L));
        ThreadService failingService = new ThreadService(failingRepository,
                TransactionOperations.withoutTransaction());

        assertThatCode(() -> failingService.recomputeGroup(1)).doesNotThrowAnyException();
        assertThat(output.getOut()).contains("The threads of group 1 could not be recomputed");
        // With the articles removed there is no earlier state to keep, so the removal fails with it.
        assertThatThrownBy(failingService::recomputeAllThreads).isInstanceOf(IllegalStateException.class);
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

    /**
     * Stores an article as the synchronization does: located in the group, and without a place in a thread. A stub
     * with this Message-ID is filled in and loses its thread root.
     */
    private int article(int inGroupId, long number, String messageId, String subject, String references, Long date) {
        Integer stubId = database.select(ARTICLE.ID).from(ARTICLE).where(ARTICLE.MESSAGE_ID.eq(messageId))
                .fetchOne(ARTICLE.ID);
        int articleId;
        if (stubId == null) {
            articleId = database.insertInto(ARTICLE)
                    .set(ARTICLE.MESSAGE_ID, messageId)
                    .set(ARTICLE.IS_STUB, false)
                    .set(ARTICLE.SUBJECT, subject)
                    .set(ARTICLE.REFERENCES, references)
                    .set(ARTICLE.DATE, date)
                    .returning(ARTICLE.ID)
                    .fetchSingle().getId();
        } else {
            articleId = stubId;
            database.update(ARTICLE)
                    .set(ARTICLE.IS_STUB, false)
                    .set(ARTICLE.SUBJECT, subject)
                    .set(ARTICLE.REFERENCES, references)
                    .set(ARTICLE.DATE, date)
                    .setNull(ARTICLE.THREAD_ROOT_ID)
                    .where(ARTICLE.ID.eq(articleId))
                    .execute();
        }
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

    /**
     * @return every article and stub by Message-ID, as {@code "<parent> root <root>"}, followed by {@code " stub"}
     *         for a stub
     */
    private Map<String, String> structure() {
        Article parent = ARTICLE.as("parent");
        Article root = ARTICLE.as("root");
        Map<String, String> structure = new TreeMap<>();
        List<String> lines = new ArrayList<>();
        database.select(ARTICLE.MESSAGE_ID, parent.MESSAGE_ID, root.MESSAGE_ID, ARTICLE.IS_STUB)
                .from(ARTICLE)
                .leftJoin(parent).on(parent.ID.eq(ARTICLE.PARENT_ID))
                .leftJoin(root).on(root.ID.eq(ARTICLE.THREAD_ROOT_ID))
                .forEach(row -> {
                    structure.put(row.value1(), row.value2() + " root " + row.value3()
                            + (Boolean.TRUE.equals(row.value4()) ? " stub" : ""));
                    lines.add(row.value1());
                });
        assertThat(lines).doesNotHaveDuplicates();
        return structure;
    }
}
