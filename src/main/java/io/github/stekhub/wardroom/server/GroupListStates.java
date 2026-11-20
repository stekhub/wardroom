package io.github.stekhub.wardroom.server;

import java.util.List;
import java.util.Optional;

/**
 * What the server row records about the group list of a server: when it was last refreshed and why the last
 * refresh failed (data model: {@code nntp_server}). The refresh itself belongs to the groups module (spec 003).
 */
public interface GroupListStates {

    /** @return the state of the server, or empty when no server has this ID */
    Optional<GroupListState> find(long serverId);

    /** @return the states of all servers */
    List<GroupListState> findAll();

    /** Records a successful refresh: the time is stored and the error of an earlier refresh is cleared. */
    void refreshSucceeded(long serverId, long refreshedAt);

    /**
     * Records a failed refresh. The time of the last successful refresh is kept.
     *
     * @param error the cause in words for the owner; never contains credentials
     */
    void refreshFailed(long serverId, String error);

    /**
     * @param serverId           the server
     * @param credentialsInvalid whether the login of the server is known not to work; a refresh would fail
     * @param refreshedAt        the time of the last successful refresh in milliseconds, or {@code null}
     * @param refreshError       why the last refresh failed, or {@code null} when it succeeded or none has run
     */
    record GroupListState(long serverId, boolean credentialsInvalid, Long refreshedAt, String refreshError) {
    }
}
