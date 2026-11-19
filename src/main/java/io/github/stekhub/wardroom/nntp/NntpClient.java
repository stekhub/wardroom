package io.github.stekhub.wardroom.nntp;

/**
 * One open, authenticated connection to an NNTP server (ADR 0005). Code outside this module talks NNTP only through
 * this interface.
 *
 * <p>A client is used by one thread at a time. Later implementation steps add the reading and posting commands.
 */
public interface NntpClient extends AutoCloseable {

    /**
     * @return the capabilities the server reported. They are read after connecting and again after logging in,
     *         because a server may offer more to an authenticated client
     */
    NntpCapabilities capabilities();

    /** @return whether the greeting of the server said that posting is allowed ({@code 200}, not {@code 201}) */
    boolean postingAllowedByGreeting();

    /** Sends {@code QUIT} and closes the connection. Never throws; closing twice is harmless. */
    @Override
    void close();
}
