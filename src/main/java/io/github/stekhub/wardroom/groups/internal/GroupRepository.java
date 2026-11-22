package io.github.stekhub.wardroom.groups.internal;

import static io.github.stekhub.wardroom.jooq.Tables.NEWSGROUP;
import static io.github.stekhub.wardroom.jooq.Tables.SUBSCRIPTION;

import io.github.stekhub.wardroom.nntp.NntpActiveGroup;
import io.github.stekhub.wardroom.nntp.NntpGroupDescription;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jooq.BatchBindStep;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record3;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Repository;

/** Database access for the group list of a server (data model: {@code newsgroup}). */
@Repository
@RequiredArgsConstructor
class GroupRepository {

    private static final char LIKE_ESCAPE = '\\';

    /** SQLite takes a limited number of values in one statement. */
    private static final int IDS_PER_STATEMENT = 500;

    private static final long MILLIS_PER_DAY = 24L * 60 * 60 * 1000;

    /** A younger baseline of this age becomes the older one, so the activity covers 7 to 14 days. */
    static final long BASELINE_AGE_MILLIS = 7 * MILLIS_PER_DAY;

    /** A shorter time follows the time of day, so it gives no value. */
    static final long SHORTEST_MEASURED_MILLIS = MILLIS_PER_DAY;

    private final DSLContext database;

    /**
     * A group as the group list page shows it.
     *
     * @param description    {@code null} when the server gave none
     * @param articlesPerDay {@code null} while the activity is not known
     */
    record StoredGroup(long id, String name, String description, PostingStatus postingStatus,
                       boolean subscribed, Double articlesPerDay) {
    }

    /** Which groups changed their place in the list of the server at the end of a refresh. */
    record RemovedMarking(int removed, int returned) {
    }

    /**
     * Stores the groups of one part of the list. A group that is known already keeps its ID, its description and
     * everything later features store for it; only what {@code LIST ACTIVE} reports is replaced.
     *
     * <p>The same statement keeps the two baselines of the activity and computes the articles per day (spec 003).
     * A new group starts with both baselines at this refresh. The expressions read the row as it was before the
     * statement, so they do not depend on their order.
     *
     * @param refreshTime the time of the refresh the part belongs to, in milliseconds
     */
    void upsert(long serverId, List<NntpActiveGroup> activeGroups, long refreshTime) {
        // The values of the refresh, as the statement names them. Constants are written into the statement,
        // because the batch binds the values of the rows by their position.
        Field<Long> now = DSL.excluded(NEWSGROUP.ACTIVITY_YOUNGER_AT);
        Field<Long> highWatermarkNow = DSL.excluded(NEWSGROUP.HIGH_WATERMARK);
        // No baseline yet, or the server numbered the group anew: both baselines start over.
        Condition startsOver = NEWSGROUP.ACTIVITY_OLDER_AT.isNull()
                .or(highWatermarkNow.lt(NEWSGROUP.ACTIVITY_OLDER_HIGH_WATERMARK))
                .or(highWatermarkNow.lt(NEWSGROUP.ACTIVITY_YOUNGER_HIGH_WATERMARK));
        Condition youngerBecomesOlder = now.minus(NEWSGROUP.ACTIVITY_YOUNGER_AT)
                .ge(DSL.inline(BASELINE_AGE_MILLIS));
        Field<Long> olderAt = DSL.when(startsOver, now)
                .when(youngerBecomesOlder, NEWSGROUP.ACTIVITY_YOUNGER_AT)
                .otherwise(NEWSGROUP.ACTIVITY_OLDER_AT);
        Field<Long> olderHighWatermark = DSL.when(startsOver, highWatermarkNow)
                .when(youngerBecomesOlder, NEWSGROUP.ACTIVITY_YOUNGER_HIGH_WATERMARK)
                .otherwise(NEWSGROUP.ACTIVITY_OLDER_HIGH_WATERMARK);
        Field<Long> youngerAt = DSL.when(startsOver.or(youngerBecomesOlder), now)
                .otherwise(NEWSGROUP.ACTIVITY_YOUNGER_AT);
        Field<Long> youngerHighWatermark = DSL.when(startsOver.or(youngerBecomesOlder), highWatermarkNow)
                .otherwise(NEWSGROUP.ACTIVITY_YOUNGER_HIGH_WATERMARK);
        // Without a branch that applies, the value is NULL: the activity is not known.
        Field<Double> articlesPerDay = DSL.when(
                DSL.not(startsOver).and(now.minus(olderAt).ge(DSL.inline(SHORTEST_MEASURED_MILLIS))),
                highWatermarkNow.minus(olderHighWatermark).cast(SQLDataType.DOUBLE)
                        .mul(DSL.inline((double) MILLIS_PER_DAY))
                        .div(now.minus(olderAt)));

        BatchBindStep batch = database.batch(database
                .insertInto(NEWSGROUP, NEWSGROUP.SERVER_ID, NEWSGROUP.NAME, NEWSGROUP.POSTING_STATUS,
                        NEWSGROUP.LOW_WATERMARK, NEWSGROUP.HIGH_WATERMARK, NEWSGROUP.ACTIVITY_OLDER_AT,
                        NEWSGROUP.ACTIVITY_OLDER_HIGH_WATERMARK, NEWSGROUP.ACTIVITY_YOUNGER_AT,
                        NEWSGROUP.ACTIVITY_YOUNGER_HIGH_WATERMARK)
                .values((Integer) null, null, null, null, null, null, null, null, null)
                .onConflict(NEWSGROUP.SERVER_ID, NEWSGROUP.NAME)
                .doUpdate()
                .set(NEWSGROUP.POSTING_STATUS, DSL.excluded(NEWSGROUP.POSTING_STATUS))
                .set(NEWSGROUP.LOW_WATERMARK, DSL.excluded(NEWSGROUP.LOW_WATERMARK))
                .set(NEWSGROUP.HIGH_WATERMARK, highWatermarkNow)
                .set(NEWSGROUP.ACTIVITY_OLDER_AT, olderAt)
                .set(NEWSGROUP.ACTIVITY_OLDER_HIGH_WATERMARK, olderHighWatermark)
                .set(NEWSGROUP.ACTIVITY_YOUNGER_AT, youngerAt)
                .set(NEWSGROUP.ACTIVITY_YOUNGER_HIGH_WATERMARK, youngerHighWatermark)
                .set(NEWSGROUP.ARTICLES_PER_DAY, articlesPerDay));
        for (NntpActiveGroup activeGroup : activeGroups) {
            batch = batch.bind((int) serverId, activeGroup.name(),
                    PostingStatus.ofListActiveStatus(activeGroup.status()).name(), activeGroup.lowWatermark(),
                    activeGroup.highWatermark(), refreshTime, activeGroup.highWatermark(), refreshTime,
                    activeGroup.highWatermark());
        }
        batch.execute();
    }

