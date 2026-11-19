package io.github.stekhub.wardroom.platform;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ProtocolAnomalyLogTest {

    private static final ProtocolAnomalyKind KIND = ProtocolAnomalyKind.OVERVIEW_MALFORMED_LINE;

    private final Logger logger = (Logger) LoggerFactory.getLogger("test.protocol-anomalies");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        logger.setLevel(null);
        logger.setAdditive(true);
    }

    @Test
    void anomalyIsOneWarnLineWithMarkerKindAndFields() {
        ProtocolAnomalyLog log = new ProtocolAnomalyLog(logger);

        log.report(ProtocolAnomaly.builder()
                .kind(KIND)
                .command("OVER 100-199")
                .group("comp.lang.java.programmer")
                .number(142L)
                .messageId("<abc@example.org>")
                .action("skipped")
                .raw("142\tonly two fields")
                .build());

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).isEqualTo("PROTOCOL_ANOMALY kind=overview.malformed-line "
                    + "command=\"OVER 100-199\" group=comp.lang.java.programmer number=142 "
                    + "messageId=<abc@example.org> action=skipped raw=\"142\\tonly two fields\"");
        });
    }

    @Test
    void unknownFieldsAreLeftOut() {
        ProtocolAnomalyLog log = new ProtocolAnomalyLog(logger);

        log.report(ProtocolAnomaly.builder().kind(KIND).action("skipped").build());

        assertThat(messages()).containsExactly("PROTOCOL_ANOMALY kind=overview.malformed-line action=skipped");
    }

    @Test
    void untrustedValuesCannotAddLinesOrFields() {
        ProtocolAnomalyLog log = new ProtocolAnomalyLog(logger);

        log.report(ProtocolAnomaly.builder()
                .kind(KIND)
                .group("a.group action=accepted")
                .action("skipped")
                .raw("line one\r\nPROTOCOL_ANOMALY kind=forged \"quoted\" back\\slash")
                .build());

        assertThat(messages()).containsExactly("PROTOCOL_ANOMALY kind=overview.malformed-line "
                + "group=\"a.group action=accepted\" action=skipped "
                + "raw=\"line one\\r\\nPROTOCOL_ANOMALY kind=forged \\\"quoted\\\" back\\\\slash\"");
    }

    @Test
    void rawIsCutAfter500Characters() {
        ProtocolAnomalyLog log = new ProtocolAnomalyLog(logger);

        log.report(ProtocolAnomaly.builder().kind(KIND).action("skipped").raw("x".repeat(600)).build());

        assertThat(messages()).singleElement().asString().endsWith(" raw=\"" + "x".repeat(500) + "...\"");
    }

    @Test
    void afterFiveOfAKindTheRestIsCountedAndSummarized() {
        ProtocolAnomalyLog log = new ProtocolAnomalyLog(logger);

        for (long number = 1; number <= 12; number++) {
            log.report(ProtocolAnomaly.builder().kind(KIND).number(number).action("skipped").build());
        }
        assertThat(messages()).hasSize(5).last().asString().contains("number=5");

        log.finish();
        assertThat(messages()).hasSize(6).last().isEqualTo(
                "PROTOCOL_ANOMALY kind=overview.malformed-line summary=true total=12 logged=5");
        assertThat(appender.list.getLast().getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void noSummaryWhenEverythingWasLoggedInFull() {
        ProtocolAnomalyLog log = new ProtocolAnomalyLog(logger);

        for (long number = 1; number <= 5; number++) {
            log.report(ProtocolAnomaly.builder().kind(KIND).number(number).action("skipped").build());
        }
        log.finish();

        assertThat(messages()).hasSize(5);
    }

    @Test
    void atDebugEveryAnomalyIsLoggedInFull() {
        logger.setLevel(Level.DEBUG);
        ProtocolAnomalyLog log = new ProtocolAnomalyLog(logger);

        for (long number = 1; number <= 12; number++) {
            log.report(ProtocolAnomaly.builder().kind(KIND).number(number).action("skipped").build());
        }
        log.finish();

        assertThat(messages()).hasSize(12).allSatisfy(message -> assertThat(message).doesNotContain("summary"));
    }

    @Test
    void countingStartsOverAfterFinish() {
        ProtocolAnomalyLog log = new ProtocolAnomalyLog(logger);
        for (long number = 1; number <= 6; number++) {
            log.report(ProtocolAnomaly.builder().kind(KIND).number(number).action("skipped").build());
        }
        log.finish();
        appender.list.clear();

        log.report(ProtocolAnomaly.builder().kind(KIND).number(7L).action("skipped").build());

        assertThat(messages()).singleElement().asString().contains("number=7");
    }

    @Test
    void identifiersAreUnique() {
        assertThat(ProtocolAnomalyKind.values()).extracting(ProtocolAnomalyKind::id).doesNotHaveDuplicates()
                .allMatch(id -> id.matches("[a-z0-9]+(\\.[a-z0-9-]+)+"));
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
