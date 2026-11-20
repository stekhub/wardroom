package io.github.stekhub.wardroom.server;

/**
 * A new NNTP server was saved. Published inside the transaction that stores it; the group list is fetched in
 * answer to this event (ADR 0013). Editing a server does not publish it.
 *
 * @param serverId the ID of the new server
 */
public record ServerCreated(long serverId) {
}
