package io.github.stekhub.wardroom.auth.internal;

import io.github.stekhub.wardroom.platform.FieldValidationException;

/** The rules for username and password (spec 001). Lengths are counted in characters, not in bytes. */
final class AccountRules {

    static final int USERNAME_MAX_LENGTH = 64;
    static final int PASSWORD_MIN_LENGTH = 12;
    static final int PASSWORD_MAX_LENGTH = 128;

    private AccountRules() {
    }

    /** @return the username without surrounding whitespace; nothing else is changed */
    static String normalizedUsername(String username) {
        return username.strip();
    }

    /** @return the normalized username, if it has 1 to 64 characters */
    static String validUsername(String field, String username) {
        String normalized = normalizedUsername(username);
        int length = characterCount(normalized);
        if (length < 1 || length > USERNAME_MAX_LENGTH) {
            throw new FieldValidationException(field,
                    "The username must have 1 to " + USERNAME_MAX_LENGTH + " characters.");
        }
        return normalized;
    }

    /** The password is not trimmed and has no composition rules. */
    static void requireValidPassword(String field, String password) {
        if (!hasValidPasswordLength(password)) {
            throw new FieldValidationException(field, "The password must have " + PASSWORD_MIN_LENGTH + " to "
                    + PASSWORD_MAX_LENGTH + " characters.");
        }
    }

    static boolean hasValidPasswordLength(String password) {
        int length = characterCount(password);
        return length >= PASSWORD_MIN_LENGTH && length <= PASSWORD_MAX_LENGTH;
    }

    private static int characterCount(String text) {
        return text.codePointCount(0, text.length());
    }
}
