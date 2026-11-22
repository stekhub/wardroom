package io.github.stekhub.wardroom.articles.internal;

import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE;
import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE_LOCATION;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Database access for the cache size limit (ADR 0023; data model, "Lifecycle rules"). */
@Repository
@RequiredArgsConstructor
class CacheRepository {

    private final DSLContext database;

    /**
     * @return the space the database uses: its pages without the free ones. Read from the file header, so it costs
     *         no scan. The file itself does not shrink when content is deleted; the freed pages are used again
     */
    long usedBytes() {
        long pageCount = pragma("page_count");
        long freePageCount = pragma("freelist_count");
        long pageSize = pragma("page_size");
        return (pageCount - freePageCount) * pageSize;
    }

    /**
     * The next articles whose content may be deleted, oldest first, articles without a date before the others.
     * The owner's articles that wait for their location are never among them (spec 009).
     *
     * @param stillOnServer {@code true} for articles the server still has (a location that is not expired), which
     *                      can be fetched again; {@code false} for every article with content
     */
    List<Integer> findCleanable(boolean stillOnServer, int limit) {
        Condition waitingForLocation = ARTICLE.POSTED_FROM_NEWSGROUP_ID.isNotNull()
                .and(DSL.notExists(DSL.selectOne().from(ARTICLE_LOCATION)
                        .where(ARTICLE_LOCATION.ARTICLE_ID.eq(ARTICLE.ID))
                        .and(ARTICLE_LOCATION.NEWSGROUP_ID.eq(ARTICLE.POSTED_FROM_NEWSGROUP_ID))));
        Condition onServer = stillOnServer
                ? DSL.exists(DSL.selectOne().from(ARTICLE_LOCATION)
                        .where(ARTICLE_LOCATION.ARTICLE_ID.eq(ARTICLE.ID))
                        .and(ARTICLE_LOCATION.EXPIRED_AT.isNull()))
                : DSL.noCondition();
        // SQLite sorts NULL first in ascending order, which is the order ADR 0023 asks for.
        return database.select(ARTICLE.ID)
                .from(ARTICLE)
                .where(ARTICLE.CONTENT_FETCHED_AT.isNotNull())
                .and(onServer)
                .and(DSL.not(waitingForLocation))
                .orderBy(ARTICLE.DATE.asc(), ARTICLE.ID.asc())
                .limit(limit)
                .fetch(ARTICLE.ID);
    }

    /** Deletes the cached content of the articles. They behave like articles that were never opened. */
    void clearContent(List<Integer> articleIds) {
        database.update(ARTICLE)
                .setNull(ARTICLE.RAW_HEADERS)
                .setNull(ARTICLE.BODY_TEXT)
                .setNull(ARTICLE.BODY_FLOWED)
                .setNull(ARTICLE.BODY_DELSP)
                .setNull(ARTICLE.BODY_TRUNCATED)
                .setNull(ARTICLE.OMITTED_PARTS)
                .setNull(ARTICLE.CONTENT_FETCHED_AT)
                .where(ARTICLE.ID.in(articleIds))
                .execute();
    }

    private long pragma(String name) {
        return database.fetchOne("PRAGMA " + name).get(0, Long.class);
    }
}
