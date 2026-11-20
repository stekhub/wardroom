package io.github.stekhub.wardroom.nntp;

/**
 * The overview of one article, from {@code OVER} or {@code XOVER} (RFC 3977, section 8.3). Everything in it comes
 * from the server and is untrusted text.
 *
 * @param number     the article number in the selected group
 * @param subject    the subject with encoded words decoded (RFC 2047)
 * @param from       the author with encoded words decoded (RFC 2047)
 * @param date       the date in UTC milliseconds, or {@code null} when it cannot be read
 * @param messageId  the Message-ID as received, with its angle brackets; never empty
 * @param references the {@code References} value as received, or {@code null} when the article has none
 * @param bytes      the size of the article in bytes, or {@code null} when the server gave no number
 * @param lines      the number of lines of the article, or {@code null} when the server gave no number
 */
public record NntpOverview(long number, String subject, String from, Long date, String messageId, String references,
                           Long bytes, Long lines) {
}
