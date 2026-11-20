package io.github.stekhub.wardroom.articles.internal;

import io.github.stekhub.wardroom.nntp.NntpHeaderText;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.james.mime4j.MimeException;
import org.apache.james.mime4j.codec.DecodeMonitor;
import org.apache.james.mime4j.message.DefaultBodyDescriptorBuilder;
import org.apache.james.mime4j.message.MaximalBodyDescriptor;
import org.apache.james.mime4j.parser.AbstractContentHandler;
import org.apache.james.mime4j.parser.MimeStreamParser;
import org.apache.james.mime4j.stream.BodyDescriptor;
import org.apache.james.mime4j.stream.MimeConfig;

/**
 * Takes the displayed text and the header values out of a fetched article (spec 007; ADR 0014).
 *
 * <ul>
 * <li>The text is the single-part body or the first {@code text/plain} part. Of everything else only name, media
 * type and size are kept.</li>
 * <li>The article is untrusted. Nothing in it can make the extraction fail: an article that cannot be parsed as
 * MIME is taken as plain text, and processing stops at the limits.</li>
 * </ul>
 */
final class ArticleContentExtractor {

    /** The stored text is at most this long, in UTF-8 bytes. */
    static final int MAX_TEXT_BYTES = 1_048_576;

    /** Parts after this many are not processed. */
    static final int MAX_PARTS = 100;

    /** Parts nested deeper than this are not processed. */
    static final int MAX_NESTING_DEPTH = 10;

    private static final int NAME_MAX_LENGTH = 200;
    private static final int MEDIA_TYPE_MAX_LENGTH = 100;
    private static final String PLAIN_TEXT = "text/plain";
    private static final String BINARY = "application/octet-stream";
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final byte[] HEADER_END = {'\r', '\n', '\r', '\n'};

    private ArticleContentExtractor() {
    }

    /**
     * A part of an article that is not displayed.
     *
     * @param name      the file name the article gives, or {@code null}; text only, never used as a path
     * @param mediaType the media type in lower case
     * @param size      the size of the decoded part in bytes
     */
    record OmittedPart(String name, String mediaType, long size) {
    }

    /**
     * What a fetched article says about itself, to fill in a stub. Each value is {@code null} when the article has
     * no such header or it cannot be read.
     */
    record ArticleHeaders(String subject, String from, Long date, String references, String inReplyTo, Long lines) {
    }

    /**
     * @param rawHeaders   the header block as received
     * @param body         the displayed text with {@code \n} line endings; empty when the article has no plain
     *                     text
     * @param flowed       whether the text is {@code format=flowed} (RFC 3676)
     * @param delSp        whether the flowed text was sent with {@code DelSp=yes}
     * @param truncated    whether a limit stopped the processing
     * @param omittedParts the parts that are not displayed, in the order of the article
     */
    record ExtractedContent(String rawHeaders, String body, boolean flowed, boolean delSp, boolean truncated,
                            List<OmittedPart> omittedParts, ArticleHeaders headers) {
    }

    /**
     * @param article  the article as the server sent it: header block, empty line, body
     * @param complete {@code false} when the article was cut off at the size limit
     */
    static ExtractedContent extract(byte[] article, boolean complete) {
        int headerEnd = indexOf(article, HEADER_END);
        byte[] headerBytes = headerEnd < 0 ? article : Arrays.copyOf(article, headerEnd);
        byte[] bodyBytes = headerEnd < 0
                ? new byte[0]
                : Arrays.copyOfRange(article, headerEnd + HEADER_END.length, article.length);
        // Every byte as one character, so that the header values can be decoded like those of an overview.
        String headerBlock = new String(headerBytes, StandardCharsets.ISO_8859_1);

        PartCollector collector = new PartCollector();
        boolean parsed = parse(article, collector);
        String text;
        if (collector.textFound) {
            text = collector.text;
        } else if (parsed || !collector.omittedParts.isEmpty()) {
            text = "";
        } else {
            // The MIME structure cannot be read: the body is taken as plain text.
            text = decode(bodyBytes, null);
        }

        List<OmittedPart> omittedParts = new ArrayList<>(collector.omittedParts);
        text = EmbeddedBinaries.remove(normalize(text), omittedParts);
        String body = cutToLimit(text);
        boolean truncated = !complete || collector.limitReached || !parsed && collector.textFound
                || body.length() < text.length();
        return new ExtractedContent(NntpHeaderText.decode(headerBytes).stripTrailing(), body.stripTrailing(),
                collector.flowed, collector.delSp, truncated, List.copyOf(omittedParts), headersOf(headerBlock));
    }

    /** @return whether the whole article was parsed; {@code false} when its MIME structure is broken */
    private static boolean parse(byte[] article, PartCollector collector) {
        MimeConfig mimeConfig = MimeConfig.custom()
                .setMaxLineLen(-1)
                .setMaxHeaderLen(-1)
                .setMaxHeaderCount(-1)
                .setMaxContentLen(-1)
                .build();
        MimeStreamParser parser = new MimeStreamParser(mimeConfig, DecodeMonitor.SILENT,
                new DefaultBodyDescriptorBuilder());
        // Base64 and quoted-printable are undone by the parser.
        parser.setContentDecoding(true);
        parser.setContentHandler(collector);
        collector.parser = parser;
        try {
            parser.parse(new ByteArrayInputStream(article));
            return true;
        } catch (MimeException | IOException | RuntimeException e) {
            return false;
        }
    }

    /** Receives the parts of an article in their order and keeps the first plain text. */
    private static final class PartCollector extends AbstractContentHandler {

        private final List<OmittedPart> omittedParts = new ArrayList<>();
        private MimeStreamParser parser;
        private int partCount;
        private int nestingDepth;
        private boolean limitReached;
        private boolean textFound;
        private String text = "";
        private boolean flowed;
        private boolean delSp;

