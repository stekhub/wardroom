package io.github.stekhub.wardroom.platform;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The log output of the running application, with the real logging configuration (ADR 0029). */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class LogOutputTest {

    private static final String LINE_START = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z ";

    private final Logger log = LoggerFactory.getLogger(LogOutputTest.class);

    @Test
    void eventIsOneLineWithUtcTimestampLevelComponentAndMessage(CapturedOutput output) {
        log.info("format check {}", 42);

        assertThat(linesContaining(output, "format check")).singleElement().asString().matches(
                LINE_START + "INFO  i\\.g\\.s\\.wardroom\\.platform\\.LogOutputTest - format check 42");
    }

    @Test
    void escapedUntrustedTextCannotForgeALogLine(CapturedOutput output) {
        String subject = "Hello\r\n2026-01-01T00:00:00.000Z INFO  wardroom - forged-entry";

        log.info("article skipped, subject={}", LogSafe.escape(subject));

        assertThat(linesContaining(output, "forged-entry")).singleElement().asString()
                .matches(LINE_START + "INFO  .* - article skipped, subject=Hello\\\\r\\\\n2026.*");
    }

    /** The second line of defense in the logging configuration, for a message that was not escaped by the caller. */
    @Test
    void lineBreakInAnyMessageStaysOnOneLine(CapturedOutput output) {
        log.info("unescaped-message first\r\nsecond\nthird");

        assertThat(linesContaining(output, "unescaped-message")).singleElement().asString()
                .endsWith("unescaped-message first\\r\\nsecond\\nthird");
        assertThat(output.getOut().lines()).noneMatch(line -> line.startsWith("second") || line.startsWith("third"));
    }

    @Test
    void stackTraceFollowsItsEvent(CapturedOutput output) {
        log.error("with-stack-trace", new IllegalStateException("cause-message"));

        assertThat(output.getOut()).contains("with-stack-trace")
                .contains("java.lang.IllegalStateException: cause-message")
                .contains("\tat " + LogOutputTest.class.getName());
    }

    private static List<String> linesContaining(CapturedOutput output, String text) {
        return output.getOut().lines().filter(line -> line.contains(text)).toList();
    }
}
