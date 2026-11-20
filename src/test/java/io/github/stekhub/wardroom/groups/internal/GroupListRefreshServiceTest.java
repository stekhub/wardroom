package io.github.stekhub.wardroom.groups.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.stekhub.wardroom.nntp.NntpException;
import io.github.stekhub.wardroom.nntp.NntpFailureType;
import io.github.stekhub.wardroom.nntp.NntpServerSettings;
import io.github.stekhub.wardroom.nntp.NntpTlsMode;
import io.github.stekhub.wardroom.server.GroupListStates;
import io.github.stekhub.wardroom.server.NntpServers;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** How the refresh job records its failures (spec 003, "Error handling"), without Spring and without a server. */
class GroupListRefreshServiceTest {

    private static final long SERVER_ID = 7;
    private static final NntpServerSettings SERVER_SETTINGS =
            new NntpServerSettings(SERVER_ID, "news.example.com", 119, NntpTlsMode.NONE, null, null, 4);

    private final GroupListStates groupListStates = mock(GroupListStates.class);
    private final NntpServers nntpServers = mock(NntpServers.class);
    private final GroupListRefresher groupListRefresher = mock(GroupListRefresher.class);
    private final GroupListRefreshService groupListRefreshService =
            new GroupListRefreshService(groupListStates, nntpServers, groupListRefresher);

    @BeforeEach
    void serverExists() {
        when(nntpServers.connectionSettings(SERVER_ID)).thenReturn(Optional.of(SERVER_SETTINGS));
    }

    @Test
    void rejectedLoginFailsTheRefreshAndMarksTheCredentialsAsInvalid() {
        when(groupListRefresher.refresh(eq(SERVER_SETTINGS), any())).thenThrow(new NntpException(
                NntpFailureType.AUTH_FAILED, "The server rejected the user name or password (481)"));

        runRefresh();

        verify(nntpServers).markCredentialsInvalid(SERVER_ID);
        verify(groupListStates).refreshFailed(SERVER_ID, "The server rejected the user name or password (481)");
        verify(groupListStates, never()).refreshSucceeded(anyLong(), anyLong());
        assertThat(groupListRefreshService.failedAt(SERVER_ID)).isPresent();
    }

    @Test
    void passwordThatCannotBeDecryptedFailsTheRefreshBeforeTheServerIsContacted() {
        when(nntpServers.connectionSettings(SERVER_ID)).thenThrow(new NntpException(NntpFailureType.AUTH_FAILED,
                "The stored password can no longer be read. Enter the password again."));

        runRefresh();

        verify(groupListRefresher, never()).refresh(any(), any());
        verify(groupListStates).refreshFailed(SERVER_ID,
                "The stored password can no longer be read. Enter the password again.");
    }

    @Test
    void otherFailureDoesNotTouchTheCredentials() {
        when(groupListRefresher.refresh(eq(SERVER_SETTINGS), any())).thenThrow(new NntpException(
                NntpFailureType.TIMEOUT, "The server news.example.com:119 did not answer in time"));

        runRefresh();

        verify(nntpServers, never()).markCredentialsInvalid(anyLong());
        verify(groupListStates).refreshFailed(SERVER_ID, "The server news.example.com:119 did not answer in time");
    }

    @Test
    void unexpectedFailureIsRecordedWithoutItsDetails() {
        when(groupListRefresher.refresh(eq(SERVER_SETTINGS), any()))
                .thenThrow(new IllegalStateException("database is locked: SELECT secret"));

        runRefresh();

        verify(groupListStates).refreshFailed(SERVER_ID,
                "The group list could not be stored. The log of Wardroom has the details.");
        assertThat(groupListRefreshService.failedAt(SERVER_ID)).isPresent();
    }

    @Test
    void successClearsTheMemoryOfAnEarlierFailure() {
        when(groupListRefresher.refresh(eq(SERVER_SETTINGS), any()))
                .thenThrow(new NntpException(NntpFailureType.TIMEOUT, "No answer"))
                .thenReturn(new GroupListRefresher.Outcome(3, 0, 0, true));

        runRefresh();
        assertThat(groupListRefreshService.failedAt(SERVER_ID)).isPresent();
        runRefresh();

        assertThat(groupListRefreshService.failedAt(SERVER_ID)).isEmpty();
        verify(groupListStates).refreshSucceeded(eq(SERVER_ID), anyLong());
    }

    @Test
    void serverThatWasRemovedEndsTheRefreshQuietly() {
        when(nntpServers.connectionSettings(SERVER_ID)).thenReturn(Optional.empty());

        runRefresh();

        verify(groupListRefresher, never()).refresh(any(), any());
        verify(groupListStates, never()).refreshFailed(anyLong(), anyString());
        assertThat(groupListRefreshService.failedAt(SERVER_ID)).isEmpty();
    }

    private void runRefresh() {
        assertThat(groupListRefreshService.start(SERVER_ID)).isTrue();
        await().until(() -> !groupListRefreshService.isRunning(SERVER_ID));
    }
}
