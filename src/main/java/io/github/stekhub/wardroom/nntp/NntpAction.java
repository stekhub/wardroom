package io.github.stekhub.wardroom.nntp;

/**
 * Work that needs a connection to an NNTP server.
 *
 * @param <T> the result
 */
@FunctionalInterface
public interface NntpAction<T> {

    /**
     * @param client a connection that belongs to this call only for its duration; it must not be kept or closed
     */
    T run(NntpClient client);
}
