package io.github.stekhub.wardroom.nntp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.nntp.NntpOverview;
import io.github.stekhub.wardroom.nntp.internal.OverviewParser.ParsedLine;
import io.github.stekhub.wardroom.nntp.internal.OverviewParser.Result;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Overview lines and the overview format (spec 005, "Overviews"). */
class OverviewParserTest {

    private static final long NOVEMBER_17_0915_UTC =
            ZonedDateTime.of(2026, 11, 17, 9, 15, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli();

    // Lines

    @Test
    void aLineInTheOrderOfRfc3977IsRead() {
        ParsedLine parsedLine = OverviewParser.parse("120\tRe: Hello\tAda <ada@example.org>\t"
                + "Tue, 17 Nov 2026 10:15:00 +0100\t<b@example.org>\t<a@example.org>\t1234\t17\tXref: x", rfcOrder());

        assertThat(parsedLine.result()).isEqualTo(Result.OVERVIEW);
        assertThat(parsedLine.overview()).isEqualTo(new NntpOverview(120, "Re: Hello", "Ada <ada@example.org>",
                NOVEMBER_17_0915_UTC, "<b@example.org>", "<a@example.org>", 1234L, 17L));
    }

    @Test
    void theFieldsAreTakenFromThePositionsOfTheFormat() {
        OverviewFormat format = OverviewFormat.ofListOverviewFmt(List.of("From:", "Subject:", "Message-ID:",
                "Date:", "Lines:", "Bytes:", "References:")).orElseThrow();

        ParsedLine parsedLine = OverviewParser.parse("7\tAda <ada@example.org>\tHello\t<a@example.org>\t"
                + "17 Nov 2026 09:15:00 GMT\t3\t800\t", format);

        assertThat(parsedLine.overview()).isEqualTo(new NntpOverview(7, "Hello", "Ada <ada@example.org>",
                NOVEMBER_17_0915_UTC, "<a@example.org>", null, 800L, 3L));
    }

    @Test
    void fieldsThatAreMissingAtTheEndOrAreNoNumbersAreEmpty() {
        ParsedLine parsedLine = OverviewParser.parse("7\tHello\tAda\tno date\t<a@example.org>\t\tmany", rfcOrder());

        assertThat(parsedLine.overview())
                .isEqualTo(new NntpOverview(7, "Hello", "Ada", null, "<a@example.org>", null, null, null));
    }

    @Test
    void aLineWithoutAnArticleNumberOrWithoutAMessageIdFieldIsMalformed() {
        assertThat(OverviewParser.parse("", rfcOrder()).result()).isEqualTo(Result.MALFORMED);
        assertThat(OverviewParser.parse("abc\tHello\tAda\t\t<a@example.org>", rfcOrder()).result())
                .isEqualTo(Result.MALFORMED);
        assertThat(OverviewParser.parse("0\tHello\tAda\t\t<a@example.org>", rfcOrder()).result())
                .isEqualTo(Result.MALFORMED);
        ParsedLine tooShort = OverviewParser.parse("12\tHello\tAda", rfcOrder());
        assertThat(tooShort.result()).isEqualTo(Result.MALFORMED);
        assertThat(tooShort.number()).isEqualTo(12);
    }

    @Test
    void anEmptyMessageIdIsReportedAsMissing() {
        ParsedLine parsedLine = OverviewParser.parse("12\tHello\tAda\t\t \t\t10\t1", rfcOrder());

        assertThat(parsedLine.result()).isEqualTo(Result.MISSING_MESSAGE_ID);
        assertThat(parsedLine.number()).isEqualTo(12);
    }

    // Header decoding

    @Test
    void encodedWordsAreDecoded() {
        assertThat(OverviewParser.decodeHeader("=?ISO-8859-1?Q?Gr=FC=DFe?= aus =?UTF-8?B?S8O2bG4=?="))
                .isEqualTo("Grüße aus Köln");
        assertThat(OverviewParser.decodeHeader("=?UTF-8?Q?J=C3=BCrgen?= <juergen@example.org>"))
                .isEqualTo("Jürgen <juergen@example.org>");
    }

    @Test
    void bytesThatAreNotEncodedAreReadAsUtf8AndOtherwiseAsLatin1() {
        String utf8OnTheWire = new String("Grüße".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        String latin1OnTheWire = "Grüße";

        assertThat(OverviewParser.decodeHeader(utf8OnTheWire)).isEqualTo("Grüße");
        assertThat(OverviewParser.decodeHeader(latin1OnTheWire)).isEqualTo("Grüße");
    }

    @Test
    void anEncodedWordThatCannotBeDecodedDoesNotFail() {
        assertThat(OverviewParser.decodeHeader("=?no-such-charset?Q?abc?= rest")).contains("rest");
        assertThat(OverviewParser.decodeHeader("=?UTF-8?B?%%%?=")).isNotNull();
        assertThat(OverviewParser.decodeHeader("=?UTF-8?Q?=FF=FE?=")).isNotNull();
        assertThat(OverviewParser.decodeHeader("=?")).isEqualTo("=?");
    }

    // Dates

    @Test
    void datesAreReadAsUtcMilliseconds() {
        assertThat(OverviewParser.parseDate("Tue, 17 Nov 2026 10:15:00 +0100")).isEqualTo(NOVEMBER_17_0915_UTC);
        assertThat(OverviewParser.parseDate("17 Nov 2026 09:15:00 GMT")).isEqualTo(NOVEMBER_17_0915_UTC);
        assertThat(OverviewParser.parseDate("Tue, 17 Nov 2026 04:15:00 -0500")).isEqualTo(NOVEMBER_17_0915_UTC);
    }

    @Test
    void aValueThatIsNoDateIsNull() {
        assertThat(OverviewParser.parseDate("")).isNull();
        assertThat(OverviewParser.parseDate("yesterday")).isNull();
        assertThat(OverviewParser.parseDate("99 Foo 2026 25:61:00 +0000")).isNull();
    }

    // Format

    @Test
    void theFormatOfAServerNamesTheFieldsInSeveralWays() {
        assertThat(OverviewFormat.ofListOverviewFmt(List.of("Subject:", "From:", "Date:", "Message-ID:",
                "References:", ":bytes", ":lines", "Xref:full"))).contains(OverviewFormat.RFC_3977);
        assertThat(OverviewFormat.ofListOverviewFmt(List.of("subject:", "FROM:", "Date:", "Message-Id:",
                "References:", "Bytes:", "Lines:", "Xref:full"))).contains(OverviewFormat.RFC_3977);
    }

    @Test
    void aFormatThatDoesNotNameAllFieldsIsNotUsed() {
        assertThat(OverviewFormat.ofListOverviewFmt(List.of("Subject:", "From:", "Date:"))).isEmpty();
        assertThat(OverviewFormat.ofListOverviewFmt(List.of())).isEmpty();
    }

    private static OverviewFormat rfcOrder() {
        return OverviewFormat.RFC_3977;
    }
}
