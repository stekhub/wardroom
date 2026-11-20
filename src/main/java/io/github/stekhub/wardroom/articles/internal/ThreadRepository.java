package io.github.stekhub.wardroom.articles.internal;

import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE;
import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE_LOCATION;

import io.github.stekhub.wardroom.articles.internal.SubjectFallback.Candidate;
import io.github.stekhub.wardroom.jooq.tables.Article;
import io.github.stekhub.wardroom.jooq.tables.ArticleLocation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.BatchBindStep;
import org.jooq.CommonTableExpression;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Name;
import org.jooq.Record1;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Database access for threads: the place of each article ({@code parent_id}, {@code thread_root_id}), stubs, and
 * the figures of the threads of a group (spec 006; data model).
 */
@Repository
@RequiredArgsConstructor
class ThreadRepository {

    /** SQLite takes a limited number of values in one statement; lists of IDs are split into parts of this size. */
    private static final int MAX_VALUES_PER_STATEMENT = 500;

    private final DSLContext database;

    /** An article whose place in its thread has not been computed yet. */
    record UnthreadedArticle(long articleId, String messageId, String references) {
    }

    /** A stored article or stub with the parent it has now. */
    record StoredNode(long articleId, String messageId, boolean stub, Long parentId) {
    }

    /** The place of an article or stub in its thread. */
    record Place(long articleId, Long parentId, long threadRootId) {
    }

    /** An article with the parent it has now. */
    record Child(long articleId, long parentId) {
    }

    /**
     * A thread as a group shows it (spec 006, "A thread in a group").
     *
     * @param threadRootId   the topmost article of the thread, which can be a stub
     * @param cardArticleId  the article whose subject, author and date stand for the thread
     * @param messageCount   the articles of the thread that are located in the group and are no stubs
     * @param latestActivity the newest date among those articles, or {@code null} when none has a date
     */
    record GroupThread(long threadRootId, long cardArticleId, int messageCount, Long latestActivity) {
    }

    /**
     * @return the articles located in the group that are no stubs and have no place yet, by article number, oldest
     *         first; at most {@code limit}
     */
    List<UnthreadedArticle> findUnthreaded(long groupId, int limit) {
        return database.select(ARTICLE.ID, ARTICLE.MESSAGE_ID, ARTICLE.REFERENCES)
                .from(ARTICLE_LOCATION)
                .join(ARTICLE).on(ARTICLE.ID.eq(ARTICLE_LOCATION.ARTICLE_ID))
                .where(ARTICLE_LOCATION.NEWSGROUP_ID.eq((int) groupId))
                .and(ARTICLE.IS_STUB.isFalse())
                .and(ARTICLE.THREAD_ROOT_ID.isNull())
                .orderBy(ARTICLE_LOCATION.ARTICLE_NUMBER)
                .limit(limit)
                .fetch(row -> new UnthreadedArticle(row.value1(), row.value2(), row.value3()));
    }

    /** @return the stored articles and stubs with these Message-IDs, and all their ancestors */
    List<StoredNode> findWithAncestors(Collection<String> messageIds) {
        Name ancestryName = DSL.name("ancestry");
        Table<?> ancestryTable = DSL.table(ancestryName);
        Field<Integer> ancestryId = DSL.field(DSL.name("ancestry", "id"), Integer.class);
        List<StoredNode> storedNodes = new ArrayList<>();
        for (List<String> part : parts(messageIds)) {
            // UNION, not UNION ALL: a row is visited once, so the walk ends even if stored links form a cycle.
            CommonTableExpression<Record1<Integer>> ancestry = ancestryName.fields("id").as(
                    DSL.select(ARTICLE.ID).from(ARTICLE).where(ARTICLE.MESSAGE_ID.in(part))
                            .union(DSL.select(ARTICLE.PARENT_ID).from(ARTICLE)
                                    .join(ancestryTable).on(ARTICLE.ID.eq(ancestryId))
                                    .where(ARTICLE.PARENT_ID.isNotNull())));
            storedNodes.addAll(database.withRecursive(ancestry)
                    .select(ARTICLE.ID, ARTICLE.MESSAGE_ID, ARTICLE.IS_STUB, ARTICLE.PARENT_ID)
                    .from(ARTICLE)
                    .where(ARTICLE.ID.in(DSL.select(ancestryId).from(ancestryTable)))
                    .fetch(row -> new StoredNode(row.value1(), row.value2(), Boolean.TRUE.equals(row.value3()),
                            row.value4() == null ? null : row.value4().longValue())));
        }
        return storedNodes;
    }