    /** Stores descriptions. A group the server describes but does not list is not created. */
    void updateDescriptions(long serverId, List<NntpGroupDescription> descriptions) {
        BatchBindStep batch = database.batch(database
                .update(NEWSGROUP)
                .set(NEWSGROUP.DESCRIPTION, (String) null)
                .where(NEWSGROUP.SERVER_ID.eq((Integer) null))
                .and(NEWSGROUP.NAME.eq((String) null)));
        for (NntpGroupDescription description : descriptions) {
            batch = batch.bind(description.description(), (int) serverId, description.name());
        }
        batch.execute();
    }

    /** Removes the descriptions of the named groups. */
    void clearDescriptions(long serverId, List<String> groupNames) {
        BatchBindStep batch = database.batch(database
                .update(NEWSGROUP)
                .setNull(NEWSGROUP.DESCRIPTION)
                .where(NEWSGROUP.SERVER_ID.eq((Integer) null))
                .and(NEWSGROUP.NAME.eq((String) null)));
        for (String groupName : groupNames) {
            // The first value is the description, which becomes NULL.
            batch = batch.bind(null, (int) serverId, groupName);
        }
        batch.execute();
    }

    /**
     * Compares the stored groups of the server with a complete list: a group that is not in the list is marked as
     * removed, and a removed group that is in the list again loses the mark. To be called in one transaction.
     *
     * @param listedNames the names of all groups the server listed
     */
    RemovedMarking markRemovedAndReturned(long serverId, Set<String> listedNames, long now) {
        List<Integer> removedIds = new ArrayList<>();
        List<Integer> returnedIds = new ArrayList<>();
        try (Stream<Record3<Integer, String, Long>> storedGroups = database
                .select(NEWSGROUP.ID, NEWSGROUP.NAME, NEWSGROUP.REMOVED_AT)
                .from(NEWSGROUP)
                .where(NEWSGROUP.SERVER_ID.eq((int) serverId))
                .fetchStream()) {
            storedGroups.forEach(storedGroup -> {
                boolean listed = listedNames.contains(storedGroup.value2());
                boolean markedRemoved = storedGroup.value3() != null;
                if (!listed && !markedRemoved) {
                    removedIds.add(storedGroup.value1());
                } else if (listed && markedRemoved) {
                    returnedIds.add(storedGroup.value1());
                }
            });
        }
        forEachPart(removedIds, groupIds -> database.update(NEWSGROUP)
                .set(NEWSGROUP.REMOVED_AT, now)
                .where(NEWSGROUP.ID.in(groupIds))
                .execute());
        forEachPart(returnedIds, groupIds -> database.update(NEWSGROUP)
                .setNull(NEWSGROUP.REMOVED_AT)
                .where(NEWSGROUP.ID.in(groupIds))
                .execute());
        return new RemovedMarking(removedIds.size(), returnedIds.size());
    }

