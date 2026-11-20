package io.github.stekhub.wardroom.nntp;

/**
 * What a server answers when a group is selected with {@code GROUP} (RFC 3977, section 6.1.1).
 *
 * @param articleCount  the number of articles the server estimates for the group
 * @param lowWatermark  the lowest article number of the group
 * @param highWatermark the highest article number of the group; lower than the low watermark when the group is
 *                      empty
 */
public record NntpGroupStatus(long articleCount, long lowWatermark, long highWatermark) {
}
