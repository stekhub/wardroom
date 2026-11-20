package io.github.stekhub.wardroom.articles.internal;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares subjects for the subject fallback (spec 006): reply prefixes are removed, whitespace is collapsed, and
 * case is ignored.
 */
final class SubjectNormalizer {

    /**
     * One reply prefix: {@code Re:}, {@code Aw:}, {@code Sv:} or {@code Antw:}, also with a counter such as
     * {@code Re[2]:} or {@code Re^2:}. Repeated prefixes are removed in a loop and not by the pattern, so that a
     * crafted subject with very many of them cannot exhaust the stack.
     */
    private static final Pattern REPLY_PREFIX = Pattern.compile(
            "\\s*(?:re|aw|sv|antw)(?:\\[\\d{1,9}]|\\^\\d{1,9})?:",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private SubjectNormalizer() {
    }

    /** @return whether the subject starts with a reply prefix */
    static boolean hasReplyPrefix(String subject) {
        return subject != null && endOfReplyPrefixes(subject) > 0;
    }

    /**
     * @return the subject without its leading reply prefixes, with single spaces and in lower case; two subjects
     *         are the same for the fallback when this text is equal. Empty for a subject that is {@code null}.
     */
    static String normalize(String subject) {
        if (subject == null) {
            return "";
        }
        String withoutPrefixes = subject.substring(endOfReplyPrefixes(subject));
        return WHITESPACE.matcher(withoutPrefixes).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
    }

    /** @return where the text after the leading reply prefixes begins; 0 when the subject has none */
    private static int endOfReplyPrefixes(String subject) {
        Matcher prefix = REPLY_PREFIX.matcher(subject);
        int end = 0;
        while (prefix.region(end, subject.length()).lookingAt()) {
            end = prefix.end();
        }
        return end;
    }
}
