package io.github.stekhub.wardroom.ignoring.internal;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The name of an author as the label of an ignore rule shows it: the display name of {@code From}; without one,
 * the address. The discussion view derives the name it shows by the same rules.
 */
final class AuthorName {

    /** As in {@code Jane Doe <jane@example.org>}: the name stands before the address. */
    private static final Pattern NAME_BEFORE_ADDRESS = Pattern.compile("^([^<]*)<[^<>]*>\\s*$");

    /** As in {@code jane@example.org (Jane Doe)}: the name stands in parentheses after the address. */
    private static final Pattern NAME_IN_PARENTHESES = Pattern.compile("^\\S+@\\S+\\s*\\(([^()]+)\\)\\s*$");

    private AuthorName() {
    }

    /**
     * @param from the {@code From} of an article that has an author key, so it is not blank; untrusted text
     */
    static String of(String from) {
        String text = from.strip();
        Matcher beforeAddress = NAME_BEFORE_ADDRESS.matcher(text);
        if (beforeAddress.matches()) {
            String name = unquoted(beforeAddress.group(1));
            return name.isEmpty() ? text.replace("<", "").replace(">", "").strip() : name;
        }
        Matcher inParentheses = NAME_IN_PARENTHESES.matcher(text);
        if (inParentheses.matches()) {
            return inParentheses.group(1).strip();
        }
        return text;
    }

    private static String unquoted(String name) {
        String stripped = name.strip();
        if (stripped.length() >= 2 && stripped.startsWith("\"") && stripped.endsWith("\"")) {
            return stripped.substring(1, stripped.length() - 1).strip();
        }
        return stripped;
    }
}
