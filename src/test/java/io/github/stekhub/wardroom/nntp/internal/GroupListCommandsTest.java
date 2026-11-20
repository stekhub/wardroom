package io.github.stekhub.wardroom.nntp.internal;

import static io.github.stekhub.wardroom.nntp.internal.NntpTestSupport.FAST;
import static io.github.stekhub.wardroom.nntp.internal.NntpTestSupport.plain;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.stekhub.wardroom.nntp.NntpActiveGroup;
import io.github.stekhub.wardroom.nntp.NntpClient;
import io.github.stekhub.wardroom.nntp.NntpException;
import io.github.stekhub.wardroom.nntp.NntpFailureType;
import io.github.stekhub.wardroom.nntp.NntpGroupDescription;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import io.github.stekhub.wardroom.nntp.mock.MockReply;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** {@code LIST ACTIVE}, {@code LIST NEWSGROUPS} and {@code MODE READER} of the NNTP adapter (spec 003). */
@ExtendWith(OutputCaptureExtension.class)
class GroupListCommandsTest {

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
    void listActiveHandsOutEveryGroupWithWatermarksAndStatus() {
        mockServer.on("LIST ACTIVE").reply("215 Newsgroups follow", "example.first 20 1 y", "example.second 5 6 n",
                "example.third 0000000009 0000000003 m", "example.alias 0 0 =example.first", ".");

        List<NntpActiveGroup> activeGroups = new ArrayList<>();
        try (NntpClient client = connector.connect(plain(mockServer))) {
            client.listActive(activeGroups::add);
        }

        assertThat(activeGroups).containsExactly(
                new NntpActiveGroup("example.first", 20, 1, "y"),
                new NntpActiveGroup("example.second", 5, 6, "n"),
                new NntpActiveGroup("example.third", 9, 3, "m"),
                new NntpActiveGroup("example.alias", 0, 0, "=example.first"));
        assertThat(mockServer.commands()).containsExactly("CAPABILITIES", "LIST ACTIVE", "QUIT");
    }

    @Test
    void listActiveHandsOutGroupsWhileTheListIsStillArriving() {
        CountDownLatch firstGroupWasHandedOut = new CountDownLatch(1);
        mockServer.on("LIST ACTIVE").reply(MockReply.inTwoParts(
                new String[] {"215 Newsgroups follow", "example.first 20 1 y"},
                firstGroupWasHandedOut, new String[] {"example.second 5 1 n", "."}));

        List<String> names = new ArrayList<>();
        try (NntpClient client = connector.connect(plain(mockServer))) {
            // The server sends the rest only after the first group reached the receiver.
            client.listActive(activeGroup -> {
                names.add(activeGroup.name());
                firstGroupWasHandedOut.countDown();
            });
        }

        assertThat(names).containsExactly("example.first", "example.second");
    }

    @Test
    void malformedListActiveLineIsSkippedAndLogged(CapturedOutput output) {
        mockServer.on("LIST ACTIVE").reply("215 Newsgroups follow", "example.first 20 1 y", "example.broken twenty 1 y",
                "example.short 20 1", "example.last 7 2 y", ".");

        List<String> names = new ArrayList<>();
        try (NntpClient client = connector.connect(plain(mockServer))) {
            client.listActive(activeGroup -> names.add(activeGroup.name()));
        }

        assertThat(names).containsExactly("example.first", "example.last");
        assertThat(output).contains("PROTOCOL_ANOMALY kind=list.malformed-active-line command=\"LIST ACTIVE\""
                + " action=skipped raw=\"example.broken twenty 1 y\"");
        assertThat(output).contains("raw=\"example.short 20 1\"");
    }

