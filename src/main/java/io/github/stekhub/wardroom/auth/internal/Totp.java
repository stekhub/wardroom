package io.github.stekhub.wardroom.auth.internal;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords after RFC 6238, with HMAC-SHA1 and periods of 30 seconds, as every common
 * authenticator app supports them (spec 012, ADR 0035).
 */
final class Totp {

    static final int PERIOD_SECONDS = 30;
    static final int DIGITS = 6;

    private static final String ALGORITHM = "HmacSHA1";

    private Totp() {
    }

    /** @return the number of the 30-second period that contains the time */
    static long period(Instant time) {
        return Math.floorDiv(time.getEpochSecond(), PERIOD_SECONDS);
    }

    /** @return the 6-digit code of the period, with leading zeros */
    static String code(byte[] secret, long period) {
        return code(secret, period, DIGITS);
    }

    /** @return the code of the period with the given number of digits, with leading zeros */
    static String code(byte[] secret, long period, int digits) {
        byte[] hash;
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(period).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The Java runtime cannot compute HMAC-SHA1", e);
        }
        int offset = hash[hash.length - 1] & 0x0F;
        long truncated = ((hash[offset] & 0x7FL) << 24) | ((hash[offset + 1] & 0xFFL) << 16)
                | ((hash[offset + 2] & 0xFFL) << 8) | (hash[offset + 3] & 0xFFL);
        long modulus = 1;
        for (int digit = 0; digit < digits; digit++) {
            modulus *= 10;
        }
        String code = Long.toString(truncated % modulus);
        return "0".repeat(digits - code.length()) + code;
    }
}