        @Override
        public void startMultipart(BodyDescriptor bodyDescriptor) {
            enter();
        }

        @Override
        public void endMultipart() {
            nestingDepth--;
        }

        @Override
        public void startMessage() {
            // The article itself is a message too; a message inside it is one level deeper.
            enter();
        }

        @Override
        public void endMessage() {
            nestingDepth--;
        }

        private void enter() {
            nestingDepth++;
            // The article and its multipart are two levels that every MIME article has.
            if (nestingDepth > MAX_NESTING_DEPTH + 1) {
                stop();
            }
        }

        @Override
        public void body(BodyDescriptor bodyDescriptor, InputStream content) throws IOException {
            if (limitReached) {
                return;
            }
            partCount++;
            if (partCount > MAX_PARTS) {
                stop();
                return;
            }
            String mediaType = mediaTypeOf(bodyDescriptor);
            Map<String, String> parameters = bodyDescriptor instanceof MaximalBodyDescriptor maximalBodyDescriptor
                    ? maximalBodyDescriptor.getContentTypeParameters()
                    : Map.of();
            if (!textFound && PLAIN_TEXT.equals(mediaType)) {
                text = decode(content.readAllBytes(), parameters.get("charset"));
                flowed = "flowed".equalsIgnoreCase(parameters.get("format"));
                delSp = flowed && "yes".equalsIgnoreCase(parameters.get("delsp"));
                textFound = true;
                return;
            }
            // The content is counted and thrown away; it is never kept (ADR 0014).
            long size = content.transferTo(java.io.OutputStream.nullOutputStream());
            omittedParts.add(new OmittedPart(nameOf(bodyDescriptor, parameters), mediaType, size));
        }

        private void stop() {
            limitReached = true;
            parser.stop();
        }
    }

    private static String mediaTypeOf(BodyDescriptor bodyDescriptor) {
        String mediaType = bodyDescriptor.getMimeType();
        if (mediaType == null || mediaType.isBlank()) {
            return BINARY;
        }
        return cleanText(mediaType.toLowerCase(Locale.ROOT), MEDIA_TYPE_MAX_LENGTH);
    }

    private static String nameOf(BodyDescriptor bodyDescriptor, Map<String, String> parameters) {
        String name = bodyDescriptor instanceof MaximalBodyDescriptor maximalBodyDescriptor
                ? maximalBodyDescriptor.getContentDispositionFilename()
                : null;
        if (name == null || name.isBlank()) {
            name = parameters.get("name");
        }
        if (name == null || name.isBlank()) {
            return null;
        }
        return cleanText(NntpHeaderText.decode(name), NAME_MAX_LENGTH);
    }

    /** A name or media type is shown as text. Control characters are removed, and it is cut to a length. */
    private static String cleanText(String text, int maxLength) {
        String cleaned = text.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString()
                .strip();
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    /**
     * Turns the bytes of a text into characters. A declared character set that Java knows is used; invalid byte
     * sequences are replaced. Without one, the text is taken as UTF-8, and as windows-1252 when it is not valid
     * UTF-8. {@code us-ascii} counts as not declared: text that claims it often has other characters.
     */
    static String decode(byte[] bytes, String declaredCharset) {
        Charset charset = charsetOf(declaredCharset);
        if (charset != null) {
            return new String(bytes, charset);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, WINDOWS_1252);
        }
    }

    private static Charset charsetOf(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            Charset charset = Charset.forName(name.strip());
            return StandardCharsets.US_ASCII.equals(charset) ? null : charset;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Line endings become {@code \n}. A NUL character cannot be stored and says nothing. */
    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "");
    }

    /** @return the text, cut off before the character that would make it longer than the limit in UTF-8 */
    private static String cutToLimit(String text) {
        if (text.length() * 3L <= MAX_TEXT_BYTES) {
            return text;
        }
        int bytes = 0;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            int codePointBytes = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
            if (bytes + codePointBytes > MAX_TEXT_BYTES) {
                return text.substring(0, index);
            }
            bytes += codePointBytes;
            index += Character.charCount(codePoint);
        }
        return text;
    }

    private static ArticleHeaders headersOf(String headerBlock) {
        String references = RawHeaders.value(headerBlock, "References");
        String inReplyTo = RawHeaders.value(headerBlock, "In-Reply-To");
        return new ArticleHeaders(
                decodedOrNull(RawHeaders.value(headerBlock, "Subject")),
                decodedOrNull(RawHeaders.value(headerBlock, "From")),
                dateOrNull(RawHeaders.value(headerBlock, "Date")),
                references == null || references.isBlank() ? null : references.strip(),
                inReplyTo == null || inReplyTo.isBlank() ? null : inReplyTo.strip(),
                numberOrNull(RawHeaders.value(headerBlock, "Lines")));
    }

    private static String decodedOrNull(String rawValue) {
        return rawValue == null ? null : NntpHeaderText.decode(rawValue);
    }

    private static Long dateOrNull(String rawValue) {
        return rawValue == null ? null : NntpHeaderText.parseDate(rawValue);
    }

    private static Long numberOrNull(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String digits = rawValue.strip();
        if (digits.isEmpty() || digits.length() > 18 || !digits.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return Long.parseLong(digits);
    }

    private static int indexOf(byte[] bytes, byte[] sequence) {
        for (int start = 0; start <= bytes.length - sequence.length; start++) {
            int matched = 0;
            while (matched < sequence.length && bytes[start + matched] == sequence[matched]) {
                matched++;
            }
            if (matched == sequence.length) {
                return start;
            }
        }
        return -1;
    }
}
