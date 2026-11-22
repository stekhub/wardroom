package io.github.stekhub.wardroom.sync.internal;

import io.github.stekhub.wardroom.articles.ArticleCache;
import io.github.stekhub.wardroom.groups.SubscribedGroups;
import io.github.stekhub.wardroom.groups.SubscribedGroups.SubscribedGroup;
import io.github.stekhub.wardroom.nntp.NntpException;
import io.github.stekhub.wardroom.nntp.NntpServerSettings;
import io.github.stekhub.wardroom.server.NntpServers;
import io.github.stekhub.wardroom.settings.Settings;
import io.github.stekhub.wardroom.sync.internal.SynchronizationService.Result;
import io.github.stekhub.wardroom.sync.internal.SynchronizationService.Trigger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Synchronizes all subscribed groups at the interval of the setting {@code sync.interval_minutes} (spec 005,
 * "Schedule").
 *
 * <ul>
 * <li>The first run starts shortly after Wardroom has started. When a run was last due is held in memory.</li>
 * <li>A run that is still going when the next is due causes that next run to be skipped.</li>
 * <li>Per server, at most {@code maxConnections − 1} groups (at least one) are synchronized in parallel.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class SynchronizationSchedule {

    private final SynchronizationProperties synchronizationProperties;
    private final SubscribedGroups subscribedGroups;
    private final NntpServers nntpServers;
    private final Settings settings;
    private final SynchronizationService synchronizationService;
    private final ArticleCache articleCache;

    private final AtomicBoolean runInProgress = new AtomicBoolean();
    private Long lastDueAt;

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT30S")
    void everyHalfMinute() {
        if (synchronizationProperties.automatic()) {
            runIfDue(System.currentTimeMillis());
        }
    }

    /**
     * Starts a run when the interval has passed since a run was last due. A changed interval counts from then on.
     *
     * @param now the current time in milliseconds
     * @return whether a run was started
     */
    synchronized boolean runIfDue(long now) {
        long intervalMillis = Duration.ofMinutes(settings.synchronization().syncIntervalMinutes()).toMillis();
        if (lastDueAt != null && now - lastDueAt < intervalMillis) {
            return false;
        }
        lastDueAt = now;
        if (!startRun()) {
            log.info("Synchronization run skipped: the previous run is still going");
            return false;
        }
        return true;
    }

    /**
     * Starts a run in the background unless one is going.
     *
     * @return whether a run was started
     */
    boolean startRun() {
        if (!runInProgress.compareAndSet(false, true)) {
            return false;
        }
        Thread.ofVirtual().name("sync-run").start(() -> {
            try {
                run();
            } catch (RuntimeException e) {
                log.error("The synchronization run failed unexpectedly", e);
            } finally {
                runInProgress.set(false);
            }
            // The run may have made the database larger than the cache size limit allows (ADR 0023).
            articleCache.cleanUpSoon();
        });
        return true;
    }

    boolean isRunInProgress() {
        return runInProgress.get();
    }

    private void run() {
        Map<Long, List<SubscribedGroup>> groupsByServer = new LinkedHashMap<>();
        for (SubscribedGroup group : subscribedGroups.findAll()) {
            groupsByServer.computeIfAbsent(group.serverId(), serverId -> new ArrayList<>()).add(group);
        }
        if (groupsByServer.isEmpty()) {
            return;
        }
        long startedAt = System.currentTimeMillis();
        int groupCount = groupsByServer.values().stream().mapToInt(List::size).sum();
        log.info("Synchronization run started: {} subscribed groups", groupCount);
        // A server that is slow or down does not hold up the others (ADR 0006).
        List<Thread> serverThreads = new ArrayList<>();
        groupsByServer.forEach((serverId, groups) -> serverThreads.add(Thread.ofVirtual()
                .name("sync-run-server-" + serverId)
                .start(() -> runServer(serverId, groups))));
        if (joinAll(serverThreads)) {
            log.info("Synchronization run ended after {} ms", System.currentTimeMillis() - startedAt);
        }
    }

    /**
     * The groups of one server. The run stops for the server when it rejects the login, and when its circuit is
     * open: the remaining groups keep the status they have.
     */
    private void runServer(long serverId, List<SubscribedGroup> groups) {
        if (nntpServers.credentialsInvalid(serverId)) {
            log.info("Synchronization run skips server {}: its username and password are invalid", serverId);
            return;
        }
        Optional<NntpServerSettings> serverSettings;
        try {
            serverSettings = nntpServers.connectionSettings(serverId);
        } catch (NntpException e) {
            log.warn("Synchronization run skips server {}: {}", serverId, e.getMessage());
            return;
        }
        if (serverSettings.isEmpty()) {
            return;
        }
        // One connection is kept for interactive actions (spec 005).
        int parallelGroups = Math.min(groups.size(), Math.max(1, serverSettings.get().maxConnections() - 1));
        Queue<SubscribedGroup> waitingGroups = new ConcurrentLinkedQueue<>(groups);
        AtomicBoolean stopped = new AtomicBoolean();
        List<Thread> workerThreads = new ArrayList<>();
        for (int worker = 1; worker <= parallelGroups; worker++) {
            workerThreads.add(Thread.ofVirtual()
                    .name("sync-run-server-" + serverId + "-" + worker)
                    .start(() -> synchronizeWaitingGroups(serverId, waitingGroups, stopped)));
        }
        joinAll(workerThreads);
    }

    private void synchronizeWaitingGroups(long serverId, Queue<SubscribedGroup> waitingGroups,
            AtomicBoolean stopped) {
        SubscribedGroup group;
        while (!stopped.get() && (group = waitingGroups.poll()) != null) {
            Result result = synchronizationService.synchronize(group, Trigger.SCHEDULE);
            if ((result == Result.CREDENTIALS_REJECTED || result == Result.CIRCUIT_OPEN)
                    && stopped.compareAndSet(false, true)) {
                log.warn("Synchronization run stops for server {}: {}", serverId,
                        result == Result.CREDENTIALS_REJECTED
                                ? "the server rejected the login"
                                : "the server failed repeatedly and is not contacted for a while");
            }
        }
    }

    /** @return {@code false} when the waiting was interrupted, which happens when Wardroom shuts down */
    private static boolean joinAll(List<Thread> threads) {
        try {
            for (Thread thread : threads) {
                thread.join();
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
