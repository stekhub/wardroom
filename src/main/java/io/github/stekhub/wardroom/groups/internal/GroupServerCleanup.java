package io.github.stekhub.wardroom.groups.internal;

import io.github.stekhub.wardroom.groups.ThreadRecomputation;
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
    private final ThreadRecomputation threadRecomputation;

    @Override
    public void removeServerData(long serverId) {
        subscriptionRepository.deleteByServer(serverId);
        groupRepository.deleteByServer(serverId);
        // The cleanup leaves stubs alone. The complete recomputation deletes them all and creates those again
        // that the articles of other servers still reference.
        threadRecomputation.recomputeAllThreads();
        groupListRefreshService.forget(serverId);
    }
}
