package io.github.stekhub.wardroom.sync.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.stekhub.wardroom.groups.SubscribedGroups;
import io.github.stekhub.wardroom.groups.SubscribedGroups.SubscribedGroup;
import io.github.stekhub.wardroom.nntp.NntpConnections;
import io.github.stekhub.wardroom.nntp.NntpException;
import io.github.stekhub.wardroom.nntp.NntpFailureType;
import io.github.stekhub.wardroom.nntp.NntpServerSettings;
import io.github.stekhub.wardroom.nntp.NntpTlsMode;
import io.github.stekhub.wardroom.server.NntpServers;
import io.github.stekhub.wardroom.settings.Settings;
import io.github.stekhub.wardroom.settings.SynchronizationSettings;
import io.github.stekhub.wardroom.sync.internal.SynchronizationService.Result;
import io.github.stekhub.wardroom.sync.internal.SynchronizationService.Trigger;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The failures of spec 005 that the mock NNTP server cannot produce without TLS, which a login needs: a rejected
 * login ({@code 481}). Without Spring, with the collaborators replaced.
 */
class SynchronizationFailureTest {

    private static final long SERVER_ID = 1;
    private static final SubscribedGroup FIRST = new SubscribedGroup(11, SERVER_ID, "example.first");
    private static final SubscribedGroup SECOND = new SubscribedGroup(12, SERVER_ID, "example.second");
    private static final SubscribedGroup THIRD = new SubscribedGroup(13, SERVER_ID, "example.third");

    /** Two connections: one group at a time in a run. */
    private static final NntpServerSettings SERVER_SETTINGS =
            new NntpServerSettings(SERVER_ID, "news.example.org", 563, NntpTlsMode.IMPLICIT, null, null, 2);

    private final SubscribedGroups subscribedGroups = mock(SubscribedGroups.class);
    private final NntpServers nntpServers = mock(NntpServers.class);
    private final NntpConnections connections = mock(NntpConnections.class);
    private final Settings settings = mock(Settings.class);

    private SynchronizationService synchronizationService;

    @BeforeEach
    void createService() {
        when(settings.synchronization()).thenReturn(SynchronizationSettings.DEFAULTS);
        when(nntpServers.connectionSettings(SERVER_ID)).thenReturn(Optional.of(SERVER_SETTINGS));
        when(subscribedGroups.findAll()).thenReturn(List.of(FIRST, SECOND, THIRD));
        synchronizationService = new SynchronizationService(subscribedGroups, nntpServers, connections, settings,
                mock(GroupSynchronizer.class), new SynchronizationProperties(true));
    }

    @Test
    void aRejectedLoginMarksTheCredentialsAndLeavesTheGroupAsItIs() {
        when(connections.background(eq(SERVER_SETTINGS), any())).thenThrow(new NntpException(
                NntpFailureType.AUTH_FAILED, "The server rejected the user name or password (481)"));

        Result result = synchronizationService.synchronize(FIRST, Trigger.SCHEDULE);

        assertThat(result).isEqualTo(Result.CREDENTIALS_REJECTED);
        verify(nntpServers).markCredentialsInvalid(SERVER_ID);
        verify(subscribedGroups, never()).synchronizationFailed(eq(FIRST.groupId()), anyString());
        assertThat(synchronizationService.synchronizingGroupIds()).isEmpty();
    }

    @Test
    void aRunStopsForTheServerThatRejectedTheLogin() {
        when(connections.background(eq(SERVER_SETTINGS), any())).thenThrow(new NntpException(
                NntpFailureType.AUTH_FAILED, "The server rejected the user name or password (481)"));
        SynchronizationSchedule synchronizationSchedule = new SynchronizationSchedule(
                new SynchronizationProperties(true), subscribedGroups, nntpServers, settings, synchronizationService);

        assertThat(synchronizationSchedule.startRun()).isTrue();
        await().atMost(Duration.ofSeconds(10)).until(() -> !synchronizationSchedule.isRunInProgress());

        // The first group met the rejected login; the other two were not tried.
        verify(connections).background(eq(SERVER_SETTINGS), any());
        verify(nntpServers).markCredentialsInvalid(SERVER_ID);
    }

    @Test
    void aRunLeavesAServerWithInvalidCredentialsAlone() {
        when(nntpServers.credentialsInvalid(SERVER_ID)).thenReturn(true);
        SynchronizationSchedule synchronizationSchedule = new SynchronizationSchedule(
                new SynchronizationProperties(true), subscribedGroups, nntpServers, settings, synchronizationService);

        assertThat(synchronizationSchedule.startRun()).isTrue();
        await().atMost(Duration.ofSeconds(10)).until(() -> !synchronizationSchedule.isRunInProgress());

        verify(connections, never()).background(any(), any());
        verify(nntpServers, never()).connectionSettings(SERVER_ID);
    }

    @Test
    void anUnexpectedFailureIsRecordedWithoutItsDetails() {
        when(connections.background(eq(SERVER_SETTINGS), any()))
                .thenThrow(new IllegalStateException("database is locked"));

        Result result = synchronizationService.synchronize(FIRST, Trigger.SCHEDULE);

        assertThat(result).isEqualTo(Result.FAILED);
        verify(subscribedGroups).synchronizationFailed(FIRST.groupId(),
                "The synchronization failed unexpectedly. The log of Wardroom has the details.");
    }

    @Test
    void anOpenCircuitIsRecordedForTheOwnerWhoAskedButNotForTheSchedule() {
        NntpException circuitOpen = new NntpException(NntpFailureType.CIRCUIT_OPEN,
                "The server failed repeatedly and is not contacted for a while");
        when(connections.background(eq(SERVER_SETTINGS), any())).thenThrow(circuitOpen);
        when(connections.interactive(eq(SERVER_SETTINGS), any())).thenThrow(circuitOpen);

        assertThat(synchronizationService.synchronize(FIRST, Trigger.SCHEDULE)).isEqualTo(Result.CIRCUIT_OPEN);
        verify(subscribedGroups, never()).synchronizationFailed(eq(FIRST.groupId()), anyString());

        assertThat(synchronizationService.synchronize(FIRST, Trigger.MANUAL)).isEqualTo(Result.CIRCUIT_OPEN);
        verify(subscribedGroups).synchronizationFailed(FIRST.groupId(),
                "The server failed repeatedly and is not contacted for a while");
    }
}
