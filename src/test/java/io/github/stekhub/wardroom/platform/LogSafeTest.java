package io.github.stekhub.wardroom.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogSafeTest {

    @Test
    void harmlessTextIsUnchanged() {
        assertThat(LogSafe.escape("Re: Größe von <id@example.org> – ok?")).isEqualTo("Re: Größe von <id@example.org> – ok?");
        assertThat(LogSafe.escape("")).isEmpty();
        assertThat(LogSafe.escape(null)).isNull();
    }

    @Test
    void lineBreaksAndTabsAreEscaped() {
        assertThat(LogSafe.escape("first\r\nsecond\tthird")).isEqualTo("first\\r\\nsecond\\tthird");
    }

    @Test
    void otherControlCharactersAreEscaped() {
        assertThat(LogSafe.escape("a\u0000b\u001bc\u007fd\u0085e")).isEqualTo("a\\u0000b\\u001bc\\u007fd\\u0085e");
    }

    @Test
    void unicodeLineSeparatorsAreEscaped() {
        assertThat(LogSafe.escape("a b c")).isEqualTo("a\\u2028b\\u2029c");
    }

    @Test
    void backslashIsDoubledSoEscapedTextCannotBeFaked() {
        assertThat(LogSafe.escape("literal \\n stays literal")).isEqualTo("literal \\\\n stays literal");
    }

    @Test
    void escapedTextNeverContainsALineBreak() {
        String forged = "subject\n2026-01-01T00:00:00.000Z INFO  wardroom - Login succeeded";

        assertThat(LogSafe.escape(forged)).doesNotContain("\n", "\r");
    }

    @Test
    void longTextIsCut() {
        assertThat(LogSafe.escape("abcdef", 3)).isEqualTo("abc...");
        assertThat(LogSafe.escape("abc", 3)).isEqualTo("abc");
        assertThat(LogSafe.escape("a\nb\nc", 3)).as("the limit counts original characters").isEqualTo("a\\nb...");
        assertThat(LogSafe.escape(null, 3)).isNull();
    }
}
