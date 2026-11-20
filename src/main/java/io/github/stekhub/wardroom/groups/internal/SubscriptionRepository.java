package io.github.stekhub.wardroom.groups.internal;

import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE;
import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE_LOCATION;
import static io.github.stekhub.wardroom.jooq.Tables.NEWSGROUP;
import static io.github.stekhub.wardroom.jooq.Tables.READ_STATE;
import static io.github.stekhub.wardroom.jooq.Tables.SUBSCRIPTION;
import static io.github.stekhub.wardroom.jooq.Tables.SYNCED_RANGE;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Select;
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
        Field<Integer> unreadCount = DSL.field(DSL.selectCount()
                .from(ARTICLE_LOCATION)
                .join(ARTICLE).on(ARTICLE.ID.eq(ARTICLE_LOCATION.ARTICLE_ID))
                .where(ARTICLE_LOCATION.NEWSGROUP_ID.eq(NEWSGROUP.ID))
                .and(unread()));
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

    /** Stores the subscription. A group that is subscribed already stays as it is. */
    void subscribe(long groupId) {
        database.insertInto(SUBSCRIPTION, SUBSCRIPTION.NEWSGROUP_ID)
                .values((int) groupId)
                .onConflict(SUBSCRIPTION.NEWSGROUP_ID)
                .doNothing()
                .execute();
    }

    /**
     * The unsubscribe cleanup of the data model: deletes the subscription, the locations of the group, the articles
     * that have no location left, with their read state, and the synchronized ranges of the group. An article that
     * also has a location in another group is kept. To be called in one transaction.
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
        database.deleteFrom(ARTICLE_LOCATION).where(ARTICLE_LOCATION.NEWSGROUP_ID.in(groupIds)).execute();
        // The read state goes with its article (ON DELETE CASCADE).
        database.deleteFrom(ARTICLE)
                .whereNotExists(DSL.selectOne().from(ARTICLE_LOCATION)
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
