package io.github.stekhub.wardroom.articles.internal;

import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE;
import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE_LOCATION;
import static io.github.stekhub.wardroom.jooq.Tables.NEWSGROUP;
import static io.github.stekhub.wardroom.jooq.Tables.READ_STATE;

import io.github.stekhub.wardroom.articles.AuthorKey;
import io.github.stekhub.wardroom.articles.internal.ArticleContentExtractor.ArticleHeaders;
import io.github.stekhub.wardroom.articles.internal.ArticleContentExtractor.ExtractedContent;
import io.github.stekhub.wardroom.jooq.tables.Article;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Database access for the content of articles and their read state (spec 007; data model). */
@Repository
@RequiredArgsConstructor
class ArticleContentRepository {

    private final DSLContext database;

    /**
     * What is known of an article before its content is looked at.
     *
     * @param contentFetched       whether the content is cached
     * @param contentUnavailableAt when the server answered that it has no such article, or {@code null}
     */
    record ArticleState(long articleId, String messageId, boolean stub, boolean contentFetched,
                        Long contentUnavailableAt) {
    }

    /** The cached content of an article as it is stored. */
    record StoredContent(String body, boolean flowed, boolean delSp, boolean truncated, String omittedPartsJson,
                         String rawHeaders) {
    }

    Optional<ArticleState> findState(long articleId) {
        return database.select(ARTICLE.ID, ARTICLE.MESSAGE_ID, ARTICLE.IS_STUB, ARTICLE.CONTENT_FETCHED_AT,
                        ARTICLE.CONTENT_UNAVAILABLE_AT)
                .from(ARTICLE)
                .where(ARTICLE.ID.eq((int) articleId))
                .fetchOptional(row -> new ArticleState(row.value1(), row.value2(),
                        Boolean.TRUE.equals(row.value3()), row.value4() != null, row.value5()));
    }

    /** @return the cached content, or empty when the article has none */
    Optional<StoredContent> findContent(long articleId) {
        return database.select(ARTICLE.BODY_TEXT, ARTICLE.BODY_FLOWED, ARTICLE.BODY_DELSP, ARTICLE.BODY_TRUNCATED,
                        ARTICLE.OMITTED_PARTS, ARTICLE.RAW_HEADERS)
                .from(ARTICLE)
                .where(ARTICLE.ID.eq((int) articleId))
                .and(ARTICLE.CONTENT_FETCHED_AT.isNotNull())
                .fetchOptional(row -> new StoredContent(row.value1() == null ? "" : row.value1(),
                        Boolean.TRUE.equals(row.value2()), Boolean.TRUE.equals(row.value3()),
                        Boolean.TRUE.equals(row.value4()), row.value5(), row.value6() == null ? "" : row.value6()));
    }

    /**
     * @return the server that has the article: the one of a group the article is located in; for an article
     *         without a location, such as a stub, the one of a group that another article of its thread is located
     *         in. Empty when neither is known
     */
    Optional<Long> findServerId(long articleId) {
        Optional<Long> serverOfOwnLocation = database.select(NEWSGROUP.SERVER_ID)
                .from(ARTICLE_LOCATION)
                .join(NEWSGROUP).on(NEWSGROUP.ID.eq(ARTICLE_LOCATION.NEWSGROUP_ID))
                .where(ARTICLE_LOCATION.ARTICLE_ID.eq((int) articleId))
                .limit(1)
                .fetchOptional(row -> row.value1().longValue());
        if (serverOfOwnLocation.isPresent()) {
            return serverOfOwnLocation;
        }
        Article threadMember = ARTICLE.as("thread_member");
        return database.select(NEWSGROUP.SERVER_ID)
                .from(ARTICLE)
                .join(threadMember).on(threadMember.THREAD_ROOT_ID.eq(ARTICLE.THREAD_ROOT_ID))
                .join(ARTICLE_LOCATION).on(ARTICLE_LOCATION.ARTICLE_ID.eq(threadMember.ID))
                .join(NEWSGROUP).on(NEWSGROUP.ID.eq(ARTICLE_LOCATION.NEWSGROUP_ID))
                .where(ARTICLE.ID.eq((int) articleId))
                .limit(1)
                .fetchOptional(row -> row.value1().longValue());
    }

    /** Stores the content and clears the mark that the server does not have the article. */
    void storeContent(long articleId, ExtractedContent content, String omittedPartsJson, long fetchedAt) {
        database.update(ARTICLE)
                .set(ARTICLE.RAW_HEADERS, content.rawHeaders())
                .set(ARTICLE.BODY_TEXT, content.body())
                .set(ARTICLE.BODY_FLOWED, content.flowed())
                .set(ARTICLE.BODY_DELSP, content.delSp())
                .set(ARTICLE.BODY_TRUNCATED, content.truncated())
                .set(ARTICLE.OMITTED_PARTS, omittedPartsJson)
                .set(ARTICLE.CONTENT_FETCHED_AT, fetchedAt)
                .setNull(ARTICLE.CONTENT_UNAVAILABLE_AT)
                .where(ARTICLE.ID.eq((int) articleId))
                .execute();
    }

    /**
     * Makes a stub a normal article with what its headers say. It has no place in a thread afterwards: its own
     * headers decide it now (spec 006).
     *
     * @param bytes the size of the article as it was fetched
     * @return {@code false} when the article is no stub any more, and nothing was changed
     */
    boolean fillStub(long articleId, ArticleHeaders headers, long bytes) {
        return database.update(ARTICLE)
                .set(ARTICLE.IS_STUB, false)
                .setNull(ARTICLE.THREAD_ROOT_ID)
                .set(ARTICLE.SUBJECT, headers.subject())
                .set(ARTICLE.FROM, headers.from())
                .set(ARTICLE.AUTHOR_KEY, AuthorKey.of(headers.from()))
                .set(ARTICLE.DATE, headers.date())
                .set(ARTICLE.REFERENCES, headers.references())
                .set(ARTICLE.BYTES, bytes)
                .set(ARTICLE.LINES, headers.lines())
                .where(ARTICLE.ID.eq((int) articleId))
                .and(ARTICLE.IS_STUB.isTrue())
                .execute() > 0;
    }

    /** @return the {@code References} of the article as stored, or {@code null} */
    String findReferences(long articleId) {
        return database.select(ARTICLE.REFERENCES)
                .from(ARTICLE)
                .where(ARTICLE.ID.eq((int) articleId))
                .fetchOne(ARTICLE.REFERENCES);
    }

    void markUnavailable(long articleId, long attemptedAt) {
        database.update(ARTICLE)
                .set(ARTICLE.CONTENT_UNAVAILABLE_AT, attemptedAt)
                .where(ARTICLE.ID.eq((int) articleId))
                .execute();
    }

    /** Marks the article as read. An article that is read already, and a stub, stay as they are. */
    void markRead(long articleId, long readAt) {
        database.insertInto(READ_STATE, READ_STATE.ARTICLE_ID, READ_STATE.READ_AT)
                .select(DSL.select(ARTICLE.ID, DSL.val(readAt))
                        .from(ARTICLE)
                        .where(ARTICLE.ID.eq((int) articleId))
                        .and(ARTICLE.IS_STUB.isFalse()))
                .onConflict(READ_STATE.ARTICLE_ID)
                .doNothing()
                .execute();
    }

    /** Removes the read state of the article (spec 014). An unread article stays as it is. */
    void markUnread(long articleId) {
        database.deleteFrom(READ_STATE)
                .where(READ_STATE.ARTICLE_ID.eq((int) articleId))
                .execute();
    }
}
