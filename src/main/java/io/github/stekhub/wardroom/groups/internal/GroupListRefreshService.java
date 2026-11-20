package io.github.stekhub.wardroom.groups.internal;

import io.github.stekhub.wardroom.groups.internal.GroupFailures.CredentialsInvalidException;
import io.github.stekhub.wardroom.groups.internal.GroupFailures.ServerNotFoundException;
import io.github.stekhub.wardroom.groups.internal.GroupListRefresher.Outcome;
import io.github.stekhub.wardroom.groups.internal.GroupResponses.RefreshStatus;
import io.github.stekhub.wardroom.nntp.NntpException;
import io.github.stekhub.wardroom.nntp.NntpFailureType;
import io.github.stekhub.wardroom.nntp.NntpServerSettings;
import io.github.stekhub.wardroom.server.GroupListStates;
import io.github.stekhub.wardroom.server.GroupListStates.GroupListState;
import io.github.stekhub.wardroom.server.NntpServers;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Starts the group list refresh of a server as a background job and knows its state (spec 003). At most one
 * refresh per server runs at a time. The running state and the number of groups received are held in memory; after
 * a restart no refresh is running.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class GroupListRefreshService {

    private static final String UNEXPECTED_FAILURE =
            "The group list could not be stored. The log of Wardroom has the details.";

    private final GroupListStates groupListStates;
    private final NntpServers nntpServers;
    private final GroupListRefresher groupListRefresher;

    private final Map<Long, Progress> progressByServer = new ConcurrentHashMap<>();

    /** What is known in memory about the refreshes of one server. */
    private static final class Progress {

        private boolean running;
        private int groupsReceived;
        private Long failedAt;
    }

    /**
     * The manual refresh of the owner.
     *
     * @throws ServerNotFoundException     when no server has this ID
     * @throws CredentialsInvalidException when the login of the server is known not to work
     */
    void requestRefresh(long serverId) {
        GroupListState state = groupListStates.find(serverId).orElseThrow(ServerNotFoundException::new);
        if (state.credentialsInvalid()) {
            throw new CredentialsInvalidException();
        }
        start(serverId);
    }

    /**
     * Starts a refresh unless one is running for the server.
     *
     * @return whether a refresh was started
     */
    boolean start(long serverId) {
        Progress progress = progressByServer.computeIfAbsent(serverId, id -> new Progress());
        synchronized (progress) {
            if (progress.running) {
                return false;
            }
            progress.running = true;
            progress.groupsReceived = 0;
        }
        Thread.ofVirtual().name("group-list-refresh-" + serverId).start(() -> run(serverId, progress));
        return true;
    }

    /** @throws ServerNotFoundException when no server has this ID */
    RefreshStatus status(long serverId) {
        GroupListState state = groupListStates.find(serverId).orElseThrow(ServerNotFoundException::new);
        Progress progress = progressByServer.get(serverId);
        if (progress == null) {
            return new RefreshStatus(false, 0, state.refreshedAt(), state.refreshError());
        }
        synchronized (progress) {
            return new RefreshStatus(progress.running, progress.groupsReceived, state.refreshedAt(),
                    state.refreshError());
        }
    }

    boolean isRunning(long serverId) {
        Progress progress = progressByServer.get(serverId);
        if (progress == null) {
            return false;
        }
        synchronized (progress) {
            return progress.running;
        }
    }

    /** @return when the last refresh of the server failed, unless one has succeeded since or Wardroom restarted */
    Optional<Long> failedAt(long serverId) {
        Progress progress = progressByServer.get(serverId);
        if (progress == null) {
            return Optional.empty();
        }
        synchronized (progress) {
            return Optional.ofNullable(progress.failedAt);
        }
    }

    /** Forgets what is known in memory about a server that was removed. */
    void forget(long serverId) {
        progressByServer.remove(serverId);
    }

    private void run(long serverId, Progress progress) {
        long startedAt = System.currentTimeMillis();
        log.info("Group list refresh of server {} started", serverId);
        Long failedAt = null;
        try {
            Optional<NntpServerSettings> serverSettings = nntpServers.connectionSettings(serverId);
            if (serverSettings.isEmpty()) {
                log.info("Group list refresh of server {} ended: the server was removed", serverId);
                return;
            }
            Outcome outcome = groupListRefresher.refresh(serverSettings.get(), groupsReceived -> {
                synchronized (progress) {
                    progress.groupsReceived = groupsReceived;
                }
            });
            groupListStates.refreshSucceeded(serverId, System.currentTimeMillis());
            log.info("Group list refresh of server {} succeeded in {} ms: {} groups, {} newly removed, {} returned,"
                            + " descriptions {}", serverId, System.currentTimeMillis() - startedAt,
                    outcome.groupsReceived(), outcome.removed(), outcome.returned(),
                    outcome.descriptionsRead() ? "read" : "not offered by the server");
        } catch (NntpException e) {
            failedAt = System.currentTimeMillis();
            if (e.type() == NntpFailureType.AUTH_FAILED) {
                nntpServers.markCredentialsInvalid(serverId);
            }
            groupListStates.refreshFailed(serverId, e.getMessage());
            log.warn("Group list refresh of server {} failed: {} ({})", serverId, e.type(), e.getMessage());
        } catch (RuntimeException e) {
            failedAt = System.currentTimeMillis();
            log.error("Group list refresh of server {} failed unexpectedly", serverId, e);
            recordUnexpectedFailure(serverId);
        } finally {
            synchronized (progress) {
                progress.running = false;
                progress.failedAt = failedAt;
            }
        }
    }

    /** The database may be the cause of the failure, so writing the error may fail as well. */
    private void recordUnexpectedFailure(long serverId) {
        try {
            groupListStates.refreshFailed(serverId, UNEXPECTED_FAILURE);
        } catch (RuntimeException e) {
            log.error("The failure of the group list refresh of server {} could not be stored", serverId, e);
        }
    }
}
