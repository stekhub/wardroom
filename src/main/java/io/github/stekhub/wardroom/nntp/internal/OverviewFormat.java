package io.github.stekhub.wardroom.nntp.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Where the fields Wardroom reads stand in an overview line. A line is split at its tabs; position 0 is the article
 * number, and the other fields follow in the order the server names with {@code LIST OVERVIEW.FMT}
 * (RFC 3977, section 8.4).
 */
record OverviewFormat(int subject, int from, int date, int messageId, int references, int bytes, int lines) {

    /** The order every server has to use for the first seven fields (RFC 3977, section 8.3.2). */
    static final OverviewFormat RFC_3977 = new OverviewFormat(1, 2, 3, 4, 5, 6, 7);

    /**
     * @param formatLines the lines of a {@code LIST OVERVIEW.FMT} answer, such as {@code Subject:}, {@code :bytes}
     *                    or {@code Xref:full}
     * @return the positions, or empty when the answer does not name all seven fields
     */
    static Optional<OverviewFormat> ofListOverviewFmt(List<String> formatLines) {
        Map<String, Integer> positionByField = new HashMap<>();
        for (int index = 0; index < formatLines.size(); index++) {
            // The article number comes before the first listed field.
            positionByField.putIfAbsent(fieldName(formatLines.get(index)), index + 1);
        }
        Integer subject = positionByField.get("subject");
        Integer from = positionByField.get("from");
        Integer date = positionByField.get("date");
        Integer messageId = positionByField.get("message-id");
        Integer references = positionByField.get("references");
        Integer bytes = positionByField.get("bytes");
        Integer lines = positionByField.get("lines");
        if (subject == null || from == null || date == null || messageId == null || references == null
                || bytes == null || lines == null) {
            return Optional.empty();
        }
        return Optional.of(new OverviewFormat(subject, from, date, messageId, references, bytes, lines));
    }

    /** {@code Subject:}, {@code :bytes}, {@code Bytes:} and {@code Xref:full} name their field in different ways. */
    private static String fieldName(String formatLine) {
        String name = formatLine.strip().toLowerCase(Locale.ROOT);
        if (name.endsWith(":full")) {
            name = name.substring(0, name.length() - ":full".length());
        }
        if (name.startsWith(":")) {
            name = name.substring(1);
        }
        if (name.endsWith(":")) {
            name = name.substring(0, name.length() - 1);
        }
        return name;
    }
}
