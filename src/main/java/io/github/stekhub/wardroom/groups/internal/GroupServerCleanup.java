package io.github.stekhub.wardroom.groups.internal;

import io.github.stekhub.wardroom.server.ServerRemovalCleanup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Removing a server deletes its groups, their subscriptions and their cached articles (spec 002). */
@Component
@RequiredArgsConstructor
class GroupServerCleanup implements ServerRemovalCleanup {

    private final GroupRepository groupRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final GroupListRefreshService groupListRefreshService;

    @Override
    public void removeServerData(long serverId) {
        subscriptionRepository.deleteByServer(serverId);
        groupRepository.deleteByServer(serverId);
        groupListRefreshService.forget(serverId);
    }
}
