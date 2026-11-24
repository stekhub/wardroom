package io.github.stekhub.wardroom.nntp.internal;

import java.io.IOException;

/**
 * The server sent a line, or an answer that is read as a whole, that is longer than Wardroom reads. The message
 * names the limit and never has anything of what the server sent.
 */
final class ResponseTooLongException extends IOException {

    ResponseTooLongException(String message) {
        super(message);
    }
}
