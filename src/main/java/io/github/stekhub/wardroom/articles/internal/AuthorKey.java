package io.github.stekhub.wardroom.articles.internal;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The key that stands for an author (UI concept, "Author icons"): the email address of {@code From}, lowercased
 * and trimmed; without an address, the display name.
 */
final class AuthorKey {

    /** An address in angle brackets, as in {@code Jane Doe <jane@example.org>}. */
    private static final Pattern BRACKETED_ADDRESS = Pattern.compile("<\\s*([^<>\\s]+@[^<>\\s]+)\\s*>");

    /** An address that stands for itself, as in {@code jane@example.org (Jane Doe)}. */
    private static final Pattern BARE_ADDRESS = Pattern.compile("[^\\s<>()\",;:]+@[^\\s<>()\",;:]+");

    private AuthorKey() {
    }

    /**
     * @param from the {@code From} of an article as it is stored; untrusted text
     * @return the key, or {@code null} when there is no {@code From} or it is blank, as for a stub
     */
    static String of(String from) {
        if (from == null || from.isBlank()) {
            return null;
        }
        Matcher bracketed = BRACKETED_ADDRESS.matcher(from);
        if (bracketed.find()) {
            return bracketed.group(1).toLowerCase(Locale.ROOT);
        }
        Matcher bare = BARE_ADDRESS.matcher(from);
        if (bare.find()) {
            return bare.group().toLowerCase(Locale.ROOT);
        }
        return from.strip();
    }
}
