package io.github.stekhub.wardroom.nntp.internal;

import static io.github.stekhub.wardroom.nntp.internal.NntpTestSupport.FAST;
import static io.github.stekhub.wardroom.nntp.internal.NntpTestSupport.plain;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.stekhub.wardroom.nntp.NntpClient;
import io.github.stekhub.wardroom.nntp.NntpException;
import io.github.stekhub.wardroom.nntp.NntpFailureType;
import io.github.stekhub.wardroom.nntp.NntpPostResult;
import io.github.stekhub.wardroom.nntp.NntpPostResult.Outcome;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import io.github.stekhub.wardroom.nntp.mock.MockReply;
import java.io.IOException;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code POST} of the NNTP adapter (spec 009). */
class PostCommandTest {

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
    void anArticleIsSentWithCrlfDotStuffingAndItsBytesAsUtf8() {
        mockServer.on("POST").reply(MockReply.article("340 Send the article", "240 Article received OK"));

        NntpPostResult result;
        try (NntpClient client = connector.connect(plain(mockServer))) {
            result = client.post("Subject: Test\n\nGrüße 🙂\n.\n.a line that starts with a dot\n..two dots\n");
        }

        assertThat(result.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(result.serverMessage()).isEqualTo("Article received OK");
        assertThat(mockServer.postedArticles()).hasSize(1);
        // The mock keeps the article as it was sent, every byte as one character.
        assertThat(WireText.decode(mockServer.postedArticles().getFirst())).isEqualTo(
                "Subject: Test\r\n\r\nGrüße 🙂\r\n..\r\n..a line that starts with a dot\r\n...two dots\r\n");
        assertThat(mockServer.commands()).containsExactly("CAPABILITIES", "POST", "QUIT");
    }

    @Test
    void aRefusalBeforeTheArticleIsAResultAndNothingIsSent() {
        mockServer.on("POST").once(MockReply.lines("440 Posting not permitted"))
                .once(MockReply.lines("480 Authentication required"))
                .reply("502 Command unavailable");

        try (NntpClient client = connector.connect(plain(mockServer))) {
            assertThat(client.post("Subject: Test\r\n\r\nText\r\n"))
                    .isEqualTo(new NntpPostResult(Outcome.NOT_ALLOWED, "Posting not permitted"));
            assertThat(client.post("Subject: Test\r\n\r\nText\r\n"))
                    .isEqualTo(new NntpPostResult(Outcome.AUTHENTICATION_REQUIRED, "Authentication required"));
            assertThat(client.post("Subject: Test\r\n\r\nText\r\n").outcome()).isEqualTo(Outcome.NOT_ALLOWED);
        }

        assertThat(mockServer.postedArticles()).isEmpty();
    }

    @Test
    void anArticleTheServerRefusesIsAResultWithTheMessageOfTheServer() {
        mockServer.on("POST").reply(MockReply.article("340 Send the article", "441 Too much quoted text"));

        try (NntpClient client = connector.connect(plain(mockServer))) {
            assertThat(client.post("Subject: Test\r\n\r\nText\r\n"))
                    .isEqualTo(new NntpPostResult(Outcome.REJECTED, "Too much quoted text"));
        }
    }

    @Test
    void anAnswerTheProtocolDoesNotAllowAndALostConnectionAreFailures() {
        mockServer.on("POST").once(MockReply.lines("211 Unexpected")).reply(MockReply.drop());

        try (NntpClient client = connector.connect(plain(mockServer))) {
            NntpException failure = catchThrowableOfType(NntpException.class,
                    () -> client.post("Subject: Test\r\n\r\nText\r\n"));
            assertThat(failure.type()).isEqualTo(NntpFailureType.PROTOCOL);
        }
        try (NntpClient client = connector.connect(plain(mockServer))) {
            NntpException failure = catchThrowableOfType(NntpException.class,
                    () -> client.post("Subject: Test\r\n\r\nText\r\n"));
            assertThat(failure.type()).isEqualTo(NntpFailureType.CONNECTION_LOST);
        }
    }
}
