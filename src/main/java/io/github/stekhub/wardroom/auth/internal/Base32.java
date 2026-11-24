package io.github.stekhub.wardroom.auth.internal;

/** Base32 after RFC 4648 without padding, as authenticator apps expect the secret (spec 012). */
final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {
    }

    static String encode(byte[] bytes) {
        StringBuilder encoded = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsInBuffer = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xFF);
            bitsInBuffer += 8;
            while (bitsInBuffer >= 5) {
                encoded.append(ALPHABET.charAt((buffer >> (bitsInBuffer - 5)) & 0x1F));
                bitsInBuffer -= 5;
            }
            buffer &= (1 << bitsInBuffer) - 1;
        }
        if (bitsInBuffer > 0) {
            encoded.append(ALPHABET.charAt((buffer << (5 - bitsInBuffer)) & 0x1F));
        }
        return encoded.toString();
    }

    /** @throws IllegalArgumentException when the text has a character that is not part of Base32 */
    static byte[] decode(String encoded) {
        byte[] bytes = new byte[encoded.length() * 5 / 8];
        int buffer = 0;
        int bitsInBuffer = 0;
        int length = 0;
        for (int position = 0; position < encoded.length(); position++) {
            int value = ALPHABET.indexOf(encoded.charAt(position));
            if (value < 0) {
                throw new IllegalArgumentException("Not a Base32 text");
            }
            buffer = (buffer << 5) | value;
            bitsInBuffer += 5;
            if (bitsInBuffer >= 8) {
                bytes[length++] = (byte) (buffer >> (bitsInBuffer - 8));
                bitsInBuffer -= 8;
            }
            buffer &= (1 << bitsInBuffer) - 1;
        }
        return bytes;
    }
}
