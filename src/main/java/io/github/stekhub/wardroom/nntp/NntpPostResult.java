package io.github.stekhub.wardroom.nntp;

/**
 * What the server answered to {@code POST} (RFC 3977, section 6.3.1). A refusal is a normal result and not a
 * failure of the server (ADR 0006).
 *
 * @param outcome       what the server did with the article
 * @param serverMessage the text of the answer after its response code, escaped and shortened; never empty for a
 *                      refusal the server explained
 */
public record NntpPostResult(Outcome outcome, String serverMessage) {

    /** What the server did with the article. */
    public enum Outcome {

        /** The server took the article ({@code 240}). */
        ACCEPTED,

        /** Posting is not permitted ({@code 440}, or {@code 502}); the article was not sent. */
        NOT_ALLOWED,

        /** The server wants a login before it accepts an article ({@code 480}); the article was not sent. */
        AUTHENTICATION_REQUIRED,

        /** The server read the article and refused it ({@code 441}). */
        REJECTED
    }
}
