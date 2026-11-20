package io.github.stekhub.wardroom.groups.internal;

import io.github.stekhub.wardroom.groups.SubscribedGroups;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The subscribed groups and their synchronization columns, for the module that synchronizes them. */
@Component
@RequiredArgsConstructor
class StoredSubscribedGroups implements SubscribedGroups {

    private final SubscriptionRepository subscriptionRepository;

    @Override
    public List<SubscribedGroup> findAll() {
        return subscriptionRepository.findSubscribedGroups();
    }

    @Override
    public Optional<SubscribedGroup> find(long groupId) {
        return subscriptionRepository.findSubscribedGroup(groupId);
    }

    @Override
    public boolean exists(long groupId) {
        return subscriptionRepository.findGroupState(groupId).isPresent();
    }

    @Override
    public void storeWatermarks(long groupId, long lowWatermark, long highWatermark) {
        subscriptionRepository.setWatermarks(groupId, lowWatermark, highWatermark);
    }

    @Override
    public void synchronizationSucceeded(long groupId, long syncedAt) {
        subscriptionRepository.setSynchronized(groupId, syncedAt);
    }

    @Override
    public void synchronizationFailed(long groupId, String error) {
        subscriptionRepository.setSynchronizationError(groupId, error);
    }

    @Override
    public void removeArticles(long groupId) {
        subscriptionRepository.removeArticles(groupId);
    }
}
