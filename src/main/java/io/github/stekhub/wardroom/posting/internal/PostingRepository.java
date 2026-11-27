package io.github.stekhub.wardroom.posting.internal;

import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE;
import static io.github.stekhub.wardroom.jooq.Tables.NEWSGROUP;
import static io.github.stekhub.wardroom.jooq.Tables.NNTP_SERVER;
import static io.github.stekhub.wardroom.jooq.Tables.READ_STATE;

import io.github.stekhub.wardroom.articles.AuthorKey;
import io.github.stekhub.wardroom.ignoring.SubjectWords;
import io.github.stekhub.wardroom.posting.internal.ArticleComposer.Composed;
import io.github.stekhub.wardroom.posting.internal.PostingIdentityService.Identity;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Database access for posting: the identity of a server, what a post is built from, and the stored post. */
@Repository
@RequiredArgsConstructor
class PostingRepository {

    private static final String POSTING_NOT_ALLOWED = "NOT_ALLOWED";

    private final DSLContext database;

    /**
     * The article a reply answers.
     *
     * @param references untrusted text, or {@code null}
     * @param rawHeaders the header block as received, or {@code null} when the content was never fetched
     */
    record Parent(long articleId, String messageId, boolean stub, String references, String rawHeaders) {
    }

    /** An article that is stored. */
    record StoredArticle(long articleId, long threadRootId) {
    }

    /** @return the identity of the server, with empty fields when none was set; empty when no server has this ID */
    Optional<Identity> findIdentity(long serverId) {
        return database.select(NNTP_SERVER.POSTING_NAME, NNTP_SERVER.POSTING_EMAIL, NNTP_SERVER.POSTING_SIGNATURE)
                .from(NNTP_SERVER)
                .where(NNTP_SERVER.ID.eq((int) serverId))
                .fetchOptional(row -> new Identity(row.value1(), row.value2(), row.value3()));
    }

    /** @return {@code false} when no server has this ID */
    boolean storeIdentity(long serverId, Identity identity) {
        return database.update(NNTP_SERVER)
                .set(NNTP_SERVER.POSTING_NAME, identity.name())
                .set(NNTP_SERVER.POSTING_EMAIL, identity.email())
                .set(NNTP_SERVER.POSTING_SIGNATURE, identity.signature())
                .where(NNTP_SERVER.ID.eq((int) serverId))
                .execute() > 0;
    }

    /** @return whether the group accepts articles, moderated or not; {@code false} when no group has this ID */
    boolean postingAllowed(long groupId) {
        return database.fetchExists(NEWSGROUP, NEWSGROUP.ID.eq((int) groupId)
                .and(NEWSGROUP.POSTING_STATUS.ne(POSTING_NOT_ALLOWED)));
    }

    /** @return the article or stub, or empty when no article has this ID */
    Optional<Parent> findParent(long articleId) {
        return database.select(ARTICLE.ID, ARTICLE.MESSAGE_ID, ARTICLE.IS_STUB, ARTICLE.REFERENCES,
                        ARTICLE.RAW_HEADERS)
                .from(ARTICLE)
                .where(ARTICLE.ID.eq((int) articleId))
                .fetchOptional(row -> new Parent(row.value1(), row.value2(), Boolean.TRUE.equals(row.value3()),
                        row.value4(), row.value5()));
    }

    /** @return the stored article with this Message-ID that is no stub, or empty */
    Optional<StoredArticle> findArticle(String messageId) {
        return database.select(ARTICLE.ID, DSL.coalesce(ARTICLE.THREAD_ROOT_ID, ARTICLE.ID))
                .from(ARTICLE)
                .where(ARTICLE.MESSAGE_ID.eq(messageId))
                .and(ARTICLE.IS_STUB.isFalse())
                .fetchOptional(row -> new StoredArticle(row.value1(), row.value2()));
    }

    /**
     * Stores an article the server accepted, with its content, as posted from the group and read. It has no place
     * in a thread yet. A stub with its Message-ID, which a reply that arrived first can have left, is filled in.
     *
     * @return the ID of the article
     */
    long storePostedArticle(long groupId, String messageId, Composed composed, String references, long postedAt) {
        Integer stubId = database.select(ARTICLE.ID).from(ARTICLE).where(ARTICLE.MESSAGE_ID.eq(messageId))
                .fetchOne(ARTICLE.ID);
        int articleId = stubId != null ? stubId : database.insertInto(ARTICLE)
                .set(ARTICLE.MESSAGE_ID, messageId)
                .set(ARTICLE.IS_STUB, false)
                .returningResult(ARTICLE.ID)
                .fetchOne()
                .value1();
        database.update(ARTICLE)
                .set(ARTICLE.IS_STUB, false)
                // Without a thread root the article has no place: its own References decide it (spec 006).
                .setNull(ARTICLE.THREAD_ROOT_ID)
                .set(ARTICLE.SUBJECT, composed.subject())
                .set(ARTICLE.SUBJECT_WORDS, SubjectWords.of(composed.subject()))
                .set(ARTICLE.FROM, composed.from())
                .set(ARTICLE.AUTHOR_KEY, AuthorKey.of(composed.from()))
                .set(ARTICLE.DATE, postedAt)
                .set(ARTICLE.REFERENCES, references)
                .set(ARTICLE.BYTES, composed.bytes())
                .set(ARTICLE.LINES, composed.lines())
                .set(ARTICLE.RAW_HEADERS, composed.headers())
                // Stored text has line feeds as line endings, as the text of a fetched article has.
                .set(ARTICLE.BODY_TEXT, composed.body().replace("\r\n", "\n"))
                .set(ARTICLE.BODY_FLOWED, true)
                .set(ARTICLE.BODY_DELSP, false)
                .set(ARTICLE.BODY_TRUNCATED, false)
                .set(ARTICLE.OMITTED_PARTS, "[]")
                .set(ARTICLE.CONTENT_FETCHED_AT, postedAt)
                .setNull(ARTICLE.CONTENT_UNAVAILABLE_AT)
                .set(ARTICLE.POSTED_FROM_NEWSGROUP_ID, (int) groupId)
                .where(ARTICLE.ID.eq(articleId))
                .execute();
        database.insertInto(READ_STATE, READ_STATE.ARTICLE_ID, READ_STATE.READ_AT)
                .values(articleId, postedAt)
                .onConflict(READ_STATE.ARTICLE_ID)
                .doNothing()
                .execute();
        return articleId;
    }

    /** @return the root of the thread of the article, as it is now */
    long findThreadRootId(long articleId) {
        return database.select(DSL.coalesce(ARTICLE.THREAD_ROOT_ID, ARTICLE.ID))
                .from(ARTICLE)
                .where(ARTICLE.ID.eq((int) articleId))
                .fetchSingle()
                .value1();
    }
}
