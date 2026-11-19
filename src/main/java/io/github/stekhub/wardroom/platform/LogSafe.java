package io.github.stekhub.wardroom.platform;

/**
 * Escapes untrusted text before it is logged (ADR 0029).
 *
 * <p>Subjects, author names, Message-IDs, request paths and raw server lines come from outside Wardroom. Without
 * escaping, a line break in such a text would start a new log line that looks like one Wardroom wrote itself.
 */
public final class LogSafe {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private LogSafe() {
    }

    /**
     * @return the text on one line: line breaks and other control characters are replaced by escape sequences, and
     *         the backslash is doubled so the result is unambiguous. {@code null} stays {@code null}.
     */
    public static String escape(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder escaped = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String replacement = replacement(c);
            if (replacement != null && escaped == null) {
                escaped = new StringBuilder(text.length() + 16).append(text, 0, i);
            }
            if (escaped != null) {
                if (replacement != null) {
                    escaped.append(replacement);
                } else {
                    escaped.append(c);
                }
            }
        }
        return escaped == null ? text : escaped.toString();
    }

    /**
     * Escapes the text like {@link #escape(String)} and cuts it after {@code maxLength} characters of the original.
     * A cut text ends with {@code ...}.
     */
    public static String escape(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return escape(text);
        }
        return escape(text.substring(0, maxLength)) + "...";
    }

    private static String replacement(char c) {
        return switch (c) {
            case '\\' -> "\\\\";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            default -> isUnsafe(c) ? unicodeEscape(c) : null;
        };
    }

    /** Control characters, and the Unicode line and paragraph separators that some viewers treat as line breaks. */
    private static boolean isUnsafe(char c) {
        return Character.isISOControl(c) || c == ' ' || c == ' ';
    }

    private static String unicodeEscape(char c) {
        return new String(new char[] {'\\', 'u', HEX[(c >> 12) & 0xF], HEX[(c >> 8) & 0xF], HEX[(c >> 4) & 0xF],
                HEX[c & 0xF]});
    }
}
