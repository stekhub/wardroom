package io.github.stekhub.wardroom.sync.internal;

import io.github.stekhub.wardroom.groups.SubscribedGroups;
import io.github.stekhub.wardroom.groups.SubscribedGroups.SubscribedGroup;
import io.github.stekhub.wardroom.nntp.NntpClient;
import io.github.stekhub.wardroom.nntp.NntpConnections;
import io.github.stekhub.wardroom.nntp.NntpException;
import io.github.stekhub.wardroom.nntp.NntpFailureType;
import io.github.stekhub.wardroom.nntp.NntpServerSettings;
import io.github.stekhub.wardroom.platform.LogSafe;
import io.github.stekhub.wardroom.server.NntpServers;
import io.github.stekhub.wardroom.settings.Settings;
import io.github.stekhub.wardroom.sync.internal.RangeLoader.Direction;
import io.github.stekhub.wardroom.sync.internal.RangeLoader.Jump;
import io.github.stekhub.wardroom.sync.internal.RangeLoader.LoadedRange;
import io.github.stekhub.wardroom.sync.internal.SynchronizationFailures.CredentialsInvalidException;
import io.github.stekhub.wardroom.sync.internal.SynchronizationFailures.GroupBusyException;
import io.github.stekhub.wardroom.sync.internal.SynchronizationFailures.GroupNotFoundException;
import io.github.stekhub.wardroom.sync.internal.SynchronizationFailures.GroupNotSubscribedException;
import io.github.stekhub.wardroom.sync.internal.SynchronizationFailures.ServerFailedException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * "Load older", "load more" and jump to date for the owner who is waiting for them (spec 005).
 *
 * <ul>
 * <li>A step uses the connection that is kept for interactive actions, with a single attempt.</li>
 * <li>A step never runs together with a synchronization of its group or with unsubscribing from it. It waits for
 * a running synchronization, and gives up after a while.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
class RangeLoadService {

    private final SubscribedGroups subscribedGroups;
    private final NntpServers nntpServers;
    private final NntpConnections connections;
    private final Settings settings;
    private final SynchronizationService synchronizationService;
    private final SynchronizationProperties synchronizationProperties;
    private final RangeLoader rangeLoader;

    /** One step on a connection, for a group that nothing else works on meanwhile. */
    @FunctionalInterface
    private interface Step<T> {

        T run(NntpClient client, SubscribedGroup group, int depth);
    }

    LoadedRange extend(long groupId, long rangeId, Direction direction) {
        return load(groupId, (client, group, depth) -> rangeLoader.extend(client, group, rangeId, direction, depth));
    }

    Jump jump(long groupId, long from) {
        return load(groupId, (client, group, depth) -> rangeLoader.jump(client, group, from, depth));
    }

    private <T> T load(long groupId, Step<T> step) {
        Optional<SubscribedGroup> subscribedGroup = subscribedGroups.find(groupId);
        if (subscribedGroup.isEmpty()) {
            if (subscribedGroups.exists(groupId)) {
                throw new GroupNotSubscribedException();
            }
            throw new GroupNotFoundException();
        }
        SubscribedGroup group = subscribedGroup.get();
        if (nntpServers.credentialsInvalid(group.serverId())) {
            throw new CredentialsInvalidException();
        }
        try {
            NntpServerSettings serverSettings = nntpServers.connectionSettings(group.serverId())
                    .orElseThrow(GroupNotFoundException::new);
            int depth = settings.synchronization().initialDepth();
            Optional<T> result = connections.interactiveWithoutRetry(serverSettings,
                    client -> synchronizationService.whenGroupIsFree(groupId, synchronizationProperties.loadWait(),
                            () -> step.run(client, group, depth)));
            return result.orElseThrow(GroupBusyException::new);
        } catch (NntpException e) {
            if (e.type() == NntpFailureType.AUTH_FAILED) {
                nntpServers.markCredentialsInvalid(group.serverId());
                throw new CredentialsInvalidException();
            }
            log.warn("Loading articles of group {} ({}) failed: {} ({})", LogSafe.escape(group.name()), groupId,
                    e.type(), e.getMessage());
            throw new ServerFailedException(e.getMessage());
        }
    }
}
