package io.github.stekhub.wardroom.ignoring.internal;

import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE;
import static io.github.stekhub.wardroom.jooq.Tables.IGNORE_RULE;
import static io.github.stekhub.wardroom.jooq.Tables.NEWSGROUP;
import static io.github.stekhub.wardroom.jooq.Tables.SUBSCRIPTION;

import io.github.stekhub.wardroom.ignoring.IgnoreFilters;
import io.github.stekhub.wardroom.jooq.tables.Article;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record6;
import org.jooq.SelectOnConditionStep;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Database access for the ignore rules (spec 010, spec 022, spec 023). */
@Repository
@RequiredArgsConstructor
class IgnoreRuleRepository {

    private final DSLContext database;

    /**
     * @param kind      one of the kinds of {@link IgnoreFilters}
     * @param label     the display name of the author, the subject of the thread, the group name or the word as
     *                  the owner typed it; untrusted text
     * @param groupId   the group of a subject rule of one group; otherwise {@code null}
     * @param groupName the name of that group; otherwise {@code null}
     */
    record StoredRule(long id, String kind, String label, long createdAt, Long groupId, String groupName) {
    }

    /**
     * What a rule is derived from.
     *
     * @param from          untrusted text; {@code null} for a stub
     * @param authorKey     {@code null} for a stub and for an article without an author
     * @param subject       untrusted text; {@code null} for a stub
     * @param threadRootId  the root of the thread of the article, as it is now
     * @param rootMessageId the Message-ID of that root
     * @param rootSubject   untrusted text; {@code null} when the root is a stub
     */
    record RuleSource(String from, String authorKey, String subject, long threadRootId, String rootMessageId,
                      String rootSubject) {
    }

    /** A group as a rule needs it. */
    record RuleGroup(long id, String name, boolean subscribed) {
    }

    /** @return all rules, the newest first */
    List<StoredRule> findAll() {
        return selectRules()
                .orderBy(IGNORE_RULE.CREATED_AT.desc(), IGNORE_RULE.ID.desc())
                .fetch(IgnoreRuleRepository::toRule);
    }

    /** @return the article or stub as a rule needs it, or empty when no article has this ID */
    Optional<RuleSource> findRuleSource(long articleId) {
        Article root = ARTICLE.as("thread_root");
        // An article without a place yet is a thread of its own until it is threaded.
        Field<Integer> threadRootId = DSL.coalesce(ARTICLE.THREAD_ROOT_ID, ARTICLE.ID);
        return database.select(ARTICLE.FROM, ARTICLE.AUTHOR_KEY, ARTICLE.SUBJECT, root.ID, root.MESSAGE_ID,
                        root.SUBJECT)
                .from(ARTICLE)
                .join(root).on(root.ID.eq(threadRootId))
                .where(ARTICLE.ID.eq((int) articleId))
                .fetchOptional(row -> new RuleSource(row.value1(), row.value2(), row.value3(), row.value4(),
                        row.value5(), row.value6()));
    }

    /** @return the group, or empty when no group has this ID */
    Optional<RuleGroup> findGroup(long groupId) {
        return database.select(NEWSGROUP.ID, NEWSGROUP.NAME, SUBSCRIPTION.ID)
                .from(NEWSGROUP)
                .leftJoin(SUBSCRIPTION).on(SUBSCRIPTION.NEWSGROUP_ID.eq(NEWSGROUP.ID))
                .where(NEWSGROUP.ID.eq((int) groupId))
                .fetchOptional(row -> new RuleGroup(row.value1(), row.value2(), row.value3() != null));
    }

    /** @return the oldest rule that ignores the thread, or empty when the thread is not ignored */
    Optional<StoredRule> findThreadRule(long threadRootId) {
        return selectRules()
                .join(ARTICLE).on(ARTICLE.MESSAGE_ID.eq(IGNORE_RULE.VALUE))
                .where(IGNORE_RULE.KIND.eq(IgnoreFilters.THREAD))
                .and(ARTICLE.THREAD_ROOT_ID.eq((int) threadRootId).or(ARTICLE.ID.eq((int) threadRootId)))
                .orderBy(IGNORE_RULE.ID)
                .limit(1)
                .fetchOptional(IgnoreRuleRepository::toRule);
    }

    /**
     * Stores the rule unless one of this kind, value and group exists.
     *
     * @param groupId the group of a subject rule of one group; otherwise {@code null}
     * @return the rule that is stored now: the new one, or the one that was there, unchanged
     */
    StoredRule store(String kind, String value, String label, Long groupId, long createdAt) {
        database.insertInto(IGNORE_RULE)
                .set(IGNORE_RULE.KIND, kind)
                .set(IGNORE_RULE.VALUE, value)
                .set(IGNORE_RULE.LABEL, label)
                .set(IGNORE_RULE.NEWSGROUP_ID, groupId == null ? null : groupId.intValue())
                .set(IGNORE_RULE.CREATED_AT, createdAt)
                .onConflictDoNothing()
                .execute();
        Condition sameGroup = groupId == null
                ? IGNORE_RULE.NEWSGROUP_ID.isNull()
                : IGNORE_RULE.NEWSGROUP_ID.eq(groupId.intValue());
        return selectRules()
                .where(IGNORE_RULE.KIND.eq(kind))
                .and(IGNORE_RULE.VALUE.eq(value))
                .and(sameGroup)
                .fetchSingle(IgnoreRuleRepository::toRule);
    }

    /** Removes the rule. Nothing happens when no rule has this ID. */
    void delete(long ruleId) {
        database.deleteFrom(IGNORE_RULE).where(IGNORE_RULE.ID.eq((int) ruleId)).execute();
    }

    private SelectOnConditionStep<Record6<Integer, String, String, Long, Integer, String>> selectRules() {
        return database.select(IGNORE_RULE.ID, IGNORE_RULE.KIND, IGNORE_RULE.LABEL, IGNORE_RULE.CREATED_AT,
                        IGNORE_RULE.NEWSGROUP_ID, NEWSGROUP.NAME)
                .from(IGNORE_RULE)
                .leftJoin(NEWSGROUP).on(NEWSGROUP.ID.eq(IGNORE_RULE.NEWSGROUP_ID));
    }

    private static StoredRule toRule(Record6<Integer, String, String, Long, Integer, String> row) {
        return new StoredRule(row.value1(), row.value2(), row.value3(), row.value4(),
                row.value5() == null ? null : row.value5().longValue(), row.value6());
    }
}