    /**
     * @param searchWords every word must appear in the name or in the description; none means all groups
     * @return one page of the groups the server still has, in the given order
     */
    List<StoredGroup> findPage(long serverId, List<String> searchWords, GroupSort sort, long offset, int size) {
        List<SortField<?>> order = sort == GroupSort.ACTIVITY
                ? List.of(NEWSGROUP.ARTICLES_PER_DAY.desc().nullsLast(), NEWSGROUP.NAME.asc())
                : List.of(NEWSGROUP.NAME.asc());
        return database.select(NEWSGROUP.ID, NEWSGROUP.NAME, NEWSGROUP.DESCRIPTION, NEWSGROUP.POSTING_STATUS,
                        SUBSCRIPTION.ID, NEWSGROUP.ARTICLES_PER_DAY)
                .from(NEWSGROUP)
                .leftJoin(SUBSCRIPTION).on(SUBSCRIPTION.NEWSGROUP_ID.eq(NEWSGROUP.ID))
                .where(listedAndMatching(serverId, searchWords))
                .orderBy(order)
                .limit(size)
                .offset(offset)
                .fetch(row -> new StoredGroup(row.value1(), row.value2(), row.value3(),
                        PostingStatus.valueOf(row.value4()), row.value5() != null, row.value6()));
    }

    /** @return whether the activity of at least one group the server still has is known */
    boolean hasActivity(long serverId) {
        return database.fetchExists(NEWSGROUP, NEWSGROUP.SERVER_ID.eq((int) serverId)
                .and(NEWSGROUP.REMOVED_AT.isNull())
                .and(NEWSGROUP.ARTICLES_PER_DAY.isNotNull()));
    }

    /** @return how many groups {@link #findPage(long, List, GroupSort, long, int)} has over all pages */
    int count(long serverId, List<String> searchWords) {
        return database.fetchCount(NEWSGROUP, listedAndMatching(serverId, searchWords));
    }

    void deleteByServer(long serverId) {
        database.deleteFrom(NEWSGROUP).where(NEWSGROUP.SERVER_ID.eq((int) serverId)).execute();
    }

    /**
     * The search of spec 003. SQLite compares with {@code LIKE} without regard to case for the letters A to Z only.
     * The words are bound parameters, and the characters with a meaning in a pattern are escaped.
     */
    private static Condition listedAndMatching(long serverId, List<String> searchWords) {
        Condition condition = NEWSGROUP.SERVER_ID.eq((int) serverId).and(NEWSGROUP.REMOVED_AT.isNull());
        for (String searchWord : searchWords) {
            String pattern = "%" + escapeForLike(searchWord) + "%";
            condition = condition.and(NEWSGROUP.NAME.like(pattern).escape(LIKE_ESCAPE)
                    .or(NEWSGROUP.DESCRIPTION.like(pattern).escape(LIKE_ESCAPE)));
        }
        return condition;
    }

    static String escapeForLike(String searchWord) {
        return searchWord.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static void forEachPart(List<Integer> groupIds, java.util.function.Consumer<Collection<Integer>> action) {
        for (int start = 0; start < groupIds.size(); start += IDS_PER_STATEMENT) {
            action.accept(groupIds.subList(start, Math.min(start + IDS_PER_STATEMENT, groupIds.size())));
        }
    }
}
