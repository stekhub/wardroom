package io.github.stekhub.wardroom.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The test values of RFC 6238, appendix B, for SHA1. */
class TotpTest {

    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @ParameterizedTest
    @CsvSource({
        "59, 94287082",
        "1111111109, 07081804",
        "1111111111, 14050471",
        "1234567890, 89005924",
        "2000000000, 69279037",
        "20000000000, 65353130",
    })
    void computesTheCodesOfTheRfc(long secondsSince1970, String expectedEightDigits) {
        long period = Totp.period(Instant.ofEpochSecond(secondsSince1970));

        assertThat(Totp.code(RFC_SECRET, period, 8)).isEqualTo(expectedEightDigits);
        assertThat(Totp.code(RFC_SECRET, period)).as("6 digits are the last 6 of the 8")
                .isEqualTo(expectedEightDigits.substring(2));
    }

    @Test
    void aPeriodHasThirtySeconds() {
        assertThat(Totp.period(Instant.ofEpochSecond(0))).isZero();
        assertThat(Totp.period(Instant.ofEpochSecond(29))).isZero();
        assertThat(Totp.period(Instant.ofEpochSecond(30))).isEqualTo(1);
        assertThat(Totp.period(Instant.ofEpochSecond(59))).isEqualTo(1);
    }

    @Test
    void codesKeepTheirLeadingZeros() {
        assertThat(Totp.code(RFC_SECRET, Totp.period(Instant.ofEpochSecond(1111111109)))).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, Totp.period(Instant.ofEpochSecond(1234567890)))).isEqualTo("005924");
    }
}
