package io.github.stekhub.wardroom.sync.internal;

import io.github.stekhub.wardroom.groups.GroupSynchronization;
import io.github.stekhub.wardroom.groups.SubscribedGroups;
import io.github.stekhub.wardroom.groups.SubscribedGroups.SubscribedGroup;
import io.github.stekhub.wardroom.nntp.NntpAction;
import io.github.stekhub.wardroom.nntp.NntpConnections;
import io.github.stekhub.wardroom.nntp.NntpException;
import io.github.stekhub.wardroom.nntp.NntpFailureType;
import io.github.stekhub.wardroom.nntp.NntpServerSettings;
import io.github.stekhub.wardroom.platform.LogSafe;
import io.github.stekhub.wardroom.server.NntpServers;
import io.github.stekhub.wardroom.settings.Settings;
import io.github.stekhub.wardroom.sync.internal.GroupSynchronizer.End;
import io.github.stekhub.wardroom.sync.internal.GroupSynchronizer.Progress;
import io.github.stekhub.wardroom.sync.internal.SynchronizationFailures.CredentialsInvalidException;
import io.github.stekhub.wardroom.sync.internal.SynchronizationFailures.GroupNotFoundException;
import io.github.stekhub.wardroom.sync.internal.SynchronizationFailures.GroupNotSubscribedException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Synchronizes single groups and keeps their synchronizations apart from each other and from unsubscribing
 * (spec 004, spec 005).
 *
 * <ul>
 * <li>A group is synchronized by one job at a time. A second request for a group that is being synchronized, or is
 * waiting for a connection, does nothing.</li>
 * <li>A synchronization holds the lock of its group while it works. Unsubscribing asks it to stop, and takes the
 * lock when the chunk that is being stored is done.</li>
 * </ul>
 *
 * <p>Which groups are being synchronized is held in memory; after a restart none is.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class SynchronizationService implements GroupSynchronization {

    private static final String GROUP_UNAVAILABLE = "The group is not available on the server.";
    private static final String UNEXPECTED_FAILURE =
            "The synchronization failed unexpectedly. The log of Wardroom has the details.";

    private final SubscribedGroups subscribedGroups;
    private final NntpServers nntpServers;
    private final NntpConnections connections;
    private final Settings settings;
    private final GroupSynchronizer groupSynchronizer;
    private final SynchronizationProperties synchronizationProperties;

    private final Set<Long> synchronizingGroupIds = ConcurrentHashMap.newKeySet();
    private final Set<Long> cancelRequests = ConcurrentHashMap.newKeySet();
    private final Map<Long, ReentrantLock> groupLocks = new ConcurrentHashMap<>();

    /** What starts a synchronization. */
    enum Trigger {
        /** The schedule; uses a background connection. */
        SCHEDULE,
        /** A new subscription; uses a background connection. */
        SUBSCRIPTION,
        /** The refresh button of the owner; uses the connection kept for interactive actions. */
        MANUAL
    }

    /** How the synchronization of one group ended, for the run that started it. */
    enum Result {
        SYNCHRONIZED,
        /** Nothing was done: the group is being synchronized already, is not subscribed, or was cancelled. */
        NOT_RUN,
        GROUP_UNAVAILABLE,
        FAILED,
        /** The server rejected the login, or its credentials are known to be invalid. */
        CREDENTIALS_REJECTED,
        /** The circuit breaker of the server is open; the server was not contacted. */
        CIRCUIT_OPEN
    }

    @Override
    public void groupSubscribed(long groupId) {
        if (!synchronizationProperties.automatic()) {
            return;
        }
        subscribedGroups.find(groupId).ifPresent(group -> startInBackground(group, Trigger.SUBSCRIPTION));
    }

    @Override
    public void synchronizeSoon(long groupId) {
        if (!synchronizationProperties.automatic()) {
            return;
        }
        subscribedGroups.find(groupId)
                .filter(group -> !nntpServers.credentialsInvalid(group.serverId()))
                .ifPresent(group -> startInBackground(group, Trigger.MANUAL));
    }

    @Override
    public <T> T withoutSynchronization(long groupId, Supplier<T> work) {
        cancelRequests.add(groupId);
        ReentrantLock groupLock = lockOf(groupId);
        groupLock.lock();
        try {
            return work.get();
        } finally {
            cancelRequests.remove(groupId);
            groupLock.unlock();
        }
    }

    @Override
    public Set<Long> synchronizingGroupIds() {
        return Set.copyOf(synchronizingGroupIds);
    }

    /**
     * The manual refresh of the owner: starts the synchronization of the group unless one is running.
     *
     * @throws GroupNotFoundException      when no group has this ID
     * @throws GroupNotSubscribedException when the group is not subscribed
     * @throws CredentialsInvalidException when the login of the server is known not to work
     */
    void requestSynchronization(long groupId) {
        Optional<SubscribedGroup> group = subscribedGroups.find(groupId);
        if (group.isEmpty()) {
            if (subscribedGroups.exists(groupId)) {
                throw new GroupNotSubscribedException();
            }
            throw new GroupNotFoundException();
        }
        if (nntpServers.credentialsInvalid(group.get().serverId())) {
            throw new CredentialsInvalidException();
        }
        startInBackground(group.get(), Trigger.MANUAL);
    }

    /** Synchronizes the group and returns when it is done. */
    Result synchronize(SubscribedGroup group, Trigger trigger) {
        if (!synchronizingGroupIds.add(group.groupId())) {
            return Result.NOT_RUN;
        }
        try {
            return run(group, trigger);
        } finally {
            synchronizingGroupIds.remove(group.groupId());
        }
    }

    private void startInBackground(SubscribedGroup group, Trigger trigger) {
        if (!synchronizingGroupIds.add(group.groupId())) {
            return;
        }
        Thread.ofVirtual().name("group-sync-" + group.groupId()).start(() -> {
            try {
                run(group, trigger);
            } finally {
                synchronizingGroupIds.remove(group.groupId());
            }
        });
    }

    private Result run(SubscribedGroup group, Trigger trigger) {
        long startedAt = System.currentTimeMillis();
        long groupId = group.groupId();
        String groupName = LogSafe.escape(group.name());
        Progress progress = new Progress();
        try {
            Optional<NntpServerSettings> serverSettings = nntpServers.connectionSettings(group.serverId());
            if (serverSettings.isEmpty()) {
                return Result.NOT_RUN;
            }
            int initialDepth = settings.synchronization().initialDepth();
            NntpAction<End> synchronization = client -> underGroupLock(groupId, () -> groupSynchronizer.synchronize(
                    client, group, initialDepth, () -> cancelRequests.contains(groupId), progress));
            End end = trigger == Trigger.MANUAL
                    ? connections.interactive(serverSettings.get(), synchronization)
                    : connections.background(serverSettings.get(), synchronization);
            return switch (end) {
                case COMPLETED -> {
                    log.info("Synchronization of group {} ({}) succeeded in {} ms: {} overviews fetched, {} new"
                                    + " articles, {} stubs filled in, {} locations expired{}", groupName, groupId,
                            System.currentTimeMillis() - startedAt, progress.overviewsFetched, progress.newArticles,
                            progress.filledStubs, progress.expiredLocations,
                            progress.renumbered ? ", after the server renumbered the group" : "");
                    yield Result.SYNCHRONIZED;
                }
                case GROUP_UNAVAILABLE -> {
                    subscribedGroups.synchronizationFailed(groupId, GROUP_UNAVAILABLE);
                    log.warn("Synchronization of group {} ({}) failed: the server has no group of this name",
                            groupName, groupId);
                    yield Result.GROUP_UNAVAILABLE;
                }
                case CANCELLED, NOT_SUBSCRIBED -> {
                    log.info("Synchronization of group {} ({}) ended early: the group is not subscribed any more",
                            groupName, groupId);
                    yield Result.NOT_RUN;
                }
            };
        } catch (NntpException e) {
            return failed(group, trigger, e);
        } catch (RuntimeException e) {
            log.error("Synchronization of group {} ({}) failed unexpectedly", groupName, groupId, e);
            recordUnexpectedFailure(groupId);
            return Result.FAILED;
        }
    }

    /**
     * The status of spec 005, "Error handling". A rejected login marks the credentials of the server and leaves
     * the group as it is. A scheduled run that finds the circuit open leaves the group as it is as well; for the
     * owner who pressed the refresh button, the group says why nothing happened.
     */
    private Result failed(SubscribedGroup group, Trigger trigger, NntpException failure) {
        String groupName = LogSafe.escape(group.name());
        if (failure.type() == NntpFailureType.AUTH_FAILED) {
            nntpServers.markCredentialsInvalid(group.serverId());
            log.warn("Synchronization of group {} ({}) failed: the server rejected the login", groupName,
                    group.groupId());
            return Result.CREDENTIALS_REJECTED;
        }
        if (failure.type() == NntpFailureType.CIRCUIT_OPEN && trigger != Trigger.MANUAL) {
            log.debug("Synchronization of group {} ({}) skipped: the circuit of the server is open", groupName,
                    group.groupId());
            return Result.CIRCUIT_OPEN;
        }
        subscribedGroups.synchronizationFailed(group.groupId(), failure.getMessage());
        log.warn("Synchronization of group {} ({}) failed: {} ({})", groupName, group.groupId(), failure.type(),
                failure.getMessage());
        return failure.type() == NntpFailureType.CIRCUIT_OPEN ? Result.CIRCUIT_OPEN : Result.FAILED;
    }

    /** The database may be the cause of the failure, so writing the error may fail as well. */
    private void recordUnexpectedFailure(long groupId) {
        try {
            subscribedGroups.synchronizationFailed(groupId, UNEXPECTED_FAILURE);
        } catch (RuntimeException e) {
            log.error("The failure of the synchronization of group {} could not be stored", groupId, e);
        }
    }

    /**
     * Runs work that must not run together with a synchronization of the group, or with unsubscribing from it:
     * loading and jumping (spec 005, "Concurrency"). Unlike unsubscribing, it does not ask a running
     * synchronization to stop, but waits for it.
     *
     * @return the result of the work, or empty when the group did not become free in time; the work did not run
     *         then
     */
    <T> Optional<T> whenGroupIsFree(long groupId, Duration longestWait, Supplier<T> work) {
        ReentrantLock groupLock = lockOf(groupId);
        try {
            if (!groupLock.tryLock(longestWait.toMillis(), TimeUnit.MILLISECONDS)) {
                return Optional.empty();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        try {
            return Optional.of(work.get());
        } finally {
            groupLock.unlock();
        }
    }

    private <T> T underGroupLock(long groupId, Supplier<T> work) {
        ReentrantLock groupLock = lockOf(groupId);
        groupLock.lock();
        try {
            return work.get();
        } finally {
            groupLock.unlock();
        }
    }

    private ReentrantLock lockOf(long groupId) {
        return groupLocks.computeIfAbsent(groupId, id -> new ReentrantLock());
    }
}