    @Test
    void listThatEndsWithoutItsLastLineIsAFailureAndNotAShortList() {
        // The connection closes after two groups, without the terminating dot.
        mockServer.on("LIST ACTIVE").reply(MockReply.cutOff("215 Newsgroups follow", "example.first 20 1 y",
                "example.second 5 1 n"));

        List<String> names = new ArrayList<>();
        NntpException failure;
        try (NntpClient client = connector.connect(plain(mockServer))) {
            failure = catchThrowableOfType(NntpException.class,
                    () -> client.listActive(activeGroup -> names.add(activeGroup.name())));
        }

        assertThat(failure.type()).isEqualTo(NntpFailureType.CONNECTION_LOST);
        assertThat(names).containsExactly("example.first", "example.second");
    }

    @Test
    void listActiveThatTheServerDoesNotAnswerWithAListIsAProtocolFailure(CapturedOutput output) {
        mockServer.on("LIST ACTIVE").reply("500 What?");

        try (NntpClient client = connector.connect(plain(mockServer))) {
            NntpException failure = catchThrowableOfType(NntpException.class, () -> client.listActive(group -> { }));

            assertThat(failure.type()).isEqualTo(NntpFailureType.PROTOCOL);
        }
        assertThat(output).contains("PROTOCOL_ANOMALY kind=response.unexpected-code command=\"LIST ACTIVE\"");
    }

    @Test
    void serverThatRequiresALoginRefusesTheList() {
        mockServer.on("LIST ACTIVE").reply("480 Authentication required");

        try (NntpClient client = connector.connect(plain(mockServer))) {
            NntpException failure = catchThrowableOfType(NntpException.class, () -> client.listActive(group -> { }));

            assertThat(failure.type()).isEqualTo(NntpFailureType.SERVER_REFUSED);
            assertThat(failure).hasMessage("The server requires a login (480)");
        }
    }

    @Test
    void listNewsgroupsHandsOutDescriptionsSeparatedByTabOrSpaces() {
        mockServer.on("LIST NEWSGROUPS").reply("215 Descriptions follow", "example.first\tThe first group",
                "example.second    Second, with spaces ", "example.none", ".");

        List<NntpGroupDescription> descriptions = new ArrayList<>();
        boolean accepted;
        try (NntpClient client = connector.connect(plain(mockServer))) {
            accepted = client.listNewsgroups(descriptions::add);
        }

        assertThat(accepted).isTrue();
        assertThat(descriptions).containsExactly(
                new NntpGroupDescription("example.first", "The first group"),
                new NntpGroupDescription("example.second", "Second, with spaces"),
                new NntpGroupDescription("example.none", ""));
    }

    @Test
    void descriptionsAreDecodedAsUtf8AndOtherwiseAsLatin1() {
        byte[] status = "215 Descriptions follow\r\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] utf8Line = "example.utf8\tGrüße aus Köln\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] latin1Line = "example.latin1\tGrüße aus Köln\r\n.\r\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] answer = new byte[status.length + utf8Line.length + latin1Line.length];
        System.arraycopy(status, 0, answer, 0, status.length);
        System.arraycopy(utf8Line, 0, answer, status.length, utf8Line.length);
        System.arraycopy(latin1Line, 0, answer, status.length + utf8Line.length, latin1Line.length);
        mockServer.on("LIST NEWSGROUPS").reply(MockReply.raw(answer));

        List<NntpGroupDescription> descriptions = new ArrayList<>();
        try (NntpClient client = connector.connect(plain(mockServer))) {
            client.listNewsgroups(descriptions::add);
        }

