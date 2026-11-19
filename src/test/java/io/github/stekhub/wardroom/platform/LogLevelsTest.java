package io.github.stekhub.wardroom.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class LogLevelsTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void levelIsInfoWithoutTheVariable(String value) {
        assertThat(LogLevels.fromEnvironmentValue(value)).containsExactlyInAnyOrderEntriesOf(Map.of(
                LogLevels.WARDROOM_PROPERTY, "INFO",
                LogLevels.ROOT_PROPERTY, "INFO"));
    }

    @ParameterizedTest
    @CsvSource({"DEBUG, DEBUG, INFO", "debug, DEBUG, INFO", "TRACE, TRACE, INFO", "INFO, INFO, INFO",
            "WARN, WARN, WARN", " error , ERROR, ERROR", "OFF, OFF, OFF"})
    void librariesFollowOnlyStricterLevels(String value, String wardroom, String root) {
        assertThat(LogLevels.fromEnvironmentValue(value)).containsExactlyInAnyOrderEntriesOf(Map.of(
                LogLevels.WARDROOM_PROPERTY, wardroom,
                LogLevels.ROOT_PROPERTY, root));
    }

    @Test
    void unknownValueFallsBackToInfoAndIsReported() {
        assertThat(LogLevels.fromEnvironmentValue("verbose\nINFO forged")).containsExactlyInAnyOrderEntriesOf(Map.of(
                LogLevels.WARDROOM_PROPERTY, "INFO",
                LogLevels.ROOT_PROPERTY, "INFO",
                LogLevels.INVALID_VALUE_PROPERTY, "verbose\\nINFO forged"));
    }
}
