package io.github.stekhub.wardroom.nntp.internal;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Passes on what the server sends and fails when a line gets longer than the limit.
 *
 * <p>The readers of the NNTP library collect a line until its CRLF, however long it is. A server that never sends
 * one could fill the memory that way. This stream sits below those readers, so the limit holds for every line:
 * greeting, status lines, lists, overviews and articles.
 */
final class LineLimitInputStream extends FilterInputStream {

    private final int maxLineBytes;
    private int lineBytes;
    private boolean afterCarriageReturn;

    /** @param maxLineBytes how long a line may be, without its CRLF */
    LineLimitInputStream(InputStream serverInput, int maxLineBytes) {
        super(serverInput);
        this.maxLineBytes = maxLineBytes;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value >= 0) {
            count((byte) value);
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int count = super.read(buffer, offset, length);
        for (int index = offset; index < offset + count; index++) {
            count(buffer[index]);
        }
        return count;
    }

    /** Only CRLF ends a line, as for the reader of the library; a single CR or LF is part of the line. */
    private void count(byte value) throws ResponseTooLongException {
        if (value == '\n' && afterCarriageReturn) {
            lineBytes = 0;
            afterCarriageReturn = false;
            return;
        }
        afterCarriageReturn = value == '\r';
        lineBytes++;
        // The CR that may end the line is counted before its LF is seen, so one byte more is let through.
        if (lineBytes > maxLineBytes + 1) {
            throw new ResponseTooLongException("a line of more than " + maxLineBytes + " bytes");
        }
    }
}
