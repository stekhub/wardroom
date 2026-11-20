package io.github.stekhub.wardroom.groups.internal;

import static io.github.stekhub.wardroom.jooq.Tables.NEWSGROUP;

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
import org.jooq.Record3;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Database access for the group list of a server (data model: {@code newsgroup}). */
@Repository
@RequiredArgsConstructor
class GroupRepository {

    private static final char LIKE_ESCAPE = '\\';

    /** SQLite takes a limited number of values in one statement. */
    private static final int IDS_PER_STATEMENT = 500;

    private final DSLContext database;

    /**
     * A group as the group list page shows it.
     *
     * @param description {@code null} when the server gave none
     */
    record StoredGroup(long id, String name, String description, PostingStatus postingStatus) {
    }

    /** Which groups changed their place in the list of the server at the end of a refresh. */
    record RemovedMarking(int removed, int returned) {
    }

    /**
     * Stores the groups of one part of the list. A group that is known already keeps its ID, its description and
     * everything later features store for it; only what {@code LIST ACTIVE} reports is replaced.
     */
    void upsert(long serverId, List<NntpActiveGroup> activeGroups) {
        BatchBindStep batch = database.batch(database
                .insertInto(NEWSGROUP, NEWSGROUP.SERVER_ID, NEWSGROUP.NAME, NEWSGROUP.POSTING_STATUS,
                        NEWSGROUP.LOW_WATERMARK, NEWSGROUP.HIGH_WATERMARK)
                .values((Integer) null, null, null, null, null)
                .onConflict(NEWSGROUP.SERVER_ID, NEWSGROUP.NAME)
                .doUpdate()
                .set(NEWSGROUP.POSTING_STATUS, DSL.excluded(NEWSGROUP.POSTING_STATUS))
                .set(NEWSGROUP.LOW_WATERMARK, DSL.excluded(NEWSGROUP.LOW_WATERMARK))
                .set(NEWSGROUP.HIGH_WATERMARK, DSL.excluded(NEWSGROUP.HIGH_WATERMARK)));
        for (NntpActiveGroup activeGroup : activeGroups) {
            batch = batch.bind((int) serverId, activeGroup.name(),
                    PostingStatus.ofListActiveStatus(activeGroup.status()).name(), activeGroup.lowWatermark(),
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
     * @return one page of the groups the server still has, ordered by name
     */
    List<StoredGroup> findPage(long serverId, List<String> searchWords, long offset, int size) {
        return database.select(NEWSGROUP.ID, NEWSGROUP.NAME, NEWSGROUP.DESCRIPTION, NEWSGROUP.POSTING_STATUS)
                .from(NEWSGROUP)
                .where(listedAndMatching(serverId, searchWords))
                .orderBy(NEWSGROUP.NAME)
                .limit(size)
                .offset(offset)
                .fetch(row -> new StoredGroup(row.value1(), row.value2(), row.value3(),
                        PostingStatus.valueOf(row.value4())));
    }

    /** @return how many groups {@link #findPage(long, List, long, int)} has over all pages */
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
