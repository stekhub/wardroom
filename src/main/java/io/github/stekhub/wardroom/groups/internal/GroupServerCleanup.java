package io.github.stekhub.wardroom.groups.internal;

import io.github.stekhub.wardroom.server.ServerRemovalCleanup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Removing a server deletes its groups (spec 002). */
@Component
@RequiredArgsConstructor
class GroupServerCleanup implements ServerRemovalCleanup {

    private final GroupRepository groupRepository;
    private final GroupListRefreshService groupListRefreshService;

    @Override
    public void removeServerData(long serverId) {
        groupRepository.deleteByServer(serverId);
        groupListRefreshService.forget(serverId);
    }
}
