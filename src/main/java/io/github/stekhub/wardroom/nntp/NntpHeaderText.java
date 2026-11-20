package io.github.stekhub.wardroom.nntp;

import io.github.stekhub.wardroom.nntp.internal.WireText;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import org.apache.james.mime4j.codec.DecodeMonitor;
import org.apache.james.mime4j.codec.DecoderUtil;
import org.apache.james.mime4j.dom.field.DateTimeField;
import org.apache.james.mime4j.field.DateTimeFieldLenientImpl;
import org.apache.james.mime4j.stream.RawField;

/**
 * Turns header values of articles into text, the same way for overviews and for fetched articles. The values are
 * untrusted: nothing in them can make these methods fail.
 */
public final class NntpHeaderText {

    private NntpHeaderText() {
    }

    /**
     * Turns the bytes of a header value into text and decodes its encoded words (RFC 2047). A word with an unknown
     * character set or a broken encoding stays as it is; invalid byte sequences are replaced, never rejected.
     *
     * @param rawValue the value with every byte as one ISO-8859-1 character
     */
    public static String decode(String rawValue) {
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

    /**
     * Turns bytes that a server sent outside of a body, such as a header block, into text: as UTF-8, and as
     * ISO-8859-1 when they are not valid UTF-8.
     */
    public static String decode(byte[] bytes) {
        return WireText.decode(new String(bytes, StandardCharsets.ISO_8859_1));
    }

    /** @return the date in UTC milliseconds, or {@code null} when the value is not a date */
    public static Long parseDate(String rawValue) {
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
}
