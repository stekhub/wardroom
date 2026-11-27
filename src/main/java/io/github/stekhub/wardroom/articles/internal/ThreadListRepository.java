package io.github.stekhub.wardroom.articles.internal;

import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE;
import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE_LOCATION;
import static io.github.stekhub.wardroom.jooq.Tables.IGNORE_RULE;
import static io.github.stekhub.wardroom.jooq.Tables.NEWSGROUP;
import static io.github.stekhub.wardroom.jooq.Tables.READ_STATE;
import static io.github.stekhub.wardroom.jooq.Tables.SYNCED_RANGE;

import io.github.stekhub.wardroom.ignoring.IgnoreFilters;
import io.github.stekhub.wardroom.jooq.tables.Article;
import io.github.stekhub.wardroom.jooq.tables.ArticleLocation;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Database access for the discussion view: the thread cards of a group, the articles of one thread, and "mark
 * thread as read" (spec 008). The figures of a thread follow spec 006, "A thread in a group".
 *
 * <p>What the owner ignores is left out of the cards and of their unread counts (spec 010). The thread page shows
 * everything and says what is ignored.
 */
@Repository
@RequiredArgsConstructor
class ThreadListRepository {

    /**
     * How much of the text of a card article is read for its excerpt. The excerpt has 200 characters; the rest is
     * room for quoted lines that it leaves out.
     */
    private static final int EXCERPT_SOURCE_LENGTH = 20_000;

    /** A thread without a date sorts as the oldest (spec 008, "Sorting"). */
    private static final long NO_DATE = Long.MIN_VALUE;

    private final DSLContext database;

    /**
     * A thread as its card shows it.
     *
     * @param threadRootId   the topmost article of the thread, which can be a stub
     * @param cardArticleId  the article whose subject, author and date stand for the thread
     * @param subject        of the card article; untrusted text, or {@code null}
     * @param from           of the card article; untrusted text, or {@code null}
     * @param authorKey      of the card article, or {@code null}
     * @param date           of the card article, or {@code null} when it cannot be read
     * @param textStart      the beginning of the cached text of the card article, or {@code null}
     * @param messageCount   the articles of the thread that are located in the group and are no stubs
     * @param latestActivity the newest date among those articles, or {@code null} when none has a date
     * @param unreadCount    how many of those articles are not read and not by an ignored author
     * @param firstKey       the first sort key of the thread in the order that was asked for
     * @param secondKey      the second sort key; the thread root in an order that has none
     */
    record ThreadCard(long threadRootId, long cardArticleId, String subject, String from, String authorKey,
                      Long date, String textStart, int messageCount, Long latestActivity, int unreadCount,
                      long firstKey, long secondKey) {
    }

    /**
     * A synchronized range of a group, as a thread list needs it.
     *
     * @param atLowWatermark whether the range begins at the low watermark of the server, so nothing older exists
     * @param newest         whether the group has no range with higher article numbers
     */
    record ListedRange(long rangeId, long firstNumber, long lastNumber, boolean atLowWatermark, boolean newest) {
    }

    /**
     * An article or stub of a thread, as the thread page of a group needs it.
     *
     * @param parentId      the article it answers, or {@code null}
     * @param subject       untrusted text; {@code null} for a stub
     * @param from          untrusted text; {@code null} for a stub
     * @param authorKey     {@code null} for a stub
     * @param ignoredAuthor whether the owner ignores the author
     * @param numberInGroup its article number in the group, or {@code null} when it is not located there
     * @param otherGroup    the name of a group it is located in, when it is not located in this group but
     *                      elsewhere; otherwise {@code null}
     * @param sent          whether the owner posted it from this group and the server does not list it there yet
     *                      (spec 009)
     */
    record ThreadArticle(long articleId, Long parentId, boolean stub, String subject, String from,
                         String authorKey, boolean ignoredAuthor, Long date, boolean read, Long numberInGroup,
                         String otherGroup, boolean sent) {
    }

    /**
     * One page of the threads of a group: every thread that has an article located in the group that is no stub.
     * Left out are the threads the owner ignores, the threads whose card article is by an ignored author, and
     * the threads whose card article has an ignored word in its subject (spec 023).
     *
     * @param until  only threads whose card article is dated before this time; {@code null} for all
     * @param range  only threads whose card article has a number in this range; {@code null} for all
     * @param cursor the place after which the page begins; {@code null} for the first page
     * @param limit  how many cards are returned at most
     */
    List<ThreadCard> findCards(long groupId, ThreadOrder order, Long until, ListedRange range, ThreadCursor cursor,
            int limit) {
        return queryCards(groupId, order, until, range, cursor, false, null, limit);
    }

