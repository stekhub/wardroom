package io.github.stekhub.wardroom.nntp.internal;

import io.github.stekhub.wardroom.nntp.NntpArticle;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.apache.commons.net.io.CRLFLineReader;
import org.apache.commons.net.io.DotTerminatedMessageReader;
import org.apache.commons.net.nntp.NNTPClient;
import org.apache.commons.net.nntp.NNTPConnectionClosedException;

/**
 * The Commons Net client with the two things it lacks (ADR 0005):
 *
 * <ul>
 * <li>reading a multi-line answer after {@code sendCommand}, for commands it has no method for, such as
 * {@code CAPABILITIES};</li>
 * <li>limits for what the server sends. The library reads a line of any length; here a line is at most
 * {@value #MAX_LINE_BYTES} bytes long, and an answer that is collected as a whole has at most
 * {@value #MAX_COLLECTED_LINES} lines.</li>
 * </ul>
 */
final class WireClient extends NNTPClient {

    /** No server sends lines this long; a line of an article that is longer is not text. */
    static final int MAX_LINE_BYTES = 1_048_576;

    /** For answers that are kept as a list of lines. The answer to {@code CAPABILITIES} has about ten. */
    static final int MAX_COLLECTED_LINES = 1_000;

    private static final byte[] LINE_ENDING = {13, 10};

    private BufferedReader limitedReader;

    /**
     * Reads a status line. The library calls this for every answer, the greeting included, and reads from the
     * reader it created while connecting. That reader is replaced here before the first byte is read, so that
     * every line of the connection passes the length limit.
     */
    @Override
    public int getReply() throws IOException {
        if (_reader_ != limitedReader) {
            // The library reads every byte as one ISO-8859-1 character, and so does this reader.
            limitedReader = new CRLFLineReader(new InputStreamReader(
                    new LineLimitInputStream(_input_, MAX_LINE_BYTES), StandardCharsets.ISO_8859_1));
            _reader_ = limitedReader;
        }
        return super.getReply();
    }

    /**
     * Reads the lines that follow the status line, up to the terminating dot, with dot-stuffing undone.
     *
     * @return the lines without their line endings
     * @throws ResponseTooLongException when the answer has more than {@value #MAX_COLLECTED_LINES} lines; the
     *                                  rest of the answer is then still unread
     */
    List<String> readMultiLineResponse() throws IOException {
        List<String> lines = new ArrayList<>();
        // Not closed: closing would read to the end of the answer, also of one that never ends.
        BufferedReader reader = new DotTerminatedMessageReader(_reader_);
        String line;
        while ((line = reader.readLine()) != null) {
            if (lines.size() == MAX_COLLECTED_LINES) {
                throw new ResponseTooLongException("an answer of more than " + MAX_COLLECTED_LINES + " lines");
            }
            lines.add(line);
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
     * undone. Nothing is collected, so the answer may have any number of lines.
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
