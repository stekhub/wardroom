package io.github.stekhub.wardroom.ignoring.internal;

import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE;
import static io.github.stekhub.wardroom.jooq.Tables.IGNORE_RULE;

import io.github.stekhub.wardroom.ignoring.IgnoreFilters;
import io.github.stekhub.wardroom.jooq.tables.Article;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** Database access for the ignore rules (spec 010). */
@Repository
@RequiredArgsConstructor
class IgnoreRuleRepository {

    private final DSLContext database;

    /**
     * @param kind  {@link IgnoreFilters#AUTHOR} or {@link IgnoreFilters#THREAD}
     * @param label the display name of the author or the subject of the thread; untrusted text
     */
    record StoredRule(long id, String kind, String label, long createdAt) {
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

    /** @return all rules, the newest first */
    List<StoredRule> findAll() {
        return database.select(IGNORE_RULE.ID, IGNORE_RULE.KIND, IGNORE_RULE.LABEL, IGNORE_RULE.CREATED_AT)
                .from(IGNORE_RULE)
                .orderBy(IGNORE_RULE.CREATED_AT.desc(), IGNORE_RULE.ID.desc())
                .fetch(row -> new StoredRule(row.value1(), row.value2(), row.value3(), row.value4()));
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

    /** @return the oldest rule that ignores the thread, or empty when the thread is not ignored */
    Optional<StoredRule> findThreadRule(long threadRootId) {
        return database.select(IGNORE_RULE.ID, IGNORE_RULE.KIND, IGNORE_RULE.LABEL, IGNORE_RULE.CREATED_AT)
                .from(IGNORE_RULE)
                .join(ARTICLE).on(ARTICLE.MESSAGE_ID.eq(IGNORE_RULE.VALUE))
                .where(IGNORE_RULE.KIND.eq(IgnoreFilters.THREAD))
                .and(ARTICLE.THREAD_ROOT_ID.eq((int) threadRootId).or(ARTICLE.ID.eq((int) threadRootId)))
                .orderBy(IGNORE_RULE.ID)
                .limit(1)
                .fetchOptional(row -> new StoredRule(row.value1(), row.value2(), row.value3(), row.value4()));
    }

    /**
     * Stores the rule unless one of this kind and value exists.
     *
     * @return the rule that is stored now: the new one, or the one that was there, unchanged
     */
    StoredRule store(String kind, String value, String label, long createdAt) {
        database.insertInto(IGNORE_RULE)
                .set(IGNORE_RULE.KIND, kind)
                .set(IGNORE_RULE.VALUE, value)
                .set(IGNORE_RULE.LABEL, label)
                .set(IGNORE_RULE.CREATED_AT, createdAt)
                .onConflictDoNothing()
                .execute();
        return database.select(IGNORE_RULE.ID, IGNORE_RULE.KIND, IGNORE_RULE.LABEL, IGNORE_RULE.CREATED_AT)
                .from(IGNORE_RULE)
                .where(IGNORE_RULE.KIND.eq(kind))
                .and(IGNORE_RULE.VALUE.eq(value))
                .fetchSingle(row -> new StoredRule(row.value1(), row.value2(), row.value3(), row.value4()));
    }

    /** Removes the rule. Nothing happens when no rule has this ID. */
    void delete(long ruleId) {
        database.deleteFrom(IGNORE_RULE).where(IGNORE_RULE.ID.eq((int) ruleId)).execute();
    }
}
