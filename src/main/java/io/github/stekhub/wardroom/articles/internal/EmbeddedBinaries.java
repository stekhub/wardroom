package io.github.stekhub.wardroom.articles.internal;

import io.github.stekhub.wardroom.articles.internal.ArticleContentExtractor.OmittedPart;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds binary content that is written into the text of an article, as yEnc or uuencode, and takes it out
 * (ADR 0014). Only the name and the size are kept.
 */
final class EmbeddedBinaries {

    private static final String BINARY = "application/octet-stream";
    private static final int NAME_MAX_LENGTH = 200;
    private static final String YENC_BEGIN = "=ybegin ";
    private static final String YENC_END = "=yend";
    private static final Pattern YENC_NAME = Pattern.compile(" name=(.*)$");
    private static final Pattern YENC_SIZE = Pattern.compile(" size=(\\d{1,18})(?: |$)");
    private static final Pattern UUENCODE_BEGIN = Pattern.compile("^begin [0-7]{3,4} (\\S.*)$");
    private static final String UUENCODE_END = "end";
    /** A full line of uuencoded data: the length character {@code M} and 60 characters. */
    private static final int UUENCODE_LINE_LENGTH = 61;

    private EmbeddedBinaries() {
    }

    /**
     * @param text         the text with {@code \n} line endings
     * @param omittedParts gets an entry for every block that was taken out
     * @return the text without the blocks
     */
    static String remove(String text, List<OmittedPart> omittedParts) {
        if (!text.contains(YENC_BEGIN) && !text.contains("begin ")) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        StringBuilder kept = new StringBuilder(text.length());
        int index = 0;
        while (index < lines.length) {
            int blockEnd = -1;
            if (lines[index].startsWith(YENC_BEGIN)) {
                blockEnd = endOfYenc(lines, index, omittedParts);
            } else if (isUuencodeBegin(lines, index)) {
                blockEnd = endOfUuencode(lines, index, omittedParts);
            }
            if (blockEnd >= 0) {
                index = blockEnd + 1;
                continue;
            }
            kept.append(lines[index]);
            if (index < lines.length - 1) {
                kept.append('\n');
            }
            index++;
        }
        return kept.toString();
    }

    /** @return the last line of the block; the end of the text when the block has no end line */
    private static int endOfYenc(String[] lines, int begin, List<OmittedPart> omittedParts) {
        int end = begin + 1;
        long encodedSize = 0;
        while (end < lines.length && !lines[end].startsWith(YENC_END)) {
            encodedSize += lines[end].length();
            end++;
        }
        Matcher name = YENC_NAME.matcher(lines[begin]);
        Matcher size = YENC_SIZE.matcher(lines[begin]);
        omittedParts.add(new OmittedPart(name.find() ? clean(name.group(1)) : null, BINARY,
                size.find() ? Long.parseLong(size.group(1)) : encodedSize));
        return Math.min(end, lines.length - 1);
    }

    /** A line that looks like the start is one only when encoded data or the end follows. */
    private static boolean isUuencodeBegin(String[] lines, int index) {
        if (!lines[index].startsWith("begin ") || !UUENCODE_BEGIN.matcher(lines[index]).matches()
                || index + 1 >= lines.length) {
            return false;
        }
        String next = lines[index + 1];
        return next.equals(UUENCODE_END) || next.equals("`")
                || next.startsWith("M") && next.length() == UUENCODE_LINE_LENGTH;
    }

    private static int endOfUuencode(String[] lines, int begin, List<OmittedPart> omittedParts) {
        int end = begin + 1;
        long encodedSize = 0;
        while (end < lines.length && !lines[end].equals(UUENCODE_END)) {
            // The first character of a line is its length.
            encodedSize += Math.max(0, lines[end].length() - 1);
            end++;
        }
        Matcher name = UUENCODE_BEGIN.matcher(lines[begin]);
        // Four encoded characters are three bytes.
        omittedParts.add(new OmittedPart(name.matches() ? clean(name.group(1)) : null, BINARY,
                encodedSize * 3 / 4));
        return Math.min(end, lines.length - 1);
    }

    private static String clean(String name) {
        String cleaned = name.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString()
                .strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() <= NAME_MAX_LENGTH ? cleaned : cleaned.substring(0, NAME_MAX_LENGTH);
    }
}