    /**
     * The first thread of the list after the cursor that has an unread article (spec 014). The list and its order are
     * those of {@link #findCards}, so what the owner ignores is skipped.
     *
     * @param cursor the place after which the search begins; {@code null} for the top of the list
     * @return the card of that thread, or empty when no thread after the cursor has an unread article
     */
    Optional<ThreadCard> findNextUnreadCard(long groupId, ThreadOrder order, Long until, ListedRange range,
            ThreadCursor cursor) {
        return queryCards(groupId, order, until, range, cursor, true, null, 1).stream().findFirst();
    }

    /**
     * @return the card of the thread as the list shows it, with its sort keys; empty when the list does not show it
     */
    Optional<ThreadCard> findCardOfThread(long groupId, ThreadOrder order, Long until, ListedRange range,
            long threadRootId) {
        return queryCards(groupId, order, until, range, null, false, threadRootId, 1).stream().findFirst();
    }

    /**
     * @param onlyUnread   whether only threads with an unread article are returned
     * @param threadRootId only this thread; {@code null} for all
     */
    private List<ThreadCard> queryCards(long groupId, ThreadOrder order, Long until, ListedRange range,
            ThreadCursor cursor, boolean onlyUnread, Long threadRootId, int limit) {
        // An article without a place yet is a thread of its own until it is threaded.
        Field<Integer> threadRootOfArticle = DSL.coalesce(ARTICLE.THREAD_ROOT_ID, ARTICLE.ID);
        // The card article: the thread root when it is among these articles, otherwise the oldest of them.
        Field<Integer> positionInThread = DSL.rowNumber().over()
                .partitionBy(threadRootOfArticle)
                .orderBy(DSL.when(ARTICLE.ID.eq(threadRootOfArticle), 0).otherwise(1),
                        DSL.when(ARTICLE.DATE.isNull(), 1).otherwise(0),
                        ARTICLE.DATE,
                        ARTICLE_LOCATION.ARTICLE_NUMBER);
        Condition authorIgnored = IgnoreFilters.authorIgnored(ARTICLE.AUTHOR_KEY);
        Table<?> articlesOfGroup = database.select(
                        threadRootOfArticle.as("thread_root"),
                        ARTICLE.ID.as("article_id"),
                        ARTICLE.DATE.as("card_date"),
                        ARTICLE_LOCATION.ARTICLE_NUMBER.as("card_number"),
                        DSL.count().over().partitionBy(threadRootOfArticle).as("message_count"),
                        DSL.max(ARTICLE.DATE).over().partitionBy(threadRootOfArticle).as("latest_activity"),
                        DSL.count(DSL.when(READ_STATE.ID.isNull().andNot(authorIgnored), 1)).over()
                                .partitionBy(threadRootOfArticle).as("unread_count"),
                        DSL.field(authorIgnored).as("author_ignored"),
                        DSL.field(IgnoreFilters.subjectIgnored(ARTICLE.SUBJECT_WORDS, groupId))
                                .as("subject_ignored"),
                        positionInThread.as("position"))
                .from(ARTICLE_LOCATION)
                .join(ARTICLE).on(ARTICLE.ID.eq(ARTICLE_LOCATION.ARTICLE_ID))
                .leftJoin(READ_STATE).on(READ_STATE.ARTICLE_ID.eq(ARTICLE.ID))
                .where(ARTICLE_LOCATION.NEWSGROUP_ID.eq((int) groupId))
                .and(ARTICLE.IS_STUB.isFalse())
                .asTable("articles_of_group");
        Field<Integer> threadRoot = articlesOfGroup.field("thread_root", Integer.class);
        Field<Integer> cardArticleId = articlesOfGroup.field("article_id", Integer.class);
        Field<Long> cardDate = articlesOfGroup.field("card_date", Long.class);
        Field<Long> cardNumber = articlesOfGroup.field("card_number", Long.class);
        Field<Integer> messageCount = articlesOfGroup.field("message_count", Integer.class);
        Field<Long> latestActivity = articlesOfGroup.field("latest_activity", Long.class);
        Field<Integer> unreadCount = articlesOfGroup.field("unread_count", Integer.class);

        Field<Long> firstKey = switch (order) {
            case NEWEST, UNTIL_DATE -> DSL.coalesce(cardDate, NO_DATE);
            case ACTIVITY -> DSL.coalesce(latestActivity, NO_DATE);
            case DISCUSSED -> messageCount.cast(Long.class);
        };
        // An order with one key repeats the thread root: a constant is not allowed in ORDER BY.
        Field<Long> secondKey = order == ThreadOrder.DISCUSSED
                ? DSL.coalesce(latestActivity, NO_DATE)
                : threadRoot.cast(Long.class);

        Condition shown = articlesOfGroup.field("position", Integer.class).eq(1)
                .and(articlesOfGroup.field("author_ignored", Boolean.class).isFalse())
                .and(articlesOfGroup.field("subject_ignored", Boolean.class).isFalse())
                .andNot(IgnoreFilters.threadIgnored(threadRoot));
        if (until != null) {
            shown = shown.and(cardDate.lt(until));
        }
        if (range != null) {
            shown = shown.and(cardNumber.between(range.firstNumber(), range.lastNumber()));
        }
        if (onlyUnread) {
            shown = shown.and(unreadCount.gt(0));
        }
        if (threadRootId != null) {
            shown = shown.and(threadRoot.eq(threadRootId.intValue()));
        }
        if (cursor != null) {
            // The keys and the thread root together are unique, so no thread is skipped or shown twice.
            shown = shown.and(DSL.row(firstKey, secondKey, threadRoot)
                    .lt(cursor.firstKey(), cursor.secondKey(), (int) cursor.threadRootId()));
        }

        Article cardArticle = ARTICLE.as("card_article");
        return database.select(threadRoot, cardArticleId, cardArticle.SUBJECT, cardArticle.FROM,
                        cardArticle.AUTHOR_KEY, cardDate,
                        DSL.substring(cardArticle.BODY_TEXT, 1, EXCERPT_SOURCE_LENGTH), messageCount,
                        latestActivity, unreadCount, firstKey, secondKey)
                .from(articlesOfGroup)
                .join(cardArticle).on(cardArticle.ID.eq(cardArticleId))
                .where(shown)
                .orderBy(firstKey.desc(), secondKey.desc(), threadRoot.desc())
                .limit(limit)
                .fetch(row -> new ThreadCard(row.value1(), row.value2(), row.value3(), row.value4(), row.value5(),
                        row.value6(), row.value7(), row.value8(), row.value9(), row.value10(), row.value11(),
                        row.value12()));
    }

