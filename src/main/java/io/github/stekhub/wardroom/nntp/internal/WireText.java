package io.github.stekhub.wardroom.nntp.internal;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Turns a line of an answer into text. The library reads every byte as one ISO-8859-1 character, so the bytes the
 * server sent can be restored and decoded properly.
 */
public final class WireText {

    private WireText() {
    }

    /**
     * Decodes the line as UTF-8. A line that is not valid UTF-8 is taken as ISO-8859-1 (spec 003).
     *
     * @param line a line as the library read it
     */
    public static String decode(String line) {
        if (line.chars().allMatch(character -> character < 0x80)) {
            return line;
        }
        byte[] bytes = line.getBytes(StandardCharsets.ISO_8859_1);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return line;
        }
    }

    /**
     * The opposite of {@link #decode(String)}, for text that is sent to the server, such as a group name: the
     * library writes every character as one ISO-8859-1 byte, so the UTF-8 bytes are handed to it as characters.
     */
    public static String encode(String text) {
        if (text.chars().allMatch(character -> character < 0x80)) {
            return text;
        }
        return new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }
}