        assertThat(descriptions).containsExactly(
                new NntpGroupDescription("example.utf8", "Grüße aus Köln"),
                new NntpGroupDescription("example.latin1", "Grüße aus Köln"));
    }

    @Test
    void rejectedListNewsgroupsIsNotAFailure() {
        mockServer.on("LIST NEWSGROUPS").reply("503 Descriptions are not available");
        mockServer.on("LIST ACTIVE").reply("215 Newsgroups follow", "example.first 20 1 y", ".");

        List<String> names = new ArrayList<>();
        try (NntpClient client = connector.connect(plain(mockServer))) {
            assertThat(client.listNewsgroups(description -> { })).isFalse();
            // The connection is still usable.
            client.listActive(activeGroup -> names.add(activeGroup.name()));
        }

        assertThat(names).containsExactly("example.first");
    }

    @Test
    void lineThatStartsWithADotIsUnstuffed() {
        mockServer.on("LIST NEWSGROUPS").reply("215 Descriptions follow", "..hidden.group\tStarts with a dot", ".");

        List<NntpGroupDescription> descriptions = new ArrayList<>();
        try (NntpClient client = connector.connect(plain(mockServer))) {
            client.listNewsgroups(descriptions::add);
        }

        assertThat(descriptions).containsExactly(new NntpGroupDescription(".hidden.group", "Starts with a dot"));
    }

    @Test
    void failureOfTheReceiverMakesTheConnectionUnusable() {
        mockServer.on("LIST ACTIVE").reply("215 Newsgroups follow", "example.first 20 1 y", "example.second 5 1 n",
                ".");

        CommonsNetClient client = (CommonsNetClient) connector.connect(plain(mockServer));
        try (client) {
            IllegalStateException failure = catchThrowableOfType(IllegalStateException.class,
                    () -> client.listActive(activeGroup -> {
                        throw new IllegalStateException("The database is gone");
                    }));

            assertThat(failure).hasMessage("The database is gone");
            assertThat(client.isUsable()).as("the rest of the list was not read").isFalse();
        }
    }

    // MODE READER

    @Test
    void modeReaderIsNotSentToAServerThatSaysItIsAReadingServer() {
        mockServer.on("LIST ACTIVE").reply("215 Newsgroups follow", ".");

        try (NntpClient client = connector.connect(plain(mockServer))) {
            client.listActive(group -> { });
        }

        assertThat(mockServer.commands()).doesNotContain("MODE READER");
    }

    @Test
    void modeReaderIsSentOnceBeforeTheFirstReadingCommandWhenTheCapabilityListDoesNotNameReader() {
        // The list a real server sent: it offers reading, but its list does not say so.
        mockServer.on("CAPABILITIES").reply("101 Capability list:", "VERSION 1", "AUTHINFO USER PASS", ".");
        mockServer.on("MODE READER").reply("200 Reader mode, posting allowed");
        mockServer.on("LIST ACTIVE").reply("215 Newsgroups follow", ".");
        mockServer.on("LIST NEWSGROUPS").reply("215 Descriptions follow", ".");

        try (NntpClient client = connector.connect(plain(mockServer))) {
            client.listActive(group -> { });
            client.listNewsgroups(description -> { });
        }

        assertThat(mockServer.commands()).containsExactly("CAPABILITIES", "MODE READER", "LIST ACTIVE",
                "LIST NEWSGROUPS", "QUIT");
    }

    @Test
    void serverWithoutCapabilitiesThatDoesNotKnowModeReaderIsStillRead() {
        // No command is scripted except the list, so CAPABILITIES and MODE READER get 500.
        mockServer.on("CAPABILITIES").reply("500 What?");
        mockServer.on("LIST ACTIVE").reply("215 Newsgroups follow", "example.first 20 1 y", ".");

        List<String> names = new ArrayList<>();
        try (NntpClient client = connector.connect(plain(mockServer))) {
            client.listActive(activeGroup -> names.add(activeGroup.name()));
        }

        assertThat(names).containsExactly("example.first");
        assertThat(mockServer.commands()).containsExactly("CAPABILITIES", "MODE READER", "LIST ACTIVE", "QUIT");
    }

    @Test
    void serverThatOffersNoReadingRefusesModeReader() {
        mockServer.on("CAPABILITIES").reply("101 Capability list:", "VERSION 2", "IHAVE", ".");
        mockServer.on("MODE READER").reply("502 Reading service permanently unavailable");

        try (NntpClient client = connector.connect(plain(mockServer))) {
            NntpException failure = catchThrowableOfType(NntpException.class, () -> client.listActive(group -> { }));

            assertThat(failure.type()).isEqualTo(NntpFailureType.SERVER_REFUSED);
        }
        assertThat(mockServer.commands()).doesNotContain("LIST ACTIVE");
    }
}
