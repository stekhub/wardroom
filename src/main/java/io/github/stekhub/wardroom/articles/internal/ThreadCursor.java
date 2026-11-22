package io.github.stekhub.wardroom.articles.internal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * The place in a thread list after which the next page begins (spec 008, "Paging"): the sort keys and the thread
 * root of the last card of a page. The browser gets it as opaque text and sends it back unchanged.
 *
 * @param order        the order of the list the cursor belongs to
 * @param firstKey     the first sort key of the last card
 * @param secondKey    the second sort key of the last card; the thread root in an order that has none
 * @param threadRootId the thread root of the last card, which makes the place unique
 */
record ThreadCursor(ThreadOrder order, long firstKey, long secondKey, long threadRootId) {

    private static final String SEPARATOR = ",";

    /** Longer text is not looked at. */
    private static final int MAX_LENGTH = 200;

    String encode() {
        String plain = String.join(SEPARATOR, order.name(), Long.toString(firstKey), Long.toString(secondKey),
                Long.toString(threadRootId));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param text  what the browser sent; untrusted
     * @param order the order of the list that is asked for
     * @return the cursor, or empty when the text is not a cursor of a list in this order
     */
    static Optional<ThreadCursor> decode(String text, ThreadOrder order) {
        if (text.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        try {
            String plain = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
            String[] parts = plain.split(SEPARATOR, -1);
            if (parts.length != 4 || !parts[0].equals(order.name())) {
                return Optional.empty();
            }
            return Optional.of(new ThreadCursor(order, Long.parseLong(parts[1]), Long.parseLong(parts[2]),
                    Long.parseLong(parts[3])));
        } catch (IllegalArgumentException e) {
            // Not Base64, or not numbers.
            return Optional.empty();
        }
    }
}
