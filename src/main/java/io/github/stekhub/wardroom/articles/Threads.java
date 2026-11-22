package io.github.stekhub.wardroom.articles;

/**
 * What the synchronization needs of the threading (spec 006, "Recomputation").
 */
public interface Threads {

    /**
     * Gives every article of the group that was stored or filled in since the last call its place in a thread, and
     * evaluates the subject fallback of the group again. To be called after the articles are stored, outside a
     * transaction.
     *
     * <p>A failure is logged and not passed on: the articles keep the place they had, and the next call tries
     * again.
     */
    void recomputeGroup(long groupId);

    /**
     * Gives one article its place, from its own headers. For an article that is stored outside a synchronization
     * and may have no location, such as one the owner posted (spec 009). To be called after the article is stored,
     * outside a transaction. A failure is logged and not passed on.
     */
    void threadArticle(long articleId);
}
