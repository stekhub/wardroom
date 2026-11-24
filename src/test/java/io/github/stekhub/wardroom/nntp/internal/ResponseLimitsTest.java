package io.github.stekhub.wardroom.nntp.internal;

import static io.github.stekhub.wardroom.nntp.internal.NntpTestSupport.FAST;
import static io.github.stekhub.wardroom.nntp.internal.NntpTestSupport.plain;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.stekhub.wardroom.nntp.NntpArticle;
import io.github.stekhub.wardroom.nntp.NntpClient;
import io.github.stekhub.wardroom.nntp.NntpException;
import io.github.stekhub.wardroom.nntp.NntpFailureType;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import io.github.stekhub.wardroom.nntp.mock.MockReply;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The limits for what the NNTP adapter reads from a server (spec 002): a server cannot fill the memory with a line
 * that does not end or with an endless answer to {@code CAPABILITIES}.
 */
@ExtendWith(OutputCaptureExtension.class)
class ResponseLimitsTest {

    private static final String ANOMALY = "PROTOCOL_ANOMALY kind=response.too-long";
    private static final int ENOUGH = 10 * WireClient.MAX_LINE_BYTES;

    private MockNntpServer mockServer;
    private CommonsNetNntpConnector connector;

    @BeforeEach
    void startServer() throws Exception {
        mockServer = MockNntpServer.start();
        mockServer.on("CAPABILITIES").reply(MockReply.fixture("capabilities-reader.txt"));
        connector = new CommonsNetNntpConnector(FAST, SSLContext.getDefault());
    }

    @AfterEach
    void stopServer() throws IOException {
        mockServer.close();
    }

    @Test
    void aGreetingThatDoesNotEndIsAProtocolAnomaly(CapturedOutput output) {
        mockServer.greeting().reply(MockReply.raw(withoutLineEnding("200 ")));

        NntpException failure = catchThrowableOfType(NntpException.class, () -> connector.connect(plain(mockServer)));

        assertThat(failure.type()).isEqualTo(NntpFailureType.PROTOCOL);
        assertThat(output).contains(ANOMALY + " action=connection-closed");
    }

    @Test
    void aStatusLineThatDoesNotEndIsAProtocolAnomaly(CapturedOutput output) {
        mockServer.on("ARTICLE <a@example.org>").reply(MockReply.raw(withoutLineEnding("220 0 ")));

        try (NntpClient client = connector.connect(plain(mockServer))) {
            assertFailure(() -> client.article("<a@example.org>", ENOUGH));
        }
        assertThat(output).contains(ANOMALY + " command=\"ARTICLE <a@example.org>\" action=connection-closed");
    }

    @Test
    void aLineOfAnArticleThatDoesNotEndIsAProtocolAnomalyAndItsTextIsNotLogged(CapturedOutput output) {
        mockServer.on("ARTICLE <a@example.org>").reply(MockReply.raw(
                withoutLineEnding("220 0 <a@example.org>\r\nSubject: Hello\r\n\r\nsecret-text ")));

        try (NntpClient client = connector.connect(plain(mockServer))) {
            assertFailure(() -> client.article("<a@example.org>", ENOUGH));
        }
        assertThat(output).contains(ANOMALY).doesNotContain("secret-text").doesNotContain("xxxx");
    }

    @Test
    void aLineOfAListThatDoesNotEndIsAProtocolAnomaly(CapturedOutput output) {
        mockServer.on("LIST ACTIVE").reply(MockReply.raw(
                withoutLineEnding("215 Newsgroups follow\r\nexample.first 20 1 y\r\nexample.")));
        List<String> groupNames = new ArrayList<>();

        try (NntpClient client = connector.connect(plain(mockServer))) {
            assertFailure(() -> client.listActive(group -> groupNames.add(group.name())));
        }
        assertThat(groupNames).containsExactly("example.first");
        assertThat(output).contains(ANOMALY + " command=\"LIST ACTIVE\" action=connection-closed");
    }

    @Test
    void anAnswerToCapabilitiesWithTooManyLinesIsAProtocolAnomaly(CapturedOutput output) {
        StringBuilder answer = new StringBuilder("101 Capability list:\r\n");
        for (int line = 0; line <= WireClient.MAX_COLLECTED_LINES; line++) {
            answer.append("X-EXTENSION-").append(line).append("\r\n");
        }
        // No terminating dot: the answer could go on for ever.
        mockServer.on("CAPABILITIES").reply(MockReply.raw(answer.toString().getBytes(StandardCharsets.ISO_8859_1)));

        NntpException failure = catchThrowableOfType(NntpException.class, () -> connector.connect(plain(mockServer)));

        assertThat(failure.type()).isEqualTo(NntpFailureType.PROTOCOL);
        assertThat(output).contains(ANOMALY + " command=CAPABILITIES action=connection-closed");
    }

    @Test
    void aLineAsLongAsTheLimitIsRead() {
        String longLine = "x".repeat(WireClient.MAX_LINE_BYTES);
        mockServer.on("ARTICLE <a@example.org>").reply("220 0 <a@example.org>", "Subject: Hello", "", longLine, ".");

        try (NntpClient client = connector.connect(plain(mockServer))) {
            NntpArticle article = client.article("<a@example.org>", ENOUGH).orElseThrow();

            assertThat(article.complete()).isTrue();
            assertThat(article.content()).hasSize("Subject: Hello\r\n\r\n".length() + longLine.length() + 2);
        }
    }

    /** The reader of the library ends a line only at CRLF, so a single CR or LF must not start the count again. */
    @Test
    void onlyCrLfEndsALine() throws IOException {
        byte[] sent = "ab\ncd\ref\r\nab\r\n".getBytes(StandardCharsets.ISO_8859_1);

        assertThat(new LineLimitInputStream(new ByteArrayInputStream(sent), 8).readAllBytes()).isEqualTo(sent);
        assertThatThrownBy(() -> new LineLimitInputStream(new ByteArrayInputStream(sent), 7).readAllBytes())
                .isInstanceOf(ResponseTooLongException.class)
                .hasMessage("a line of more than 7 bytes");
    }

    private static void assertFailure(Runnable command) {
        NntpException failure = catchThrowableOfType(NntpException.class, command::run);
        assertThat(failure).isNotNull();
        assertThat(failure.type()).isEqualTo(NntpFailureType.PROTOCOL);
    }

    /** What a server sends that starts a line and never ends it: more bytes than a line may have, without CRLF. */
    private static byte[] withoutLineEnding(String start) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(start.getBytes(StandardCharsets.ISO_8859_1));
        bytes.writeBytes("x".repeat(WireClient.MAX_LINE_BYTES + 100).getBytes(StandardCharsets.ISO_8859_1));
        return bytes.toByteArray();
    }
}
