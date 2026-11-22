package io.github.stekhub.wardroom.groups.internal;

import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE;
import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE_LOCATION;
import static io.github.stekhub.wardroom.jooq.Tables.IGNORE_RULE;
import static io.github.stekhub.wardroom.jooq.Tables.NEWSGROUP;
import static io.github.stekhub.wardroom.jooq.Tables.READ_STATE;
import static io.github.stekhub.wardroom.jooq.Tables.SUBSCRIPTION;
import static io.github.stekhub.wardroom.jooq.Tables.SYNCED_RANGE;

import io.github.stekhub.wardroom.groups.SubscribedGroups.SubscribedGroup;
import io.github.stekhub.wardroom.ignoring.IgnoreFilters;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Select;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Database access for subscriptions, and for what a subscription owns of the articles: locations, read state and
 * synchronized ranges (data model, lifecycle rules).
 */
@Repository
@RequiredArgsConstructor
class SubscriptionRepository {

    private final DSLContext database;

    /**
     * A subscribed group as the sidebar shows it.
     *
     * @param description   {@code null} when the server gave none
     * @param removed       whether the server no longer lists the group
     * @param unreadCount   the unread articles of the group, without what the owner ignores (spec 010)
     * @param lastSyncedAt  the time of the last successful synchronization in milliseconds, or {@code null}
     * @param lastSyncError why the last synchronization failed, or {@code null}
     */
    record StoredSubscription(long groupId, long serverId, String name, String description,
                              PostingStatus postingStatus, boolean removed, int unreadCount, Long lastSyncedAt,
                              String lastSyncError) {
    }

    /** What subscribing and unsubscribing need to know about a group. */
    record GroupState(boolean removed, boolean subscribed) {
    }

    /** @return the state of the group, or empty when no group has this ID */
    Optional<GroupState> findGroupState(long groupId) {
        return database.select(NEWSGROUP.REMOVED_AT, SUBSCRIPTION.ID)
                .from(NEWSGROUP)
                .leftJoin(SUBSCRIPTION).on(SUBSCRIPTION.NEWSGROUP_ID.eq(NEWSGROUP.ID))
                .where(NEWSGROUP.ID.eq((int) groupId))
                .fetchOptional(row -> new GroupState(row.value1() != null, row.value2() != null));
    }

    /** @return all subscribed groups, ordered by name */
    List<StoredSubscription> findAll() {
        // Without ignore rules, which is the usual case, the count needs no look at authors and threads.
        if (database.fetchExists(IGNORE_RULE)) {
            return findAll(DSL.val(0)).stream()
                    .map(subscription -> new StoredSubscription(subscription.groupId(), subscription.serverId(),
                            subscription.name(), subscription.description(), subscription.postingStatus(),
                            subscription.removed(), countUnreadNotIgnored(subscription.groupId()),
                            subscription.lastSyncedAt(), subscription.lastSyncError()))
                    .toList();
        }
        return findAll(DSL.field(DSL.selectCount()
                .from(ARTICLE_LOCATION)
                .join(ARTICLE).on(ARTICLE.ID.eq(ARTICLE_LOCATION.ARTICLE_ID))
                .where(ARTICLE_LOCATION.NEWSGROUP_ID.eq(NEWSGROUP.ID))
                .and(unread())));
    }

