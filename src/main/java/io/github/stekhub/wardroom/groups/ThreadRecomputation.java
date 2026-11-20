package io.github.stekhub.wardroom.groups;

/**
 * What the groups module needs of the threading (spec 006; data model, lifecycle rules). The module that builds
 * threads implements this interface as a Spring bean.
 */
public interface ThreadRecomputation {

    /**
     * Prepares the removal of the articles of a group, with the unsubscribe cleanup or when a server has renumbered
     * the group. Every thread that has an article located in the group loses its stubs, and its articles lose
     * their place. All other threads stay as they are. To be called before the articles are removed, in the same
     * transaction, and followed by {@link #threadArticlesWithoutPlace()}.
     */
    void forgetThreadsOfGroup(long groupId);

    /**
     * Gives every article that has no place its place in a thread. Stubs that these articles reference are created
     * again. To be called after the articles of a group were removed, in the same transaction.
     */
    void threadArticlesWithoutPlace();

    /**
     * Computes all threads again, after a server was removed with all its articles. All stubs are deleted, and
     * those that remaining articles still reference are created again. To be called in the transaction that
     * removed the articles.
     */
    void recomputeAllThreads();
}
