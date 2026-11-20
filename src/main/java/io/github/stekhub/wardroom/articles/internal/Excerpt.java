package io.github.stekhub.wardroom.articles.internal;

/** The excerpt of an article on a thread card (spec 007, "Card excerpt"). */
final class Excerpt {

    static final int MAX_LENGTH = 200;

    private static final String SIGNATURE_SEPARATOR = "-- ";

    private Excerpt() {
    }

    /**
     * @param body the stored text of the article, or {@code null} when its content is not cached
     * @return the first {@value #MAX_LENGTH} characters of the text without quoted lines and without the
     *         signature, with every run of whitespace as one space; {@code null} when nothing is left
     */
    static String of(String body) {
        if (body == null) {
            return null;
        }
        StringBuilder excerpt = new StringBuilder();
        for (String line : body.split("\n")) {
            if (line.equals(SIGNATURE_SEPARATOR)) {
                break;
            }
            if (line.startsWith(">")) {
                continue;
            }
            for (String word : line.strip().split("\\s+")) {
                if (word.isEmpty()) {
                    continue;
                }
                if (!excerpt.isEmpty()) {
                    excerpt.append(' ');
                }
                excerpt.append(word);
            }
            if (excerpt.codePointCount(0, excerpt.length()) >= MAX_LENGTH) {
                break;
            }
        }
        if (excerpt.isEmpty()) {
            return null;
        }
        int end = excerpt.offsetByCodePoints(0, Math.min(MAX_LENGTH, excerpt.codePointCount(0, excerpt.length())));
        return excerpt.substring(0, end).strip();
    }
}
