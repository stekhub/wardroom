package io.github.stekhub.wardroom.articles.internal;

import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE;
import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE_LOCATION;
import static io.github.stekhub.wardroom.jooq.Tables.NEWSGROUP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.stekhub.wardroom.IntegrationTest;
import io.github.stekhub.wardroom.TestBrowser;
import io.github.stekhub.wardroom.TestInstallation;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import io.github.stekhub.wardroom.nntp.mock.MockReply;
import io.github.stekhub.wardroom.settings.CacheSettings;
import io.github.stekhub.wardroom.settings.Settings;
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

/** The cleanup job of the cache size limit (ADR 0023, ADR 0034), against a real SQLite database. */
@IntegrationTest
class CacheCleanupTest {

    /** Large enough to be stored in pages of its own, which are free again when the content is deleted. */
    private static final String BODY = "A line of an article that is cached.\n".repeat(600);

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DSLContext database;

    @Autowired
    CacheCleanup cacheCleanup;

    @Autowired
    CacheRepository cacheRepository;

    TestBrowser browser;
    MockNntpServer mockServer;
    long serverId;
    int groupId;
    long nextNumber = 1;

    @BeforeEach
    void startWithAGroup() throws IOException {
        browser = TestInstallation.loggedInOwner(mvc, database);
        mockServer = MockNntpServer.start();
        mockServer.on("CAPABILITIES").reply(MockReply.fixture("capabilities-reader.txt"));
        serverId = TestInstallation.addServer(browser, mockServer, 2);
        groupId = database.insertInto(NEWSGROUP)
                .set(NEWSGROUP.SERVER_ID, (int) serverId)
                .set(NEWSGROUP.NAME, "example.talk")
                .set(NEWSGROUP.POSTING_STATUS, "ALLOWED")
                .set(NEWSGROUP.LOW_WATERMARK, 0L)
                .set(NEWSGROUP.HIGH_WATERMARK, 0L)
                .returning(NEWSGROUP.ID)
                .fetchSingle().getId();
    }

    @AfterEach
    void removeServer() throws IOException {
        assertThat(browser.delete("/api/servers/" + serverId)).hasStatus(HttpStatus.NO_CONTENT);
        mockServer.close();
    }

    @Test
    void theSizeIsTheSpaceTheDatabaseUsesAndFallsWhenContentIsDeleted() {
        long emptyBytes = cacheRepository.usedBytes();
        List<Integer> articleIds = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            articleIds.add(locatedArticle((long) index, false));
        }
        long filledBytes = cacheRepository.usedBytes();
        assertThat(filledBytes).isGreaterThan(emptyBytes + 20L * BODY.length() / 2);

        cacheRepository.clearContent(articleIds);

