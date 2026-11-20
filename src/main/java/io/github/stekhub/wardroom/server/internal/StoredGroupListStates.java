package io.github.stekhub.wardroom.server.internal;

import io.github.stekhub.wardroom.server.GroupListStates;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The group list columns of the server row, for the module that refreshes the group list. */
@Component
@RequiredArgsConstructor
class StoredGroupListStates implements GroupListStates {

    private final ServerRepository serverRepository;

    @Override
    public Optional<GroupListState> find(long serverId) {
        return serverRepository.findGroupListState(serverId);
    }

    @Override
    public List<GroupListState> findAll() {
        return serverRepository.findGroupListStates();
    }

    @Override
    public void refreshSucceeded(long serverId, long refreshedAt) {
        serverRepository.setGroupListRefreshed(serverId, refreshedAt);
    }

    @Override
    public void refreshFailed(long serverId, String error) {
        serverRepository.setGroupListRefreshError(serverId, error);
    }
}
