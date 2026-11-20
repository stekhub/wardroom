package io.github.stekhub.wardroom.nntp;

import java.util.Optional;
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

    /**
     * Selects a group with {@code GROUP} (RFC 3977, section 6.1.1). The selection lasts until another group is
     * selected on this connection.
     *
     * @return the article count and the watermarks, or empty when the server has no group of this name
     *         ({@code 411}), which is not a failure (ADR 0006)
     * @throws NntpException when the server does not answer as the protocol says or the connection fails
     */
    Optional<NntpGroupStatus> selectGroup(String groupName);

    /**
     * Reads the overviews of a range of article numbers in the selected group, with {@code OVER}, or with
     * {@code XOVER} when the capabilities do not list {@code OVER}. The order of the fields is asked once per
     * server with {@code LIST OVERVIEW.FMT}; a server that does not know the command gets the order of RFC 3977.
     *
     * <p>The overviews are handed out one by one, in the order of the answer. A line that cannot be read, and an
     * overview without a Message-ID, is logged as a protocol anomaly and left out. A range without articles hands
     * out nothing.
     *
     * @param firstNumber the first article number of the range
     * @param lastNumber  the last article number of the range, which belongs to it
     * @throws NntpException when no group is selected, the server does not send the overviews or the connection
     *                       fails before their end
     */
    void overviews(long firstNumber, long lastNumber, Consumer<NntpOverview> overviewConsumer);

    /**
     * Fetches an article by its Message-ID with {@code ARTICLE} (RFC 3977, section 6.2.1). No group has to be
     * selected.
     *
     * <p>At most {@code maxBytes} are read. A longer article is cut off after the last line that fits; the
     * connection is not used again then, because the rest of the article is still on its way.
     *
     * @param messageId the Message-ID with its angle brackets
     * @param maxBytes  how many bytes of the article are read at most
     * @return the article, or empty when the server has no article with this Message-ID ({@code 430}), which is
     *         not a failure (ADR 0006). Also empty for a Message-ID that cannot be sent in a command
     * @throws NntpException when the server does not send the article or the connection fails before its end
     */
    Optional<NntpArticle> article(String messageId, int maxBytes);

    /** Sends {@code QUIT} and closes the connection. Never throws; closing twice is harmless. */
    @Override
    void close();
}
