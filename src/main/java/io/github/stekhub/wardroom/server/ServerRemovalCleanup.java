package io.github.stekhub.wardroom.server;

/**
 * Deletes the data that belongs to a server when the server is removed (spec 002).
 *
 * <p>A module that stores such data implements this interface as a Spring bean. Every implementation runs in the
 * database transaction that removes the server, before the server row is deleted.
 */
public interface ServerRemovalCleanup {

    /** Called inside the transaction that removes the server. Must only change the database. */
    void removeServerData(long serverId);
}
