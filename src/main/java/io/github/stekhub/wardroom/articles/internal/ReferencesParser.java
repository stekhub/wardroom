package io.github.stekhub.wardroom.articles.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the Message-IDs of a {@code References} or {@code In-Reply-To} header (spec 006, "Parsing References").
 * The header comes from an article and is untrusted: nothing in it makes the parser fail or work for long.
 */
final class ReferencesParser {

    /** At most this many Message-IDs of a header are used, the last ones. */
    static final int MAX_MESSAGE_IDS = 100;

    private ReferencesParser() {
    }

    /**
     * @param header       the value of the header, or {@code null} when the article has none
     * @param ownMessageId the Message-ID of the article the header belongs to; it is dropped from the result
     * @return the Message-IDs in the order of the header, each once and with its angle brackets, oldest first
     */
    static List<String> parse(String header, String ownMessageId) {
        if (header == null) {
            return List.of();
        }
        Set<String> messageIds = new LinkedHashSet<>();
        int tokenStart = -1;
        for (int index = 0; index < header.length(); index++) {
            char character = header.charAt(index);
            if (character == '<') {
                tokenStart = index;
            } else if (character == '>' && tokenStart >= 0) {
                String messageId = header.substring(tokenStart, index + 1);
                if (isUsable(messageId) && !messageId.equals(ownMessageId)) {
                    messageIds.add(messageId);
                }
                tokenStart = -1;
            }
        }
        List<String> inOrder = new ArrayList<>(messageIds);
        return inOrder.size() <= MAX_MESSAGE_IDS
                ? inOrder
                : List.copyOf(inOrder.subList(inOrder.size() - MAX_MESSAGE_IDS, inOrder.size()));
    }

    /**
     * @return whether the header has anything of the form {@code <…>}. An article whose header has none counts as
     *         an article without references.
     */
    static boolean hasMessageIdToken(String header) {
        if (header == null) {
            return false;
        }
        int tokenStart = header.indexOf('<');
        return tokenStart >= 0 && header.indexOf('>', tokenStart) >= 0;
    }

    /**
     * A Message-ID is later sent to the server in commands, so one with whitespace or control characters is not
     * used. An empty one names nothing.
     */
    private static boolean isUsable(String messageId) {
        if (messageId.length() <= 2) {
            return false;
        }
        for (int index = 0; index < messageId.length(); index++) {
            char character = messageId.charAt(index);
            if (Character.isWhitespace(character) || Character.isISOControl(character)) {
                return false;
            }
        }
        return true;
    }
}