    /**
     * The unread articles of a group that the owner does not ignore (spec 010). Not counted are:
     *
     * <ul>
     * <li>articles by an ignored author,</li>
     * <li>the articles of an ignored thread,</li>
     * <li>the articles of a thread whose card article is by an ignored author, because the thread list hides
     * that thread.</li>
     * </ul>
     */
    private int countUnreadNotIgnored(long groupId) {
        // An article without a place yet is a thread of its own until it is threaded.
        Field<Integer> threadRootOfArticle = DSL.coalesce(ARTICLE.THREAD_ROOT_ID, ARTICLE.ID);
        Field<Boolean> authorIgnored = DSL.field(IgnoreFilters.authorIgnored(ARTICLE.AUTHOR_KEY));
        // The card article of a thread in a group (spec 006, "A thread in a group"): the thread root when it is
        // among these articles, otherwise the oldest of them.
        Field<Boolean> cardAuthorIgnored = DSL.firstValue(authorIgnored).over()
                .partitionBy(threadRootOfArticle)
                .orderBy(DSL.when(ARTICLE.ID.eq(threadRootOfArticle), 0).otherwise(1),
                        DSL.when(ARTICLE.DATE.isNull(), 1).otherwise(0),
                        ARTICLE.DATE,
                        ARTICLE_LOCATION.ARTICLE_NUMBER);
        Field<Boolean> unread = DSL.field(DSL.notExists(DSL.selectOne().from(READ_STATE)
                .where(READ_STATE.ARTICLE_ID.eq(ARTICLE.ID))));
        Table<?> articlesOfGroup = database.select(
                        threadRootOfArticle.as("thread_root"),
                        unread.as("unread"),
                        authorIgnored.as("author_ignored"),
                        cardAuthorIgnored.as("card_author_ignored"))
                .from(ARTICLE_LOCATION)
                .join(ARTICLE).on(ARTICLE.ID.eq(ARTICLE_LOCATION.ARTICLE_ID))
                .where(ARTICLE_LOCATION.NEWSGROUP_ID.eq((int) groupId))
                .and(ARTICLE.IS_STUB.isFalse())
                .asTable("articles_of_group");
        return database.fetchCount(DSL.selectOne()
                .from(articlesOfGroup)
                .where(articlesOfGroup.field("unread", Boolean.class).isTrue())
                .and(articlesOfGroup.field("author_ignored", Boolean.class).isFalse())
                .and(articlesOfGroup.field("card_author_ignored", Boolean.class).isFalse())
                .andNot(IgnoreFilters.threadIgnored(articlesOfGroup.field("thread_root", Integer.class))));
    }

    private List<StoredSubscription> findAll(Field<Integer> unreadCount) {
        return database.select(NEWSGROUP.ID, NEWSGROUP.SERVER_ID, NEWSGROUP.NAME, NEWSGROUP.DESCRIPTION,
                        NEWSGROUP.POSTING_STATUS, NEWSGROUP.REMOVED_AT, unreadCount, NEWSGROUP.LAST_SYNCED_AT,
                        NEWSGROUP.LAST_SYNC_ERROR)
                .from(SUBSCRIPTION)
                .join(NEWSGROUP).on(NEWSGROUP.ID.eq(SUBSCRIPTION.NEWSGROUP_ID))
                .orderBy(NEWSGROUP.NAME)
                .fetch(row -> new StoredSubscription(row.value1(), row.value2(), row.value3(), row.value4(),
                        PostingStatus.valueOf(row.value5()), row.value6() != null, row.value7(), row.value8(),
                        row.value9()));
    }

    /** @return the subscribed groups as the synchronization needs them, ordered by name */
    List<SubscribedGroup> findSubscribedGroups() {
        return database.select(NEWSGROUP.ID, NEWSGROUP.SERVER_ID, NEWSGROUP.NAME)
                .from(SUBSCRIPTION)
                .join(NEWSGROUP).on(NEWSGROUP.ID.eq(SUBSCRIPTION.NEWSGROUP_ID))
                .orderBy(NEWSGROUP.NAME)
                .fetch(row -> new SubscribedGroup(row.value1(), row.value2(), row.value3()));
    }

    /** @return the group, or empty when it is not subscribed or no group has this ID */
    Optional<SubscribedGroup> findSubscribedGroup(long groupId) {
        return database.select(NEWSGROUP.ID, NEWSGROUP.SERVER_ID, NEWSGROUP.NAME)
                .from(SUBSCRIPTION)
                .join(NEWSGROUP).on(NEWSGROUP.ID.eq(SUBSCRIPTION.NEWSGROUP_ID))
                .where(NEWSGROUP.ID.eq((int) groupId))
                .fetchOptional(row -> new SubscribedGroup(row.value1(), row.value2(), row.value3()));
    }

    /**
     * Stores the subscription. A group that is subscribed already stays as it is. What an earlier subscription
     * left about its synchronizations is cleared: the new one starts with a first synchronization.
     */
    void subscribe(long groupId) {
        int added = database.insertInto(SUBSCRIPTION, SUBSCRIPTION.NEWSGROUP_ID)
                .values((int) groupId)
                .onConflict(SUBSCRIPTION.NEWSGROUP_ID)
                .doNothing()
                .execute();
        if (added > 0) {
            database.update(NEWSGROUP)
                    .setNull(NEWSGROUP.LAST_SYNCED_AT)
                    .setNull(NEWSGROUP.LAST_SYNC_ERROR)
                    .where(NEWSGROUP.ID.eq((int) groupId))
                    .execute();
        }
    }

    void setWatermarks(long groupId, long lowWatermark, long highWatermark) {
        database.update(NEWSGROUP)
                .set(NEWSGROUP.LOW_WATERMARK, lowWatermark)
                .set(NEWSGROUP.HIGH_WATERMARK, highWatermark)
                .where(NEWSGROUP.ID.eq((int) groupId))
                .execute();
    }

