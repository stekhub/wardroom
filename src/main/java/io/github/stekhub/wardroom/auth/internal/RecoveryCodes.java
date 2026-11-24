package io.github.stekhub.wardroom.auth.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * One-time recovery codes for a lost phone (spec 012). A code has 10 characters from a set without characters that
 * are easy to confuse. Codes are long and random, so they are stored as SHA-256 hashes.
 */
final class RecoveryCodes {

    static final int NUMBER_OF_CODES = 10;
    static final int CODE_LENGTH = 10;

    /** No 0 and O, no 1 and I. */
    private static final String CHARACTERS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    private RecoveryCodes() {
    }

    static List<String> generate(SecureRandom random) {
        List<String> codes = new ArrayList<>(NUMBER_OF_CODES);
        for (int code = 0; code < NUMBER_OF_CODES; code++) {
            StringBuilder characters = new StringBuilder(CODE_LENGTH);
            for (int position = 0; position < CODE_LENGTH; position++) {
                characters.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
            }
            codes.add(characters.toString());
        }
        return codes;
    }

    /** @return the code as it was created: without spaces and hyphens, in capital letters */
    static String normalized(String entered) {
        StringBuilder normalized = new StringBuilder(entered.length());
        for (int position = 0; position < entered.length(); position++) {
            char character = entered.charAt(position);
            if (!Character.isWhitespace(character) && character != '-') {
                normalized.append(character);
            }
        }
        return normalized.toString().toUpperCase(Locale.ROOT);
    }

    /** @return the SHA-256 hash of a normalized code, as lowercase hexadecimal digits */
    static String hash(String normalizedCode) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(normalizedCode.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("The Java runtime cannot compute SHA-256", e);
        }
    }
}
