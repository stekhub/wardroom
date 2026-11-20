package io.github.stekhub.wardroom.nntp.internal;

import static io.github.stekhub.wardroom.nntp.internal.NntpTestSupport.FAST;
import static io.github.stekhub.wardroom.nntp.internal.NntpTestSupport.plain;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.stekhub.wardroom.nntp.NntpClient;
import io.github.stekhub.wardroom.nntp.NntpException;
import io.github.stekhub.wardroom.nntp.NntpFailureType;
import io.github.stekhub.wardroom.nntp.NntpGroupStatus;
import io.github.stekhub.wardroom.nntp.NntpOverview;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import io.github.stekhub.wardroom.nntp.mock.MockReply;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** {@code GROUP}, {@code OVER}, {@code XOVER} and {@code LIST OVERVIEW.FMT} of the NNTP adapter (spec 005). */
@ExtendWith(OutputCaptureExtension.class)
class OverviewCommandsTest {

    private static final String[] RFC_FORMAT = {"215 Order of fields in overview database.", "Subject:", "From:",
        "Date:", "Message-ID:", "References:", ":bytes", ":lines", "Xref:full", "."};

    private MockNntpServer mockServer;
    private CommonsNetNntpConnector connector;

    @BeforeEach
    void startServer() throws Exception {
        mockServer = MockNntpServer.start();
        mockServer.on("CAPABILITIES").reply(MockReply.fixture("capabilities-reader.txt"));
        mockServer.on("GROUP example.talk").reply("211 3 5 9 example.talk");
        connector = new CommonsNetNntpConnector(FAST, SSLContext.getDefault());
    }

    @AfterEach
    void stopServer() throws IOException {
        mockServer.close();
    }

    // GROUP

    @Test
    void selectingAGroupReturnsItsCountAndWatermarks() {
        try (NntpClient client = connector.connect(plain(mockServer))) {
            assertThat(client.selectGroup("example.talk")).contains(new NntpGroupStatus(3, 5, 9));
        }

        assertThat(mockServer.commands()).contains("GROUP example.talk");
    }

    @Test
    void aGroupTheServerDoesNotHaveIsNotAFailure() {
        mockServer.on("GROUP example.gone").reply("411 No such newsgroup");

        try (NntpClient client = connector.connect(plain(mockServer))) {
            assertThat(client.selectGroup("example.gone")).isEmpty();
            // The connection is still usable.
            assertThat(client.selectGroup("example.talk")).isPresent();
        }
    }

    @Test
    void aGroupNameWithOtherLettersIsSentAsUtf8() {
        mockServer.on("GROUP").reply("211 0 1 0 group");

        try (NntpClient client = connector.connect(plain(mockServer))) {
            client.selectGroup("example.grüße");
        }

        // The mock reads every byte as one character.
        assertThat(mockServer.commands()).contains("GROUP example.grÃ¼Ã\u009Fe");
    }

    @Test
    void aGroupAnswerWithoutNumbersIsAProtocolFailure(CapturedOutput output) {
        mockServer.on("GROUP example.talk").reply("211 example.talk selected");

        try (NntpClient client = connector.connect(plain(mockServer))) {
            NntpException failure = catchThrowableOfType(NntpException.class,
                    () -> client.selectGroup("example.talk"));

            assertThat(failure.type()).isEqualTo(NntpFailureType.PROTOCOL);
        }
        assertThat(output).contains("PROTOCOL_ANOMALY kind=group.malformed-response command=GROUP group=example.talk");
    }

    // OVER

    @Test
    void overviewsAreReadInTheFieldOrderTheServerNames() {
        mockServer.on("LIST OVERVIEW.FMT").reply("215 Order of fields", "From:", "Subject:", "Date:",
                "Message-ID:", "References:", ":bytes", ":lines", ".");
        mockServer.on("OVER 5-9").reply(MockReply.fixture("over-from-first.txt"));

        List<NntpOverview> overviews = overviews(5, 9);

        assertThat(overviews).containsExactly(
                new NntpOverview(5, "Hello", "Ada <ada@example.org>", 1_795_000_000_000L, "<a@example.org>", null,
                        900L, 12L),
                new NntpOverview(7, "Re: Hello", "Jürgen <juergen@example.org>", 1_795_000_060_000L,
                        "<b@example.org>", "<a@example.org>", 1100L, 20L));
        assertThat(mockServer.commands()).containsSubsequence("GROUP example.talk", "LIST OVERVIEW.FMT", "OVER 5-9");
    }

    @Test
    void theFieldOrderIsAskedOncePerServerAndAgainAfterItWasForgotten() {
        mockServer.on("LIST OVERVIEW.FMT").reply(RFC_FORMAT);
        mockServer.on("OVER").reply("224 Overview information follows", ".");

        overviews(5, 9);
        overviews(5, 9);

        assertThat(mockServer.commands()).filteredOn("LIST OVERVIEW.FMT"::equals).hasSize(1);

        connector.forgetOverviewFormat(plain(mockServer).serverId());
        overviews(5, 9);

        assertThat(mockServer.commands()).filteredOn("LIST OVERVIEW.FMT"::equals).hasSize(2);
    }

    @Test
    void aServerThatDoesNotKnowTheFormatCommandGetsTheOrderOfRfc3977() {
        mockServer.on("LIST OVERVIEW.FMT").reply("503 Not supported");
        mockServer.on("OVER 5-9").reply("224 Overview information follows",
                "5\tHello\tAda <ada@example.org>\tTue, 17 Nov 2026 09:15:00 +0000\t<a@example.org>\t\t900\t12", ".");

        List<NntpOverview> overviews = overviews(5, 9);

        assertThat(overviews).extracting(NntpOverview::subject, NntpOverview::messageId)
                .containsExactly(org.assertj.core.api.Assertions.tuple("Hello", "<a@example.org>"));
    }