    /** @return the ID of the new stub */
    long insertStub(String messageId, Long parentId) {
        return database.insertInto(ARTICLE)
                .set(ARTICLE.MESSAGE_ID, messageId)
                .set(ARTICLE.IS_STUB, true)
                .set(ARTICLE.PARENT_ID, toInteger(parentId))
                .returningResult(ARTICLE.ID)
                .fetchOne()
                .value1();
    }

    void updatePlaces(List<Place> places) {
        if (places.isEmpty()) {
            return;
        }
        // One statement with the values of every article: much faster than a statement per article.
        BatchBindStep updates = database.batch(database.update(ARTICLE)
                .set(ARTICLE.PARENT_ID, (Integer) null)
                .set(ARTICLE.THREAD_ROOT_ID, (Integer) null)
                .where(ARTICLE.ID.eq((Integer) null)));
        for (Place place : places) {
            updates.bind(toInteger(place.parentId()), (int) place.threadRootId(), (int) place.articleId());
        }
        updates.execute();
    }

    /** @param threadRootByArticleId the new thread root of each article */
    void updateThreadRoots(Map<Long, Long> threadRootByArticleId) {
        if (threadRootByArticleId.isEmpty()) {
            return;
        }
        BatchBindStep updates = database.batch(database.update(ARTICLE)
                .set(ARTICLE.THREAD_ROOT_ID, (Integer) null)
                .where(ARTICLE.ID.eq((Integer) null)));
        threadRootByArticleId.forEach((articleId, threadRootId) ->
                updates.bind(threadRootId.intValue(), articleId.intValue()));
        updates.execute();
    }

    void updateThreadRoot(long articleId, long threadRootId) {
        database.update(ARTICLE)
                .set(ARTICLE.THREAD_ROOT_ID, (int) threadRootId)
                .where(ARTICLE.ID.eq((int) articleId))
                .execute();
    }

    /** @return the articles and stubs that have one of these parents and a place of their own already */
    List<Child> findThreadedChildren(Collection<Long> parentIds) {
        List<Child> children = new ArrayList<>();
        for (List<Long> part : parts(parentIds)) {
            children.addAll(database.select(ARTICLE.ID, ARTICLE.PARENT_ID)
                    .from(ARTICLE)
                    .where(ARTICLE.PARENT_ID.in(part.stream().map(Long::intValue).toList()))
                    .and(ARTICLE.THREAD_ROOT_ID.isNotNull())
                    .fetch(row -> new Child(row.value1(), row.value2())));
        }
        return children;
    }

    /**
     * @return what the subject fallback of the group looks at: the dated articles located in the group that are no
     *         stubs, have a place, and have no references or no parent
     */
    List<Candidate> findFallbackCandidates(long groupId) {
        Field<Integer> decidingGroup = DSL.field(DSL.select(DSL.min(ARTICLE_LOCATION.NEWSGROUP_ID))
                .from(ARTICLE_LOCATION)
                .where(ARTICLE_LOCATION.ARTICLE_ID.eq(ARTICLE.ID)));
        ArticleLocation location = ARTICLE_LOCATION.as("location_in_group");
        return database.select(ARTICLE.ID, ARTICLE.MESSAGE_ID, ARTICLE.SUBJECT, ARTICLE.DATE, ARTICLE.PARENT_ID,
                        ARTICLE.REFERENCES, decidingGroup)
                .from(location)
                .join(ARTICLE).on(ARTICLE.ID.eq(location.ARTICLE_ID))
                .where(location.NEWSGROUP_ID.eq((int) groupId))
                .and(ARTICLE.IS_STUB.isFalse())
                .and(ARTICLE.DATE.isNotNull())
                .and(ARTICLE.THREAD_ROOT_ID.isNotNull())
                .and(ARTICLE.PARENT_ID.isNull()
                        .or(ARTICLE.REFERENCES.isNull())
                        .or(ARTICLE.REFERENCES.notLike("%<%>%")))
                .fetch(row -> new Candidate(row.value1(), row.value2(), row.value3(), row.value4(),
                        row.value5() == null ? null : row.value5().longValue(),
                        !ReferencesParser.hasMessageIdToken(row.value6()),
                        row.value7() != null && row.value7() == groupId));
    }

