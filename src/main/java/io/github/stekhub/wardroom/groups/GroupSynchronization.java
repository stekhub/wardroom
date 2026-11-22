package io.github.stekhub.wardroom.groups;

import java.util.Set;
import java.util.function.Supplier;

/**
 * What subscriptions need of the synchronization (spec 004). The module that synchronizes groups implements this
 * interface as a Spring bean.
 */
public interface GroupSynchronization {

    /**
     * Hands a newly subscribed group to the synchronization, which starts the first synchronization of the group
     * in the background. To be called after the subscription is stored; the call does not wait.
     */
    void groupSubscribed(long groupId);

    /**
     * Starts a synchronization of the group in the background, on the connection that is kept for interactive
     * actions, unless one is running or the login of the server is known not to work. For what the owner just
     * did and waits to see, such as a post (spec 009). The call does not wait and never throws.
     */
    void synchronizeSoon(long groupId);

    /**
     * Runs work that must not happen while the group is synchronized, such as the unsubscribe cleanup. A running
     * synchronization of the group is cancelled; the work runs after the batch that is being stored, and no
     * synchronization of the group starts until the work has ended.
     *
     * @return what the work returned
     */
    <T> T withoutSynchronization(long groupId, Supplier<T> work);

    /** @return the groups that are being synchronized right now, or are waiting for a connection to be */
    Set<Long> synchronizingGroupIds();
}