    @Test
    void aFormatThatDoesNotNameAllFieldsIsLoggedAndTheOrderOfRfc3977IsUsed(CapturedOutput output) {
        mockServer.on("LIST OVERVIEW.FMT").reply("215 Order of fields", "Subject:", "From:", ".");
        mockServer.on("OVER 5-9").reply("224 Overview information follows",
                "5\tHello\tAda\t\t<a@example.org>\t\t900\t12", ".");

        assertThat(overviews(5, 9)).extracting(NntpOverview::messageId).containsExactly("<a@example.org>");
        assertThat(output).contains("PROTOCOL_ANOMALY kind=overview.unusable-format")
                .contains("action=default-order-used");
    }

    @Test
    void aServerWhoseCapabilitiesDoNotListOverIsAskedWithXover() throws IOException {
        mockServer.on("CAPABILITIES").reply("101 Capability list:", "VERSION 2", "READER", ".");
        mockServer.on("LIST OVERVIEW.FMT").reply(RFC_FORMAT);
        mockServer.on("XOVER 5-9").reply("224 Overview information follows",
                "5\tHello\tAda\t\t<a@example.org>\t\t900\t12", ".");

        assertThat(overviews(5, 9)).hasSize(1);
        assertThat(mockServer.commands()).contains("XOVER 5-9").noneMatch(command -> command.startsWith("OVER"));
    }

    @Test
    void aServerWithoutCapabilitiesIsAskedWithXoverAndTheFormatCommand() {
        mockServer.on("CAPABILITIES").reply("500 What?");
        mockServer.on("MODE READER").reply("200 Reader mode");
        mockServer.on("LIST OVERVIEW.FMT").reply(RFC_FORMAT);
        mockServer.on("XOVER 5-9").reply("224 Overview information follows", ".");

        assertThat(overviews(5, 9)).isEmpty();
        assertThat(mockServer.commands()).containsSubsequence("LIST OVERVIEW.FMT", "XOVER 5-9");
    }

    @Test
    void aRangeWithoutArticlesHandsOutNothing() {
        mockServer.on("LIST OVERVIEW.FMT").reply(RFC_FORMAT);
        mockServer.on("OVER 5-9").reply("423 No articles in that range");
        mockServer.on("OVER 10-12").reply("420 Current article number is invalid");

        try (NntpClient client = connector.connect(plain(mockServer))) {
            client.selectGroup("example.talk");
            List<NntpOverview> overviews = new ArrayList<>();
            client.overviews(5, 9, overviews::add);
            client.overviews(10, 12, overviews::add);

            assertThat(overviews).isEmpty();
        }
    }

    @Test
    void linesThatCannotBeReadAreSkippedAndLogged(CapturedOutput output) {
        mockServer.on("LIST OVERVIEW.FMT").reply(RFC_FORMAT);
        mockServer.on("OVER 5-9").reply("224 Overview information follows",
                "5\tHello\tAda\t\t<a@example.org>\t\t900\t12",
                "not an overview",
                "6\tNo ID\tAda\t\t\t\t900\t12",
                "7\tLast\tAda\t\t<c@example.org>\t\t900\t12", ".");

        assertThat(overviews(5, 9)).extracting(NntpOverview::number).containsExactly(5L, 7L);
        assertThat(output)
                .contains("PROTOCOL_ANOMALY kind=overview.malformed-line command=\"OVER 5-9\" group=example.talk"
                        + " action=skipped raw=\"not an overview\"")
                .contains("PROTOCOL_ANOMALY kind=overview.missing-message-id command=\"OVER 5-9\" group=example.talk"
                        + " number=6 action=skipped");
    }

    @Test
    void anOverviewAnswerThatBreaksOffIsAFailure() {
        mockServer.on("LIST OVERVIEW.FMT").reply(RFC_FORMAT);
        mockServer.on("OVER 5-9").reply(MockReply.cutOff("224 Overview information follows",
                "5\tHello\tAda\t\t<a@example.org>\t\t900\t12"));

        try (NntpClient client = connector.connect(plain(mockServer))) {
            client.selectGroup("example.talk");
            NntpException failure = catchThrowableOfType(NntpException.class,
                    () -> client.overviews(5, 9, overview -> { }));

            assertThat(failure.type()).isEqualTo(NntpFailureType.CONNECTION_LOST);
        }
    }

    @Test
    void overviewsWithoutASelectedGroupAreAProtocolFailure() {
        mockServer.on("LIST OVERVIEW.FMT").reply(RFC_FORMAT);
        mockServer.on("OVER 5-9").reply("412 No newsgroup selected");

        try (NntpClient client = connector.connect(plain(mockServer))) {
            NntpException failure = catchThrowableOfType(NntpException.class,
                    () -> client.overviews(5, 9, overview -> { }));

            assertThat(failure.type()).isEqualTo(NntpFailureType.PROTOCOL);
        }
    }

    private List<NntpOverview> overviews(long firstNumber, long lastNumber) {
        List<NntpOverview> overviews = new ArrayList<>();
        try (NntpClient client = connector.connect(plain(mockServer))) {
            client.selectGroup("example.talk");
            client.overviews(firstNumber, lastNumber, overviews::add);
        }
        return overviews;
    }
}