        assertThat(cacheRepository.usedBytes()).isLessThan(filledBytes - 20L * BODY.length() / 2);
    }

    @Test
    void atOrBelowTheLimitNothingIsDeleted() {
        int articleId = locatedArticle(1L, false);

        assertThat(cacheCleanup.cleanUp(cacheRepository.usedBytes(), 10)).isZero();

        assertThat(hasContent(articleId)).isTrue();
    }

    @Test
    void aboveTheLimitTheOldestContentIsDeletedUntilTheDatabaseIsBelowNinetyPercentOfTheLimit() {
        int undatedId = locatedArticle(null, false);
        List<Integer> articleIdsOldestFirst = new ArrayList<>(List.of(undatedId));
        // Stored newest first, so that the order of the cleanup is not the order of the rows.
        List<Integer> datedIds = new ArrayList<>();
        for (int index = 59; index >= 1; index--) {
            datedIds.addFirst(locatedArticle(index * 1000L, false));
        }
        articleIdsOldestFirst.addAll(datedIds);
        long limitBytes = cacheRepository.usedBytes() - 100_000;

        int cleanedArticles = cacheCleanup.cleanUp(limitBytes, 5);

        assertThat(cacheRepository.usedBytes()).isLessThan(limitBytes / 10 * 9);
        assertThat(cleanedArticles).isBetween(5, 55);
        assertThat(cleanedArticles % 5).isZero();
        for (int position = 0; position < articleIdsOldestFirst.size(); position++) {
            assertThat(hasContent(articleIdsOldestFirst.get(position)))
                    .as("content of the article at position %d, oldest first", position)
                    .isEqualTo(position >= cleanedArticles);
        }
        // What the overview said, the location and the place in the list stay.
        assertThat(database.select(ARTICLE.SUBJECT).from(ARTICLE).where(ARTICLE.ID.eq(undatedId))
                .fetchSingle(ARTICLE.SUBJECT)).isEqualTo("An article");
        assertThat(database.fetchCount(ARTICLE_LOCATION)).isEqualTo(60);
        // A second check finds the database below the limit and does nothing.
        assertThat(cacheCleanup.cleanUp(limitBytes, 5)).isZero();
    }

    @Test
    void contentTheServerStillHasGoesBeforeContentItNoLongerHas() {
        int expiredOldId = locatedArticle(1000L, true);
        int withoutLocationId = article("<without-location>", 2000L, null);
        int onServerNewId = locatedArticle(4000L, false);
        int onServerOldId = locatedArticle(3000L, false);
        int onServerUndatedId = locatedArticle(null, false);
        int neverOpenedId = database.insertInto(ARTICLE)
                .set(ARTICLE.MESSAGE_ID, "<never-opened>")
                .set(ARTICLE.IS_STUB, false)
                .set(ARTICLE.DATE, 500L)
                .returning(ARTICLE.ID)
                .fetchSingle().getId();

        assertThat(cacheRepository.findCleanable(true, 10))
                .containsExactly(onServerUndatedId, onServerOldId, onServerNewId);
        assertThat(cacheRepository.findCleanable(false, 10))
                .containsExactly(onServerUndatedId, expiredOldId, withoutLocationId, onServerOldId, onServerNewId)
                .doesNotContain(neverOpenedId);

        // A limit that asks for a little: the first batch of one article is the undated one on the server.
        long limitBytes = cacheRepository.usedBytes() - 1;
        cacheCleanup.cleanUp(limitBytes, 1);
        assertThat(hasContent(onServerUndatedId)).isFalse();
        assertThat(hasContent(expiredOldId)).isTrue();
    }

    @Test
    void theOwnersArticleThatWaitsForItsLocationKeepsItsContentAndTheLimitIsReportedAsNotReachable() {
        int waitingId = article("<waiting>", 1000L, groupId);
        int foundInGroupId = article("<found>", 2000L, groupId);
        locate(foundInGroupId, false);
        int otherId = locatedArticle(3000L, false);
        assertThat(cacheCleanup.limitUnreachable(1)).isFalse();

        int cleanedArticles = cacheCleanup.cleanUp(1, 10);

        assertThat(cleanedArticles).isEqualTo(2);
        assertThat(hasContent(waitingId)).isTrue();
        assertThat(hasContent(foundInGroupId)).isFalse();
        assertThat(hasContent(otherId)).isFalse();
        assertThat(cacheCleanup.limitUnreachable(1)).isTrue();
        assertThat(cacheCleanup.limitUnreachable(Long.MAX_VALUE)).isFalse();
    }

    @Test
    void anInstallationFarBelowItsLimitIsNotCleanedAndReportsItsUse() {
        int articleId = locatedArticle(1L, false);

        assertThat(cacheCleanup.cleanUp()).isZero();

        assertThat(hasContent(articleId)).isTrue();
        assertThat(cacheCleanup.usedBytes()).isPositive();
        assertThat(cacheCleanup.limitUnreachable()).isFalse();
    }

    @Test
    void withTheLimitSwitchedOffNothingIsDeletedAndNoWarningIsDue() {
        CacheRepository hugeCache = mock(CacheRepository.class);
        when(hugeCache.usedBytes()).thenReturn(10L * 1024 * 1024 * 1024);
        when(hugeCache.findCleanable(anyBoolean(), anyInt())).thenReturn(List.of());
        Settings settings = mock(Settings.class);
        CacheCleanup cleanup = new CacheCleanup(hugeCache, settings);

        when(settings.cache()).thenReturn(new CacheSettings(false, 5));
        assertThat(cleanup.cleanUp()).isZero();
        assertThat(cleanup.limitUnreachable()).isFalse();
        verify(hugeCache, never()).findCleanable(anyBoolean(), anyInt());
        verify(hugeCache, never()).clearContent(any());

        // The same cache with the limit switched on is above it, and nothing is left to delete.
        when(settings.cache()).thenReturn(new CacheSettings(true, 5));
        assertThat(cleanup.cleanUp()).isZero();
        assertThat(cleanup.limitUnreachable()).isTrue();
    }

    /** Stores an article with cached content and a location in the group. */
    private int locatedArticle(Long date, boolean expired) {
        int articleId = article("<" + nextNumber + "@example>", date, null);
        locate(articleId, expired);
        return articleId;
    }

    private int article(String messageId, Long date, Integer postedFromGroupId) {
        return database.insertInto(ARTICLE)
                .set(ARTICLE.MESSAGE_ID, messageId)
                .set(ARTICLE.IS_STUB, false)
                .set(ARTICLE.SUBJECT, "An article")
                .set(ARTICLE.DATE, date)
                .set(ARTICLE.RAW_HEADERS, "Subject: An article\r\n")
                .set(ARTICLE.BODY_TEXT, BODY)
                .set(ARTICLE.BODY_FLOWED, false)
                .set(ARTICLE.OMITTED_PARTS, "[]")
                .set(ARTICLE.CONTENT_FETCHED_AT, 1L)
                .set(ARTICLE.POSTED_FROM_NEWSGROUP_ID, postedFromGroupId)
                .returning(ARTICLE.ID)
                .fetchSingle().getId();
    }

    private void locate(int articleId, boolean expired) {
        database.insertInto(ARTICLE_LOCATION)
                .set(ARTICLE_LOCATION.NEWSGROUP_ID, groupId)
                .set(ARTICLE_LOCATION.ARTICLE_NUMBER, nextNumber++)
                .set(ARTICLE_LOCATION.ARTICLE_ID, articleId)
                .set(ARTICLE_LOCATION.EXPIRED_AT, expired ? 1L : null)
                .execute();
    }

    private boolean hasContent(int articleId) {
        return database.select(ARTICLE.BODY_TEXT, ARTICLE.RAW_HEADERS, ARTICLE.OMITTED_PARTS,
                        ARTICLE.CONTENT_FETCHED_AT)
                .from(ARTICLE)
                .where(ARTICLE.ID.eq(articleId))
                .fetchSingle(row -> {
                    boolean anyValue = row.value1() != null || row.value2() != null || row.value3() != null
                            || row.value4() != null;
                    boolean everyValue = row.value1() != null && row.value2() != null && row.value3() != null
                            && row.value4() != null;
                    assertThat(anyValue).as("content is there completely or not at all").isEqualTo(everyValue);
                    return everyValue;
                });
    }
}
