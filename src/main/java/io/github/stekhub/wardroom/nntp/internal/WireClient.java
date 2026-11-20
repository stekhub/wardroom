package io.github.stekhub.wardroom.nntp.internal;

import io.github.stekhub.wardroom.nntp.NntpArticle;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.apache.commons.net.io.DotTerminatedMessageReader;
import org.apache.commons.net.nntp.NNTPClient;
import org.apache.commons.net.nntp.NNTPConnectionClosedException;

/**
 * The Commons Net client with the one thing it lacks for commands it has no method for, such as
 * {@code CAPABILITIES}: reading a multi-line answer after {@code sendCommand} (ADR 0005).
 */
final class WireClient extends NNTPClient {

    private static final byte[] LINE_ENDING = {13, 10};

    /**
     * Reads the lines that follow the status line, up to the terminating dot, with dot-stuffing undone.
     *
     * @return the lines without their line endings
     */
    List<String> readMultiLineResponse() throws IOException {
        List<String> lines = new ArrayList<>();
        // Closing this reader only reads to the end of the answer; the connection stays open.
        try (BufferedReader reader = new DotTerminatedMessageReader(_reader_)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * Reads the lines that follow the status line as bytes, up to the terminating dot, with dot-stuffing undone
     * and CRLF after every line. Reading stops before the line that would make the content longer than the limit;
     * the rest of the answer is then still unread.
     *
     * @throws NNTPConnectionClosedException when the connection ends before the terminating dot
     */
    NntpArticle readLimitedResponse(int maxBytes) throws IOException {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        String line;
        while ((line = _reader_.readLine()) != null) {
            if (line.equals(".")) {
                return new NntpArticle(content.toByteArray(), true);
            }
            String text = line.startsWith(".") ? line.substring(1) : line;
            if (content.size() + text.length() + LINE_ENDING.length > maxBytes) {
                return new NntpArticle(content.toByteArray(), false);
            }
            // The library reads every byte as one ISO-8859-1 character, so this restores the bytes.
            content.writeBytes(text.getBytes(StandardCharsets.ISO_8859_1));
            content.writeBytes(LINE_ENDING);
        }
        throw new NNTPConnectionClosedException("The connection ended in the middle of an answer");
    }

    /**
     * Hands out the lines that follow the status line one by one, up to the terminating dot, with dot-stuffing
     * undone. Nothing is collected, so the answer may be of any length.
     *
     * @throws NNTPConnectionClosedException when the connection ends before the terminating dot. A reader of the
     *                                       library would end quietly there, and a list cut short would look complete
     */
    void forEachResponseLine(Consumer<String> lineConsumer) throws IOException {
        String line;
        while ((line = _reader_.readLine()) != null) {
            if (line.equals(".")) {
                return;
            }
            lineConsumer.accept(line.startsWith(".") ? line.substring(1) : line);
        }
        throw new NNTPConnectionClosedException("The connection ended in the middle of an answer");
    }
}