    void setSynchronized(long groupId, long syncedAt) {
        database.update(NEWSGROUP)
                .set(NEWSGROUP.LAST_SYNCED_AT, syncedAt)
                .setNull(NEWSGROUP.LAST_SYNC_ERROR)
                .where(NEWSGROUP.ID.eq((int) groupId))
                .execute();
    }

    void setSynchronizationError(long groupId, String error) {
        database.update(NEWSGROUP)
                .set(NEWSGROUP.LAST_SYNC_ERROR, error)
                .where(NEWSGROUP.ID.eq((int) groupId))
                .execute();
    }

    /**
     * Steps 2 to 4 of the unsubscribe cleanup, for a group that stays subscribed: the server has renumbered it
     * (spec 005). To be called in one transaction.
     */
    void removeArticles(long groupId) {
        deleteArticleData(DSL.select(NEWSGROUP.ID).from(NEWSGROUP).where(NEWSGROUP.ID.eq((int) groupId)));
    }

    /**
     * The unsubscribe cleanup of the data model: deletes the subscription, the locations of the group, the articles
     * that have no location left, with their read state, and the synchronized ranges of the group. An article that
     * also has a location in another group is kept. Stubs are left to the threading
     * ({@link io.github.stekhub.wardroom.groups.ThreadRecomputation}). To be called in one transaction.
     */
    void unsubscribe(long groupId) {
        Condition ofTheGroup = NEWSGROUP.ID.eq((int) groupId);
        deleteGroupData(DSL.select(NEWSGROUP.ID).from(NEWSGROUP).where(ofTheGroup));
    }

    /** Deletes what {@link #unsubscribe(long)} deletes, for all groups of the server. */
    void deleteByServer(long serverId) {
        deleteGroupData(DSL.select(NEWSGROUP.ID).from(NEWSGROUP).where(NEWSGROUP.SERVER_ID.eq((int) serverId)));
    }

    /**
     * Marks every article of the group as read that is not a stub and not read yet, in one statement. The read
     * state belongs to the article, so a crossposted article is read in its other groups as well (ADR 0012).
     *
     * @return how many articles were marked
     */
    int markAllRead(long groupId, long now) {
        return database.insertInto(READ_STATE, READ_STATE.ARTICLE_ID, READ_STATE.READ_AT)
                .select(DSL.select(ARTICLE.ID, DSL.val(now))
                        .from(ARTICLE_LOCATION)
                        .join(ARTICLE).on(ARTICLE.ID.eq(ARTICLE_LOCATION.ARTICLE_ID))
                        .where(ARTICLE_LOCATION.NEWSGROUP_ID.eq((int) groupId))
                        .and(unread()))
                .execute();
    }

    private void deleteGroupData(Select<Record1<Integer>> groupIds) {
        database.deleteFrom(SUBSCRIPTION).where(SUBSCRIPTION.NEWSGROUP_ID.in(groupIds)).execute();
        deleteArticleData(groupIds);
    }

    private void deleteArticleData(Select<Record1<Integer>> groupIds) {
        database.deleteFrom(ARTICLE_LOCATION).where(ARTICLE_LOCATION.NEWSGROUP_ID.in(groupIds)).execute();
        // The read state goes with its article (ON DELETE CASCADE). An article the owner posted from another
        // group stays while it waits for its location there (spec 009).
        database.deleteFrom(ARTICLE)
                .where(ARTICLE.IS_STUB.isFalse())
                .and(ARTICLE.POSTED_FROM_NEWSGROUP_ID.isNull().or(ARTICLE.POSTED_FROM_NEWSGROUP_ID.in(groupIds)))
                .andNotExists(DSL.selectOne().from(ARTICLE_LOCATION)
                        .where(ARTICLE_LOCATION.ARTICLE_ID.eq(ARTICLE.ID)))
                .execute();
        database.deleteFrom(SYNCED_RANGE).where(SYNCED_RANGE.NEWSGROUP_ID.in(groupIds)).execute();
    }

    /**
     * An article counts as unread when it is not a stub and has no read state. Whether its location has expired
     * does not matter: the cached content stays readable (spec 004).
     */
    private static Condition unread() {
        return ARTICLE.IS_STUB.isFalse()
                .andNotExists(DSL.selectOne().from(READ_STATE).where(READ_STATE.ARTICLE_ID.eq(ARTICLE.ID)));
    }
}
