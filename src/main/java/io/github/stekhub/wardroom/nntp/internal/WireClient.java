package io.github.stekhub.wardroom.nntp.internal;

import java.io.BufferedReader;
import java.io.IOException;
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
