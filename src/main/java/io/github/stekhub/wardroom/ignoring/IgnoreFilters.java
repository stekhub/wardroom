package io.github.stekhub.wardroom.ignoring;

import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE;
import static io.github.stekhub.wardroom.jooq.Tables.IGNORE_RULE;

import io.github.stekhub.wardroom.jooq.tables.Article;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Select;
import org.jooq.impl.DSL;

/**
 * The ignore rules as parts of a query, for the lists and counts that leave out what the owner ignores (spec 010,
 * "Technical design"). Ignoring is a filter: these parts read the rules and change nothing.
 */
public final class IgnoreFilters {

    /** The kind of a rule that ignores an author; its value is an author key. */
    public static final String AUTHOR = "AUTHOR";

    /** The kind of a rule that ignores a thread; its value is the Message-ID its root had. */
    public static final String THREAD = "THREAD";

    private IgnoreFilters() {
    }

    /**
     * @param authorKey the author key of an article
     * @return whether the owner ignores this author. An article without a key, as a stub, is never ignored
     */
    public static Condition authorIgnored(Field<String> authorKey) {
        return DSL.exists(DSL.selectOne()
                .from(IGNORE_RULE)
                .where(IGNORE_RULE.KIND.eq(AUTHOR))
                .and(IGNORE_RULE.VALUE.eq(authorKey)));
    }

    /**
     * @param threadRootId the root of a thread
     * @return whether the owner ignores this thread: one of its articles has the Message-ID of a thread rule
     */
    public static Condition threadIgnored(Field<Integer> threadRootId) {
        return threadRootId.in(ignoredThreadRoots());
    }

    /** The roots of the threads the owner ignores, as they are now. */
    private static Select<Record1<Integer>> ignoredThreadRoots() {
        Article member = ARTICLE.as("ignored_thread_member");
        // An article without a place yet is a thread of its own until it is threaded.
        return DSL.select(DSL.coalesce(member.THREAD_ROOT_ID, member.ID))
                .from(IGNORE_RULE)
                .join(member).on(member.MESSAGE_ID.eq(IGNORE_RULE.VALUE))
                .where(IGNORE_RULE.KIND.eq(THREAD));
    }
}
