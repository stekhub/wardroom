package io.github.stekhub.wardroom.nntp;

/**
 * An article as the server sent it, from {@code ARTICLE} (RFC 3977, section 6.2.1). Everything in it is untrusted.
 *
 * @param content  the bytes of the article: the header block, an empty line and the body, with CRLF line endings
 *                 and dot-stuffing undone. Never changed after the article was read
 * @param complete {@code false} when the article is longer than the limit; the content then ends with the last
 *                 line that fits
 */
public record NntpArticle(byte[] content, boolean complete) {
}
