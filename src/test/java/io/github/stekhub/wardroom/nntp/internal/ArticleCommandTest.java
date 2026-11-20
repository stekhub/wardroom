package io.github.stekhub.wardroom.nntp.internal;

import static io.github.stekhub.wardroom.nntp.internal.NntpTestSupport.FAST;
import static io.github.stekhub.wardroom.nntp.internal.NntpTestSupport.plain;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.stekhub.wardroom.nntp.NntpArticle;
import io.github.stekhub.wardroom.nntp.NntpClient;
import io.github.stekhub.wardroom.nntp.NntpException;
import io.github.stekhub.wardroom.nntp.NntpFailureType;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import io.github.stekhub.wardroom.nntp.mock.MockReply;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code ARTICLE} of the NNTP adapter (spec 007). */
class ArticleCommandTest {

    private static final int ENOUGH = 10_000;

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
    void anArticleIsReturnedAsItsBytesWithDotStuffingUndone() {
        mockServer.on("ARTICLE <a@example.org>").reply("220 0 <a@example.org>", "Subject: Hello",
                "From: Ada <ada@example.org>", "", "First line", "..a line that starts with a dot", "", "Last line",
                ".");

        try (NntpClient client = connector.connect(plain(mockServer))) {
            Optional<NntpArticle> article = client.article("<a@example.org>", ENOUGH);

            assertThat(article).isPresent();
            assertThat(article.get().complete()).isTrue();
            assertThat(new String(article.get().content(), StandardCharsets.ISO_8859_1)).isEqualTo(
                    "Subject: Hello\r\nFrom: Ada <ada@example.org>\r\n\r\nFirst line\r\n"
                            + ".a line that starts with a dot\r\n\r\nLast line\r\n");
        }
        assertThat(mockServer.commands()).contains("ARTICLE <a@example.org>");
    }

    @Test
    void theBytesOfAnArticleAreNotChanged() {
        byte[] latin = "220 0 <a@example.org>\r\nSubject: x\r\n\r\ncafé ü\r\n.\r\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        mockServer.on("ARTICLE <a@example.org>").reply(MockReply.raw(latin));

        try (NntpClient client = connector.connect(plain(mockServer))) {
            byte[] content = client.article("<a@example.org>", ENOUGH).orElseThrow().content();

            assertThat(content).isEqualTo("Subject: x\r\n\r\ncafé ü\r\n".getBytes(StandardCharsets.ISO_8859_1));
        }
    }

    @Test
    void anArticleTheServerDoesNotHaveIsNotAFailure() {
        mockServer.on("ARTICLE <gone@example.org>").reply("430 No such article");
        mockServer.on("ARTICLE <a@example.org>").reply("220 0 <a@example.org>", "Subject: Hello", "", "Text", ".");

        try (NntpClient client = connector.connect(plain(mockServer))) {
            assertThat(client.article("<gone@example.org>", ENOUGH)).isEmpty();
            // The connection is still usable.
            assertThat(client.article("<a@example.org>", ENOUGH)).isPresent();
        }
    }

    @Test
    void anArticleLongerThanTheLimitIsCutOffAfterTheLastLineThatFits() {
        mockServer.on("ARTICLE <a@example.org>").reply("220 0 <a@example.org>", "Subject: Hello", "", "1234567890",
                "1234567890", "1234567890", ".");

        try (NntpClient client = connector.connect(plain(mockServer))) {
            // The header, the empty line and one line of text are 30 bytes; the second line of text ends at 42.
            NntpArticle article = client.article("<a@example.org>", 41).orElseThrow();

            assertThat(article.complete()).isFalse();
            assertThat(new String(article.content(), StandardCharsets.ISO_8859_1))
                    .isEqualTo("Subject: Hello\r\n\r\n1234567890\r\n");
        }
    }

    @Test
    void aMessageIdThatCannotBeSentIsNotAskedFor() {
        try (NntpClient client = connector.connect(plain(mockServer))) {
            assertThat(client.article("<a b@example.org>", ENOUGH)).isEmpty();
            assertThat(client.article("<a@example.org>\r\nQUIT", ENOUGH)).isEmpty();
            assertThat(client.article("", ENOUGH)).isEmpty();
        }

        assertThat(mockServer.commands()).noneMatch(command -> command.startsWith("ARTICLE"));
    }

    @Test
    void anArticleThatEndsBeforeItsTerminatingDotIsAFailure() {
        mockServer.on("ARTICLE <a@example.org>").reply(MockReply.cutOff("220 0 <a@example.org>", "Subject: Hello"));

        try (NntpClient client = connector.connect(plain(mockServer))) {
            NntpException failure = catchThrowableOfType(NntpException.class,
                    () -> client.article("<a@example.org>", ENOUGH));

            assertThat(failure.type()).isEqualTo(NntpFailureType.CONNECTION_LOST);
        }
    }

    @Test
    void aServerThatRefusesTheCommandIsAFailure() {
        mockServer.on("ARTICLE <a@example.org>").reply("502 Not today");

        try (NntpClient client = connector.connect(plain(mockServer))) {
            NntpException failure = catchThrowableOfType(NntpException.class,
                    () -> client.article("<a@example.org>", ENOUGH));

            assertThat(failure.type()).isEqualTo(NntpFailureType.SERVER_REFUSED);
        }
    }
}
