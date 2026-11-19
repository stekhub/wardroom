package io.github.stekhub.wardroom.nntp.mock;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

/**
 * What the {@link MockNntpServer} does when a command arrives: send bytes, or misbehave.
 *
 * @param kind  what happens
 * @param data  the bytes sent first
 * @param rest  the bytes sent last, for kinds that send in two parts
 * @param gate  for {@link Kind#TWO_PARTS}: the second part is sent when this latch is released
 * @param delay the wait before anything happens
 */
public record MockReply(Kind kind, byte[] data, byte[] rest, CountDownLatch gate, Duration delay) {

    enum Kind {
        SEND, DROP, SILENCE, ARTICLE, TWO_PARTS
    }

    /** Sends the lines, each ended with CRLF. A multi-line answer needs its terminating {@code "."} line. */
    public static MockReply lines(String... lines) {
        return raw(toBytes(lines));
    }

    /** Sends exactly these bytes, for answers that break the protocol. */
    public static MockReply raw(byte[] bytes) {
        return new MockReply(Kind.SEND, bytes, null, null, Duration.ZERO);
    }

    /** Sends a file from {@code src/test/resources/nntp} byte for byte. */
    public static MockReply fixture(String name) {
        try (InputStream in = MockReply.class.getResourceAsStream("/nntp/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No NNTP fixture named " + name);
            }
            return raw(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Closes the connection without an answer. */
    public static MockReply drop() {
        return new MockReply(Kind.DROP, null, null, null, Duration.ZERO);
    }

    /** Never answers and keeps the connection open, so the client runs into its read timeout. */
    public static MockReply silence() {
        return new MockReply(Kind.SILENCE, null, null, null, Duration.ZERO);
    }

    /**
     * The answer to {@code POST}: sends {@code proceed}, reads the article up to its terminating dot, then sends
     * {@code done}. The article is available from {@link MockNntpServer#postedArticles()}.
     */
    public static MockReply article(String proceed, String done) {
        return new MockReply(Kind.ARTICLE, toBytes(proceed), toBytes(done), null, Duration.ZERO);
    }

    /**
     * Sends the first lines at once and the remaining lines when the gate is released. Shows whether a client
     * hands out the beginning of a long answer before the end has arrived.
     */
    public static MockReply inTwoParts(String[] first, CountDownLatch gate, String[] remaining) {
        return new MockReply(Kind.TWO_PARTS, toBytes(first), toBytes(remaining), gate, Duration.ZERO);
    }

    /** @return this reply, started after a wait */
    public MockReply after(Duration wait) {
        return new MockReply(kind, data, rest, gate, wait);
    }

    private static byte[] toBytes(String... lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append("\r\n");
        }
        return text.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}