    void setParent(long articleId, Long parentId) {
        database.update(ARTICLE)
                .set(ARTICLE.PARENT_ID, toInteger(parentId))
                .where(ARTICLE.ID.eq((int) articleId))
                .execute();
    }

    /**
     * Takes apart every thread that has an article located in the group: its stubs are deleted, and its articles
     * that are also located in another group lose their place. The articles located only in this group keep
     * theirs; the caller removes them.
     */
    void forgetThreadsOfGroup(long groupId) {
        Article member = ARTICLE.as("member");
        ArticleLocation otherLocation = ARTICLE_LOCATION.as("other_location");
        List<Integer> stubIds = new ArrayList<>();
        List<Integer> remainingArticleIds = new ArrayList<>();
        // Read first, change afterwards: deleting a stub empties the thread root of the rows that pointed to it.
        database.select(ARTICLE.ID, ARTICLE.IS_STUB)
                .from(ARTICLE)
                .where(ARTICLE.THREAD_ROOT_ID.in(DSL.select(member.THREAD_ROOT_ID)
                        .from(ARTICLE_LOCATION)
                        .join(member).on(member.ID.eq(ARTICLE_LOCATION.ARTICLE_ID))
                        .where(ARTICLE_LOCATION.NEWSGROUP_ID.eq((int) groupId))
                        .and(member.THREAD_ROOT_ID.isNotNull())))
                .and(ARTICLE.IS_STUB.isTrue().orExists(DSL.selectOne().from(otherLocation)
                        .where(otherLocation.ARTICLE_ID.eq(ARTICLE.ID))
                        .and(otherLocation.NEWSGROUP_ID.ne((int) groupId))))
                .forEach(row -> (Boolean.TRUE.equals(row.value2()) ? stubIds : remainingArticleIds)
                        .add(row.value1()));
        for (List<Integer> part : parts(remainingArticleIds)) {
            database.update(ARTICLE)
                    .setNull(ARTICLE.PARENT_ID)
                    .setNull(ARTICLE.THREAD_ROOT_ID)
                    .where(ARTICLE.ID.in(part))
                    .execute();
        }
        for (List<Integer> part : parts(stubIds)) {
            database.deleteFrom(ARTICLE).where(ARTICLE.ID.in(part)).execute();
        }
    }

    /** Deletes the stubs that belong to no thread. A stub of a thread always has a thread root. */
    void deleteStubsWithoutPlace() {
        database.deleteFrom(ARTICLE)
                .where(ARTICLE.THREAD_ROOT_ID.isNull())
                .and(ARTICLE.IS_STUB.isTrue())
                .execute();
    }

    /** @return the groups that have articles without a place, lowest ID first */
    List<Long> findGroupsWithUnthreadedArticles() {
        return database.selectDistinct(ARTICLE_LOCATION.NEWSGROUP_ID)
                .from(ARTICLE)
                .join(ARTICLE_LOCATION).on(ARTICLE_LOCATION.ARTICLE_ID.eq(ARTICLE.ID))
                .where(ARTICLE.THREAD_ROOT_ID.isNull())
                .and(ARTICLE.IS_STUB.isFalse())
                .orderBy(ARTICLE_LOCATION.NEWSGROUP_ID)
                .fetch(row -> row.value1().longValue());
    }

    /** Deletes every stub. Links to a stub go with it. */
    void deleteStubs() {
        database.deleteFrom(ARTICLE).where(ARTICLE.IS_STUB.isTrue()).execute();
    }

