package io.github.stekhub.wardroom.nntp.internal;

import io.github.stekhub.wardroom.nntp.NntpOverview;
import java.util.Date;
import org.apache.james.mime4j.codec.DecodeMonitor;
import org.apache.james.mime4j.codec.DecoderUtil;
import org.apache.james.mime4j.dom.field.DateTimeField;
import org.apache.james.mime4j.field.DateTimeFieldLenientImpl;
import org.apache.james.mime4j.stream.RawField;

/**
 * Reads one line of an {@code OVER} or {@code XOVER} answer (RFC 3977, section 8.3; spec 005). The content of a
 * line is untrusted: nothing in it can make the parser fail.
 */
final class OverviewParser {

    private OverviewParser() {
    }

    /** What became of a line. */
    enum Result {
        /** The line is an overview. */
        OVERVIEW,
        /** The line has no article number or ends before its Message-ID field. */
        MALFORMED,
        /** The Message-ID field is empty. */
        MISSING_MESSAGE_ID
    }

    /**
     * @param result   what became of the line
     * @param overview the overview, or {@code null} unless the result is {@link Result#OVERVIEW}
     * @param number   the article number, or {@code null} when the line has none
     */
    record ParsedLine(Result result, NntpOverview overview, Long number) {
    }

    /**
     * @param line   a line as the library read it: every byte one ISO-8859-1 character
     * @param format where the fields stand
     */
    static ParsedLine parse(String line, OverviewFormat format) {
        String[] fields = line.split("\t", -1);
        Long number = parseNumber(fields[0]);
        if (number == null || number < 1 || fields.length <= format.messageId()) {
            return new ParsedLine(Result.MALFORMED, null, number);
        }
        String messageId = fields[format.messageId()].strip();
        if (messageId.isEmpty()) {
            return new ParsedLine(Result.MISSING_MESSAGE_ID, null, number);
        }
        String references = field(fields, format.references()).strip();
        NntpOverview overview = new NntpOverview(number, decodeHeader(field(fields, format.subject())),
                decodeHeader(field(fields, format.from())), parseDate(field(fields, format.date())), messageId,
                references.isEmpty() ? null : references, parseNumber(field(fields, format.bytes())),
                parseNumber(field(fields, format.lines())));
        return new ParsedLine(Result.OVERVIEW, overview, number);
    }

    /**
     * Turns the bytes of a header into text and decodes its encoded words (RFC 2047). A word with an unknown
     * character set or a broken encoding stays as it is; invalid byte sequences are replaced, never rejected.
     */
    static String decodeHeader(String rawValue) {
        String text = WireText.decode(rawValue).strip();
        if (!text.contains("=?")) {
            return text;
        }
        try {
            return DecoderUtil.decodeEncodedWords(text, DecodeMonitor.SILENT);
        } catch (RuntimeException e) {
            return text;
        }
    }

    /** @return the date in UTC milliseconds, or {@code null} when the value is not a date */
    static Long parseDate(String rawValue) {
        String text = rawValue.strip();
        if (text.isEmpty()) {
            return null;
        }
        try {
            DateTimeField dateField = DateTimeFieldLenientImpl.PARSER.parse(new RawField("Date", text),
                    DecodeMonitor.SILENT);
            Date date = dateField.getDate();
            return date == null ? null : date.getTime();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String field(String[] fields, int position) {
        return position < fields.length ? fields[position] : "";
    }

    /** @return the number, or {@code null} when the text is not a number that is zero or more */
    private static Long parseNumber(String text) {
        String digits = text.strip();
        if (digits.isEmpty() || digits.length() > 18 || !digits.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return Long.parseLong(digits);
    }
}
