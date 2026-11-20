package io.github.stekhub.wardroom.nntp;

/**
 * The description of one group ({@code LIST NEWSGROUPS}, RFC 3977, section 7.6.6).
 *
 * @param name        the group name
 * @param description the description, without surrounding whitespace; may be empty. It comes from the server and is
 *                    untrusted
 */
public record NntpGroupDescription(String name, String description) {
}
