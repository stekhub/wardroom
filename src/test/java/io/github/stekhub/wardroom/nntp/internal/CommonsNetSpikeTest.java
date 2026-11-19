package io.github.stekhub.wardroom.nntp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import io.github.stekhub.wardroom.nntp.mock.MockReply;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The library spike of ADR 0005 and open question 1, kept as tests: what Apache Commons Net does for the commands
 * Wardroom needs, and how the gaps are closed. Later steps build the adapter methods on what is shown here.
 */
class CommonsNetSpikeTest {

    private MockNntpServer server;
    private WireClient client;

    @BeforeEach
    void connect() throws IOException {
        server = MockNntpServer.start();
        client = new WireClient();
        client.setDefaultTimeout(2_000);
        client.connect("localhost", server.port());
    }

    @AfterEach
    void disconnect() throws IOException {
        client.disconnect();
        server.close();
    }

    @Test
    void capabilitiesHasNoMethodButWorksAsAGenericCommand() throws IOException {
        server.on("CAPABILITIES").reply("101 Capability list:", "VERSION 2", "READER", ".");

        int code = client.sendCommand("CAPABILITIES");

        assertThat(code).isEqualTo(101);
        assertThat(client.readMultiLineResponse()).containsExactly("VERSION 2", "READER");
    }

    @Test
    void listActiveIsStreamedWithOneLineOfLookAhead() throws IOException {
        CountDownLatch firstGroupWasHandedOut = new CountDownLatch(1);
        server.on("LIST").reply(MockReply.inTwoParts(
                new String[] {"215 Newsgroups follow", "example.first 20 1 y", "example.second 5 1 n"},
                firstGroupWasHandedOut, new String[] {"example.third 9 1 m", "."}));

        Iterator<String> groups = client.iterateNewsgroupListing().iterator();

        // The first group is handed out while the server has not sent the end of the list yet. The iterator
        // always reads one line ahead, so the second line must have arrived by then.
        assertThat(groups.next()).isEqualTo("example.first 20 1 y");
        firstGroupWasHandedOut.countDown();
        assertThat(groups.next()).isEqualTo("example.second 5 1 n");
        assertThat(groups.next()).isEqualTo("example.third 9 1 m");
        assertThat(groups.hasNext()).isFalse();
        assertThat(server.commands()).containsExactly("LIST");
    }

    @Test
    void listNewsgroupsHasNoMethodButWorksAsAGenericCommand() throws IOException {
        server.on("LIST NEWSGROUPS").reply("215 Descriptions follow", "example.first\tThe first group", ".");

        int code = client.sendCommand("LIST", "NEWSGROUPS");

        assertThat(code).isEqualTo(215);
        assertThat(client.readMultiLineResponse()).containsExactly("example.first\tThe first group");
    }

    @Test
    void listOverviewFmtHasAMethod() throws IOException {
        server.on("LIST OVERVIEW.FMT").reply("215 Order of fields", "Subject:", "From:", "Date:", "Message-ID:",
                "References:", ":bytes", ":lines", "Xref:full", ".");

        assertThat(client.listOverviewFmt()).containsExactly("Subject:", "From:", "Date:", "Message-ID:",
                "References:", ":bytes", ":lines", "Xref:full");
    }

    @Test
    void overHasNoMethodButWorksAsAGenericCommand() throws IOException {
        server.on("OVER").reply("224 Overview follows", "1\tHello\tA <a@example.com>\t1 Jan 2026 00:00:00 +0000"
                + "\t<1@example.com>\t\t100\t5", ".");

        int code = client.sendCommand("OVER", "1-1");

        assertThat(code).isEqualTo(224);
        assertThat(client.readMultiLineResponse()).singleElement().asString().startsWith("1\tHello\t");
        assertThat(server.commands()).containsExactly("OVER 1-1");
    }

    @Test
    void theOverviewMethodOfTheLibrarySendsTheOlderXover() throws IOException {
        server.on("XOVER").reply("224 Overview follows", ".");

        client.retrieveArticleInfo(1, 2).close();

        assertThat(server.commands()).containsExactly("XOVER 1-2");
    }

    @Test
    void postSendsTheArticleWithDotStuffing() throws IOException {
        server.on("POST").reply(MockReply.article("340 Send the article", "240 Article received"));

        try (Writer article = client.postArticle()) {
            article.write("Subject: Test\r\n\r\nFirst line\r\n.A line that starts with a dot\r\n");
        }

        assertThat(client.completePendingCommand()).isTrue();
        assertThat(server.postedArticles()).containsExactly(
                "Subject: Test\r\n\r\nFirst line\r\n..A line that starts with a dot\r\n");
    }

    @Test
    void bytesOfAnAnswerSurviveBecauseTheLibraryReadsLatin1() throws IOException {
        byte[] utf8 = "215 Descriptions follow\r\nexample.first\tGrüße\r\n.\r\n".getBytes(StandardCharsets.UTF_8);
        server.on("LIST NEWSGROUPS").reply(MockReply.raw(utf8));

        client.sendCommand("LIST", "NEWSGROUPS");
        List<String> lines = client.readMultiLineResponse();

        // Every byte became one character, so the original bytes can be restored and decoded as needed.
        String restored = new String(lines.getFirst().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        assertThat(restored).isEqualTo("example.first\tGrüße");
    }
}
