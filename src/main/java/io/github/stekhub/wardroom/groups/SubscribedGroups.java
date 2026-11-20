package io.github.stekhub.wardroom.groups;

import java.util.List;
import java.util.Optional;

/**
 * What the synchronization needs of the subscribed groups: which groups there are, and where it records what it
 * found out about a group (data model: {@code newsgroup}; spec 005).
 */
public interface SubscribedGroups {

    /** @return all subscribed groups, ordered by name */
    List<SubscribedGroup> findAll();

    /** @return the group, or empty when it is not subscribed or no group has this ID */
    Optional<SubscribedGroup> find(long groupId);

    /** @return whether a group has this ID, subscribed or not */
    boolean exists(long groupId);

    /** Stores the watermarks the server reported when the group was selected. */
    void storeWatermarks(long groupId, long lowWatermark, long highWatermark);

    /** Records a successful synchronization: the time is stored and the error of an earlier one is cleared. */
    void synchronizationSucceeded(long groupId, long syncedAt);

    /**
     * Records a failed synchronization. The time of the last successful one is kept.
     *
     * @param error the cause in words for the owner; never contains credentials
     */
    void synchronizationFailed(long groupId, String error);

    /**
     * Deletes the article locations of the group, the articles that have no location left, with their read state,
     * and the synchronized ranges of the group: steps 2 to 4 of the unsubscribe cleanup (data model, lifecycle
     * rules). The subscription stays. To be called in a transaction.
     */
    void removeArticles(long groupId);

    /**
     * @param groupId  the group
     * @param serverId the server the group is on
     * @param name     the name of the group, as the server sent it; untrusted text
     */
    record SubscribedGroup(long groupId, long serverId, String name) {
    }
}
