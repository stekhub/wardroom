package io.github.stekhub.wardroom.nntp;

import java.util.Objects;

/**
 * Talking to an NNTP server failed.
 *
 * <p>The message names the cause in words that can be shown to the owner. It never contains credentials, and text
 * that came from the server is escaped.
 */
public class NntpException extends RuntimeException {

    private final NntpFailureType type;

    public NntpException(NntpFailureType type, String message) {
        this(type, message, null);
    }

    public NntpException(NntpFailureType type, String message, Throwable cause) {
        super(message, cause);
        this.type = Objects.requireNonNull(type, "type");
    }

    public NntpFailureType type() {
        return type;
    }
}