    /** @return the range of the group with the highest article numbers, or empty when the group has none */
    Optional<ListedRange> findNewestRange(long groupId) {
        return findRanges(groupId).stream().findFirst();
    }

    /** @return the range, or empty when the group has no range with this ID */
    Optional<ListedRange> findRange(long groupId, long rangeId) {
        return findRanges(groupId).stream().filter(range -> range.rangeId() == rangeId).findFirst();
    }

    /** @return the ranges of the group, the one with the highest article numbers first. A group has few. */
    private List<ListedRange> findRanges(long groupId) {
        Integer newestRangeId = database.select(SYNCED_RANGE.ID)
                .from(SYNCED_RANGE)
                .where(SYNCED_RANGE.NEWSGROUP_ID.eq((int) groupId))
                .orderBy(SYNCED_RANGE.LAST_NUMBER.desc())
                .limit(1)
                .fetchOne(SYNCED_RANGE.ID);
        return database.select(SYNCED_RANGE.ID, SYNCED_RANGE.FIRST_NUMBER, SYNCED_RANGE.LAST_NUMBER,
                        NEWSGROUP.LOW_WATERMARK)
                .from(SYNCED_RANGE)
                .join(NEWSGROUP).on(NEWSGROUP.ID.eq(SYNCED_RANGE.NEWSGROUP_ID))
                .where(SYNCED_RANGE.NEWSGROUP_ID.eq((int) groupId))
                .orderBy(SYNCED_RANGE.LAST_NUMBER.desc())
                .fetch(row -> new ListedRange(row.value1(), row.value2(), row.value3(),
                        // A server may report a low watermark of 0; article numbers begin at 1.
                        row.value2() <= Math.max(1, row.value4() == null ? 1 : row.value4()),
                        row.value1().equals(newestRangeId)));
    }

