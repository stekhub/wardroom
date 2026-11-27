package io.github.stekhub.wardroom.ignoring;

import java.util.Locale;

/**
 * The words of a subject, as the ignored words are compared with them (spec 023, "Matching").
 *
 * <p>The subject is lowercased, every character that is no letter and no digit becomes a space, runs of spaces
 * become one, and one space stands before and after: {@code Re: Java 25 — released!} gives
 * {@code " re java 25 released "}. A word of a rule, normalized the same way, matches when the subject words
 * contain it with a space before and after. So whole words match, never parts of words.
 *
 * <p>The code that stores an article or fills in a stub computes the subject words with this class.
 */
public final class SubjectWords {

    private SubjectWords() {
    }

    /**
     * @param subject the decoded subject; untrusted text, or {@code null}
     * @return the subject words, or {@code null} without a subject. A subject without a letter or digit gives one
     *         space
     */
    public static String of(String subject) {
        if (subject == null) {
            return null;
        }
        StringBuilder subjectWords = new StringBuilder(" ");
        boolean afterSpace = true;
        // The root locale, so the result does not depend on the language of the container.
        String lowercased = subject.toLowerCase(Locale.ROOT);
        for (int index = 0; index < lowercased.length(); ) {
            int codePoint = lowercased.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                subjectWords.appendCodePoint(codePoint);
                afterSpace = false;
            } else if (!afterSpace) {
                subjectWords.append(' ');
                afterSpace = true;
            }
        }
        if (!afterSpace) {
            subjectWords.append(' ');
        }
        return subjectWords.toString();
    }

    /**
     * @param text a word or phrase the owner typed
     * @return the text as a rule stores it: normalized like subject words, without the spaces around it. Empty
     *         when the text has no letter and no digit
     */
    public static String ofRule(String text) {
        return text == null ? "" : of(text).strip();
    }
}
