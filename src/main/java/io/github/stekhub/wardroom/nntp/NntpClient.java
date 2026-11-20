package io.github.stekhub.wardroom.nntp;

import java.util.function.Consumer;

/**
 * One open, authenticated connection to an NNTP server (ADR 0005). Code outside this module talks NNTP only through
 * this interface.
 *
 * <p>A client is used by one thread at a time. Later implementation steps add further commands.
 */
public interface NntpClient extends AutoCloseable {

    /**
     * @return the capabilities the server reported. They are read after connecting and again after logging in,
     *         because a server may offer more to an authenticated client
     */
    NntpCapabilities capabilities();

    /** @return whether the greeting of the server said that posting is allowed ({@code 200}, not {@code 201}) */
    boolean postingAllowedByGreeting();

    /**
     * Reads the group list of the server with {@code LIST ACTIVE} (RFC 3977, section 7.6.3). The groups are handed
     * out one by one while the list is still arriving, so a long list does not have to fit into memory.
     *
     * <p>A line that is not a valid entry is logged as a protocol anomaly and left out. The method returns when
     * the complete list was received.
     *
     * @throws NntpException when the server does not send the list or the connection fails before its end
     */
    void listActive(Consumer<NntpActiveGroup> groupConsumer);

    /**
     * Reads the group descriptions with {@code LIST NEWSGROUPS} (RFC 3977, section 7.6.6), one by one like
     * {@link #listActive(Consumer)}.
     *
     * @return {@code false} when the server rejected the command, which is not a failure: a server does not have to
     *         offer descriptions
     * @throws NntpException when the connection fails before the end of the list
     */
    boolean listNewsgroups(Consumer<NntpGroupDescription> descriptionConsumer);

    /** Sends {@code QUIT} and closes the connection. Never throws; closing twice is harmless. */
    @Override
    void close();
}
