package io.github.stekhub.wardroom.groups.internal;

import io.github.stekhub.wardroom.server.GroupListStates;
import io.github.stekhub.wardroom.server.GroupListStates.GroupListState;
import io.github.stekhub.wardroom.server.ServerCreated;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Fetches the group list without a request of the owner: when a server is created, and on a schedule (spec 003,
 * ADR 0020).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class GroupListSchedule {

    /**
     * The default of the setting {@code sync.group_list_interval_hours}. The setting itself comes with the settings
     * section "Synchronization".
     */
    static final Duration REFRESH_INTERVAL = Duration.ofHours(24);

    /** A failed refresh is attempted again after this time (ADR 0020). */
    static final Duration RETRY_AFTER_FAILURE = Duration.ofHours(1);

    private final GroupListProperties groupListProperties;
    private final GroupListStates groupListStates;
    private final GroupListRefreshService groupListRefreshService;

    /** The refresh starts when the new server is stored, not before: the job reads it from the database. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void serverCreated(ServerCreated serverCreated) {
        if (groupListProperties.automaticRefresh()) {
            groupListRefreshService.start(serverCreated.serverId());
        }
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    void everyMinute() {
        if (groupListProperties.automaticRefresh()) {
            startDueRefreshes(System.currentTimeMillis());
        }
    }

    /**
     * Starts the refresh of every server whose group list is due.
     *
     * @param now the current time in milliseconds
     */
    void startDueRefreshes(long now) {
        for (GroupListState state : groupListStates.findAll()) {
            if (isDue(state, now) && groupListRefreshService.start(state.serverId())) {
                log.info("Scheduled group list refresh of server {}", state.serverId());
            }
        }
    }

    /**
     * A list is due when the interval has passed since the last successful refresh, or when it was never fetched.
     * After a failure it is due one hour later. A server whose login is known not to work is left alone until the
     * owner has entered new credentials: every attempt would be another rejected login.
     */
    private boolean isDue(GroupListState state, long now) {
        if (state.credentialsInvalid() || groupListRefreshService.isRunning(state.serverId())) {
            return false;
        }
        Optional<Long> failedAt = groupListRefreshService.failedAt(state.serverId());
        if (failedAt.isPresent()) {
            return now - failedAt.get() >= RETRY_AFTER_FAILURE.toMillis();
        }
        return state.refreshedAt() == null || now - state.refreshedAt() >= REFRESH_INTERVAL.toMillis();
    }
}
