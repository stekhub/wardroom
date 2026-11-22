package io.github.stekhub.wardroom.server;

import io.github.stekhub.wardroom.nntp.NntpException;
import io.github.stekhub.wardroom.nntp.NntpServerSettings;
import java.util.Optional;

/**
 * What other modules need from the configured NNTP server to talk to it (spec 002).
 */
public interface NntpServers {

    /**
     * Returns the settings of a server in the form the NNTP adapter takes. The stored password is decrypted for
     * this call and not kept anywhere; the caller hands the settings to the adapter and does not keep them either.
     *
     * @return the settings, or empty when no server has this ID
     * @throws NntpException with the type {@code AUTH_FAILED} when the stored password cannot be decrypted. The
     *                       credentials of the server are then marked as invalid
     */
    Optional<NntpServerSettings> connectionSettings(long serverId);

    /**
     * Returns the settings of the separate server that takes the posts of a server (spec 002, "Posting server").
     * They carry the ID of the server they belong to. The caller opens a connection of its own with them; the
     * pooled connections of the server lead to the host that is read from.
     *
     * @return the settings, or empty when posts go to the server itself or no server has this ID
     * @throws NntpException with the type {@code AUTH_FAILED} when the stored password cannot be decrypted
     */
    Optional<NntpServerSettings> postingServerSettings(long serverId);

    /**
     * @return whether the login of the server is known not to work. Work that would only be another rejected login
     *         is left out then. {@code false} when no server has this ID
     */
    boolean credentialsInvalid(long serverId);

    /**
     * Records that the server rejected the stored credentials ({@code 481}, ADR 0006). The owner is asked to enter
     * them again; saving new credentials clears the mark.
     */
    void markCredentialsInvalid(long serverId);
}
