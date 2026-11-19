package io.github.stekhub.wardroom.nntp.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.net.io.DotTerminatedMessageReader;
import org.apache.commons.net.nntp.NNTPClient;

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
}