    /**
     * @return every article and stub of the thread, in no particular order; empty when no article has this thread
     *         root
     */
    List<ThreadArticle> findThreadArticles(long threadRootId, long groupId) {
        ArticleLocation locationInGroup = ARTICLE_LOCATION.as("location_in_group");
        Field<Boolean> read = DSL.field(DSL.exists(DSL.selectOne().from(READ_STATE)
                .where(READ_STATE.ARTICLE_ID.eq(ARTICLE.ID))));
        Field<String> anyGroup = DSL.field(DSL.select(DSL.min(NEWSGROUP.NAME))
                .from(ARTICLE_LOCATION)
                .join(NEWSGROUP).on(NEWSGROUP.ID.eq(ARTICLE_LOCATION.NEWSGROUP_ID))
                .where(ARTICLE_LOCATION.ARTICLE_ID.eq(ARTICLE.ID)));
        Field<Boolean> ignoredAuthor = DSL.field(IgnoreFilters.authorIgnored(ARTICLE.AUTHOR_KEY));
        return database.select(ARTICLE.ID, ARTICLE.PARENT_ID, ARTICLE.IS_STUB, ARTICLE.SUBJECT, ARTICLE.FROM,
                        ARTICLE.AUTHOR_KEY, ignoredAuthor, ARTICLE.DATE, read, locationInGroup.ARTICLE_NUMBER,
                        anyGroup, ARTICLE.POSTED_FROM_NEWSGROUP_ID)
                .from(ARTICLE)
                .leftJoin(locationInGroup).on(locationInGroup.ARTICLE_ID.eq(ARTICLE.ID)
                        .and(locationInGroup.NEWSGROUP_ID.eq((int) groupId)))
                .where(ARTICLE.THREAD_ROOT_ID.eq((int) threadRootId).or(ARTICLE.ID.eq((int) threadRootId)))
                .fetch(row -> new ThreadArticle(row.value1(),
                        row.value2() == null ? null : row.value2().longValue(), Boolean.TRUE.equals(row.value3()),
                        row.value4(), row.value5(), row.value6(), Boolean.TRUE.equals(row.value7()),
                        row.value8(), Boolean.TRUE.equals(row.value9()), row.value10(),
                        row.value10() == null ? row.value11() : null,
                        row.value10() == null && row.value12() != null && row.value12() == groupId));
    }

    /**
     * @return the rules that ignore the thread: those with the Message-ID of one of its articles. Empty when the
     *         thread is not ignored
     */
    List<Long> findIgnoreRuleIds(long threadRootId) {
        return database.selectDistinct(IGNORE_RULE.ID)
                .from(IGNORE_RULE)
                .join(ARTICLE).on(ARTICLE.MESSAGE_ID.eq(IGNORE_RULE.VALUE))
                .where(IGNORE_RULE.KIND.eq(IgnoreFilters.THREAD))
                .and(ARTICLE.THREAD_ROOT_ID.eq((int) threadRootId).or(ARTICLE.ID.eq((int) threadRootId)))
                .orderBy(IGNORE_RULE.ID)
                .fetch(row -> row.value1().longValue());
    }

    /**
     * @return the ignored word that hides the thread in the lists of the group, as the owner typed it: the oldest
     *         rule that matches the subject of the card article (spec 023). Empty when no word hides the thread
     */
    Optional<String> findIgnoredWord(long threadRootId, long groupId) {
        // The card article: the thread root when it is located in the group, otherwise the oldest article there.
        Field<String> cardSubjectWords = DSL.field(DSL.select(ARTICLE.SUBJECT_WORDS)
                .from(ARTICLE_LOCATION)
                .join(ARTICLE).on(ARTICLE.ID.eq(ARTICLE_LOCATION.ARTICLE_ID))
                .where(ARTICLE_LOCATION.NEWSGROUP_ID.eq((int) groupId))
                .and(ARTICLE.THREAD_ROOT_ID.eq((int) threadRootId).or(ARTICLE.ID.eq((int) threadRootId)))
                .and(ARTICLE.IS_STUB.isFalse())
                .orderBy(DSL.when(ARTICLE.ID.eq((int) threadRootId), 0).otherwise(1),
                        DSL.when(ARTICLE.DATE.isNull(), 1).otherwise(0),
                        ARTICLE.DATE,
                        ARTICLE_LOCATION.ARTICLE_NUMBER)
                .limit(1));
        return database.select(IGNORE_RULE.LABEL)
                .from(IGNORE_RULE)
                .where(IgnoreFilters.subjectRuleMatches(cardSubjectWords, groupId))
                .orderBy(IGNORE_RULE.ID)
                .limit(1)
                .fetchOptional(IGNORE_RULE.LABEL);
    }

    /**
     * Marks every article of the thread as read that is located in the group, is no stub and is not read yet. The
     * read state belongs to the article, so a crossposted article is read in its other groups as well (ADR 0012).
     */
    void markThreadRead(long threadRootId, long groupId, long readAt) {
        database.insertInto(READ_STATE, READ_STATE.ARTICLE_ID, READ_STATE.READ_AT)
                .select(DSL.select(ARTICLE.ID, DSL.val(readAt))
                        .from(ARTICLE_LOCATION)
                        .join(ARTICLE).on(ARTICLE.ID.eq(ARTICLE_LOCATION.ARTICLE_ID))
                        .where(ARTICLE_LOCATION.NEWSGROUP_ID.eq((int) groupId))
                        .and(ARTICLE.THREAD_ROOT_ID.eq((int) threadRootId).or(ARTICLE.ID.eq((int) threadRootId)))
                        .and(ARTICLE.IS_STUB.isFalse())
                        .andNotExists(DSL.selectOne().from(READ_STATE)
                                .where(READ_STATE.ARTICLE_ID.eq(ARTICLE.ID))))
                .execute();
    }
}
