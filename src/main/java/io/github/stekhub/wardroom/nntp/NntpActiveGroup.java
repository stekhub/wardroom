package io.github.stekhub.wardroom.nntp;

/**
 * One entry of the group list of a server ({@code LIST ACTIVE}, RFC 3977, section 7.6.3).
 *
 * @param name          the group name, as text; it comes from the server and is untrusted
 * @param highWatermark the highest article number the server reports for the group
 * @param lowWatermark  the lowest article number the server reports for the group
 * @param status        the status as sent: {@code y}, {@code n}, {@code m}, or a value of the server's own
 */
public record NntpActiveGroup(String name, long highWatermark, long lowWatermark, String status) {
}
