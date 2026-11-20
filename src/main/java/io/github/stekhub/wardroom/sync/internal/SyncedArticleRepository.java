package io.github.stekhub.wardroom.sync.internal;

import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE;
import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE_LOCATION;
import static io.github.stekhub.wardroom.jooq.Tables.SYNCED_RANGE;

import io.github.stekhub.wardroom.nntp.NntpOverview;
import io.github.stekhub.wardroom.platform.LogSafe;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.springframework.stereotype.Repository;

/**
 * Database access for what the synchronization stores: articles, their locations and the synchronized ranges of a
 * group (data model; spec 005).
 */
@Slf4j
@Repository
@RequiredArgsConstructor
class SyncedArticleRepository {

    private final DSLContext database;

    /** A range of article numbers whose overviews have been fetched. Both ends belong to it. */
    record SyncedRange(long id, long firstNumber, long lastNumber) {
    }

    /** What storing the overviews of one chunk did, for the log. */
    record StoredChunk(int newArticles, int filledStubs, int newLocations, int movedLocations) {
    }

    /** @return the range of the group that reaches the highest article number, or empty before the first one */
    Optional<SyncedRange> findNewestRange(long groupId) {
        return database.select(SYNCED_RANGE.ID, SYNCED_RANGE.FIRST_NUMBER, SYNCED_RANGE.LAST_NUMBER)
                .from(SYNCED_RANGE)
                .where(SYNCED_RANGE.NEWSGROUP_ID.eq((int) groupId))
                .orderBy(SYNCED_RANGE.LAST_NUMBER.desc())
                .limit(1)
                .fetchOptional(row -> new SyncedRange(row.value1(), row.value2(), row.value3()));
    }

    /**
     * Extends the newest range of the group up to the given number. A group without a range gets its first one.
     *
     * @param firstNumber the start of the range when it is the first of the group
     */
    void extendNewestRange(long groupId, long firstNumber, long lastNumber) {
        Optional<SyncedRange> newestRange = findNewestRange(groupId);
        if (newestRange.isPresent()) {
            database.update(SYNCED_RANGE)
                    .set(SYNCED_RANGE.LAST_NUMBER, lastNumber)
                    .where(SYNCED_RANGE.ID.eq((int) newestRange.get().id()))
                    .execute();
        } else {
            database.insertInto(SYNCED_RANGE, SYNCED_RANGE.NEWSGROUP_ID, SYNCED_RANGE.FIRST_NUMBER,
                            SYNCED_RANGE.LAST_NUMBER)
                    .values((int) groupId, firstNumber, lastNumber)
                    .execute();
        }
    }

    /**
     * Marks the locations of the group below the low watermark as expired: the server no longer has these articles.
     * Their cached content stays readable.
     *
     * @return how many locations were newly marked
     */
    int markExpired(long groupId, long lowWatermark, long now) {
        return database.update(ARTICLE_LOCATION)
                .set(ARTICLE_LOCATION.EXPIRED_AT, now)
                .where(ARTICLE_LOCATION.NEWSGROUP_ID.eq((int) groupId))
                .and(ARTICLE_LOCATION.ARTICLE_NUMBER.lt(lowWatermark))
                .and(ARTICLE_LOCATION.EXPIRED_AT.isNull())
                .execute();
    }

    /**
     * Stores the overviews of one chunk. To be called in one transaction, together with the range.
     *
     * <ul>
     * <li>An article that is not known is stored, and is unread.</li>
     * <li>A stub with this Message-ID is filled in.</li>
     * <li>A known article gets a location in this group: it is crossposted. Its stored fields stay.</li>
     * <li>A known article that has a location in this group under another number gets the new number.</li>
     * </ul>
     */
    StoredChunk storeOverviews(long groupId, List<NntpOverview> overviews) {
        int newArticles = 0;
        int filledStubs = 0;
        int newLocations = 0;
        int movedLocations = 0;
        for (NntpOverview overview : overviews) {
            Record2<Integer, Boolean> knownArticle = database.select(ARTICLE.ID, ARTICLE.IS_STUB)
                    .from(ARTICLE)
                    .where(ARTICLE.MESSAGE_ID.eq(overview.messageId()))
                    .fetchOne();
            int articleId;
            if (knownArticle == null) {
                articleId = insertArticle(overview);
                newArticles++;
            } else {
                articleId = knownArticle.value1();
                if (Boolean.TRUE.equals(knownArticle.value2())) {
                    fillStub(articleId, overview);
                    filledStubs++;
                }
            }
            Record2<Integer, Long> knownLocation = database
                    .select(ARTICLE_LOCATION.ID, ARTICLE_LOCATION.ARTICLE_NUMBER)
                    .from(ARTICLE_LOCATION)
                    .where(ARTICLE_LOCATION.NEWSGROUP_ID.eq((int) groupId))
                    .and(ARTICLE_LOCATION.ARTICLE_ID.eq(articleId))
                    .fetchOne();
            if (knownLocation != null && knownLocation.value2() == overview.number()) {
                continue;
            }
            // A number belongs to one article. What another article left under this number is outdated.
            database.deleteFrom(ARTICLE_LOCATION)
                    .where(ARTICLE_LOCATION.NEWSGROUP_ID.eq((int) groupId))
                    .and(ARTICLE_LOCATION.ARTICLE_NUMBER.eq(overview.number()))
                    .execute();
            if (knownLocation == null) {
                database.insertInto(ARTICLE_LOCATION, ARTICLE_LOCATION.NEWSGROUP_ID, ARTICLE_LOCATION.ARTICLE_NUMBER,
                                ARTICLE_LOCATION.ARTICLE_ID)
                        .values((int) groupId, overview.number(), articleId)
                        .execute();
                newLocations++;
            } else {
                database.update(ARTICLE_LOCATION)
                        .set(ARTICLE_LOCATION.ARTICLE_NUMBER, overview.number())
                        .setNull(ARTICLE_LOCATION.EXPIRED_AT)
                        .where(ARTICLE_LOCATION.ID.eq(knownLocation.value1()))
                        .execute();
                movedLocations++;
                log.debug("Article {} of group {} moved from number {} to {}", LogSafe.escape(overview.messageId()),
                        groupId, knownLocation.value2(), overview.number());
            }
        }
        return new StoredChunk(newArticles, filledStubs, newLocations, movedLocations);
    }

    private int insertArticle(NntpOverview overview) {
        return database.insertInto(ARTICLE)
                .set(ARTICLE.MESSAGE_ID, overview.messageId())
                .set(ARTICLE.IS_STUB, false)
                .set(ARTICLE.SUBJECT, overview.subject())
                .set(ARTICLE.FROM, overview.from())
                .set(ARTICLE.DATE, overview.date())
                .set(ARTICLE.REFERENCES, overview.references())
                .set(ARTICLE.BYTES, overview.bytes())
                .set(ARTICLE.LINES, overview.lines())
                .returningResult(ARTICLE.ID)
                .fetchOne()
                .value1();
    }

    private void fillStub(int articleId, NntpOverview overview) {
        database.update(ARTICLE)
                .set(ARTICLE.IS_STUB, false)
                .set(ARTICLE.SUBJECT, overview.subject())
                .set(ARTICLE.FROM, overview.from())
                .set(ARTICLE.DATE, overview.date())
                .set(ARTICLE.REFERENCES, overview.references())
                .set(ARTICLE.BYTES, overview.bytes())
                .set(ARTICLE.LINES, overview.lines())
                .where(ARTICLE.ID.eq(articleId))
                .execute();
        log.debug("Stub {} was filled in", LogSafe.escape(overview.messageId()));
    }
}
