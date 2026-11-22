package io.github.stekhub.wardroom.posting.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the text of the owner into a body of {@code format=flowed} (RFC 3676; spec 009, "Body").
 *
 * <ul>
 * <li>Trailing spaces are removed from every line, because a line that ends with a space would be read as
 * continued on the next one. The signature separator {@code "-- "} keeps its space.</li>
 * <li>A line of text that is longer than 72 characters is broken after a space; the space stays at the end of the
 * line and marks the break as soft, so a reader joins the lines again.</li>
 * <li>A quoted line, which starts with {@code >}, is never broken.</li>
 * <li>A line that starts with a space, with {@code >} without being a quote, or with {@code "From "} gets a space
 * in front of it (space-stuffing).</li>
 * </ul>
 */
final class FlowedText {

    /** The longest line the wrapping produces, with its trailing space. */
    static final int LINE_LENGTH = 72;

    private static final String SIGNATURE_SEPARATOR = "-- ";

    private FlowedText() {
    }

    /**
     * @param text the text as it was written, with any line ending
     * @return the lines of the body, without line endings
     */
    static List<String> encode(String text) {
        List<String> flowedLines = new ArrayList<>();
        for (String line : text.split("\r\n|\r|\n", -1)) {
            if (line.equals(SIGNATURE_SEPARATOR)) {
                flowedLines.add(line);
                continue;
            }
            String written = withoutTrailingSpaces(line);
            if (written.startsWith(">")) {
                flowedLines.add(written);
            } else {
                wrap(written, flowedLines);
            }
        }
        return flowedLines;
    }

    private static void wrap(String line, List<String> flowedLines) {
        String rest = line;
        while (stuffed(rest).length() > LINE_LENGTH) {
            int longestPart = LINE_LENGTH - (stuffed(rest).length() - rest.length());
            int breakAt = rest.lastIndexOf(' ', longestPart - 1);
            if (breakAt <= 0) {
                // A word that is longer than a line is not broken; the line ends after it.
                breakAt = rest.indexOf(' ', longestPart);
            }
            if (breakAt <= 0 || breakAt >= rest.length() - 1) {
                break;
            }
            flowedLines.add(stuffed(rest.substring(0, breakAt + 1)));
            rest = rest.substring(breakAt + 1);
        }
        flowedLines.add(stuffed(rest));
    }

    private static String stuffed(String line) {
        return line.startsWith(" ") || line.startsWith(">") || line.startsWith("From ") ? " " + line : line;
    }

    private static String withoutTrailingSpaces(String line) {
        int end = line.length();
        while (end > 0 && line.charAt(end - 1) == ' ') {
            end--;
        }
        return line.substring(0, end);
    }
}