    /** Takes the place away from every article, so that all are threaded again. */
    void forgetPlaces() {
        database.update(ARTICLE)
                .setNull(ARTICLE.PARENT_ID)
                .setNull(ARTICLE.THREAD_ROOT_ID)
                .where(ARTICLE.PARENT_ID.isNotNull().or(ARTICLE.THREAD_ROOT_ID.isNotNull()))
                .execute();
    }

    /** @return the groups that have articles, lowest ID first */
    List<Long> findGroupsWithArticles() {
        return database.selectDistinct(ARTICLE_LOCATION.NEWSGROUP_ID)
                .from(ARTICLE_LOCATION)
                .orderBy(ARTICLE_LOCATION.NEWSGROUP_ID)
                .fetch(row -> row.value1().longValue());
    }

    /**
     * @return the threads of the group: every thread that has an article located in the group that is no stub,
     *         expired locations included, ordered by the ID of the thread root
     */
    List<GroupThread> findThreads(long groupId) {
        // An article without a place yet is a thread of its own until it is threaded.
        Field<Integer> threadRoot = DSL.coalesce(ARTICLE.THREAD_ROOT_ID, ARTICLE.ID);
        Field<Integer> root = threadRoot.as("thread_root");
        Field<Integer> messageCount = DSL.count().over().partitionBy(threadRoot).as("message_count");
        Field<Long> latestActivity = DSL.max(ARTICLE.DATE).over().partitionBy(threadRoot).as("latest_activity");
        // The card article: the thread root when it is among these articles, otherwise the oldest of them.
        Field<Integer> position = DSL.rowNumber().over()
                .partitionBy(threadRoot)
                .orderBy(DSL.when(ARTICLE.ID.eq(threadRoot), 0).otherwise(1),
                        DSL.when(ARTICLE.DATE.isNull(), 1).otherwise(0),
                        ARTICLE.DATE,
                        ARTICLE_LOCATION.ARTICLE_NUMBER)
                .as("position");
        Table<?> articlesOfGroup = database.select(root, ARTICLE.ID.as("article_id"), messageCount, latestActivity,
                        position)
                .from(ARTICLE_LOCATION)
                .join(ARTICLE).on(ARTICLE.ID.eq(ARTICLE_LOCATION.ARTICLE_ID))
                .where(ARTICLE_LOCATION.NEWSGROUP_ID.eq((int) groupId))
                .and(ARTICLE.IS_STUB.isFalse())
                .asTable("articles_of_group");
        return database.select(articlesOfGroup.field("thread_root", Integer.class),
                        articlesOfGroup.field("article_id", Integer.class),
                        articlesOfGroup.field("message_count", Integer.class),
                        articlesOfGroup.field("latest_activity", Long.class))
                .from(articlesOfGroup)
                .where(articlesOfGroup.field("position", Integer.class).eq(1))
                .orderBy(articlesOfGroup.field("thread_root", Integer.class))
                .fetch(row -> new GroupThread(row.value1(), row.value2(), row.value3(), row.value4()));
    }

    /**
     * Resolves any article of a thread to the current root of the thread (spec 006, "Thread identity").
     *
     * @return the ID of the thread root, or empty when no article or stub has this ID
     */
    Optional<Long> findThreadRootId(long articleId) {
        return database.select(DSL.coalesce(ARTICLE.THREAD_ROOT_ID, ARTICLE.ID))
                .from(ARTICLE)
                .where(ARTICLE.ID.eq((int) articleId))
                .fetchOptional(row -> row.value1().longValue());
    }

    private static Integer toInteger(Long value) {
        return value == null ? null : value.intValue();
    }

    private static <T> List<List<T>> parts(Collection<T> values) {
        List<T> all = new ArrayList<>(values);
        List<List<T>> parts = new ArrayList<>();
        for (int start = 0; start < all.size(); start += MAX_VALUES_PER_STATEMENT) {
            parts.add(all.subList(start, Math.min(start + MAX_VALUES_PER_STATEMENT, all.size())));
        }
        return parts;
    }
}
