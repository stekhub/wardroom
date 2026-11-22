package io.github.stekhub.wardroom.posting.internal;

import io.github.stekhub.wardroom.nntp.NntpHeaderText;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.james.mime4j.codec.EncoderUtil;
import org.apache.james.mime4j.util.MimeUtil;

/**
 * Builds the article that is sent for a post of the owner (spec 009, "The generated article"). It has no database
 * access and talks to no server.
 *
 * <p>Everything that goes into a header is cleaned of line breaks and other control characters first, so a value
 * cannot add a header.
 */
final class ArticleComposer {

    /** The longest header line a server has to accept, without its line ending (RFC 5322). */
    static final int MAX_HEADER_LINE_LENGTH = 998;

    /** How many entries of {@code References} are kept at its end when it is too long (RFC 5537). */
    private static final int KEPT_LAST_REFERENCES = 3;

    private static final int MESSAGE_ID_RANDOM_LENGTH = 20;
    private static final String MESSAGE_ID_CHARACTERS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final Pattern MESSAGE_ID_TOKEN = Pattern.compile("<[^<>\\s]+>");
    private static final Pattern NEWSGROUP_NAME = Pattern.compile("[A-Za-z0-9+_-]+(\\.[A-Za-z0-9+_-]+)*");
    private static final String POSTER = "poster";
    private static final DateTimeFormatter RFC_5322_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss '+0000'", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final SecureRandom RANDOM = new SecureRandom();

    private ArticleComposer() {
    }

    /**
     * What an article is built from.
     *
     * @param name               the display name of the posting identity
     * @param email              the email address of the posting identity, of the form {@code local@domain}
     * @param newsgroups         the groups the article is posted to
     * @param subject            the subject as the owner wrote it
     * @param messageId          the Message-ID with its angle brackets
     * @param date               when the article is sent
     * @param references         the value of {@code References}, or {@code null} for a new discussion
     * @param body               the text as the owner wrote it, with the signature when one is sent
     * @param applicationVersion the version of Wardroom
     */
    record Parts(String name, String email, List<String> newsgroups, String subject, String messageId, Instant date,
                 String references, String body, String applicationVersion) {
    }

    /**
     * An article as it is sent and as it is stored.
     *
     * @param text    the header lines, an empty line and the body, with CRLF line endings
     * @param headers the header lines alone, with CRLF line endings
     * @param body    the body alone, in {@code format=flowed}, with CRLF line endings
     * @param from    the value of {@code From} as a reader decodes it
     * @param subject the value of {@code Subject} as a reader decodes it
     * @param lines   the number of lines of the body
     */
    record Composed(String text, String headers, String body, String from, String subject, long lines) {

        /** @return the size of the article as it is sent, in bytes */
        long bytes() {
            return text.getBytes(StandardCharsets.UTF_8).length;
        }
    }

    static Composed compose(Parts parts) {
        String from = EncoderUtil.encodeAddressDisplayName(cleaned(parts.name())) + " <" + cleaned(parts.email())
                + ">";
        String subject = EncoderUtil.encodeIfNecessary(cleaned(parts.subject()), EncoderUtil.Usage.TEXT_TOKEN,
                "Subject: ".length());
        List<String> headerLines = new ArrayList<>();
        headerLines.add(folded("From: " + from));
        headerLines.add("Newsgroups: " + String.join(",", parts.newsgroups()));
        headerLines.add(folded("Subject: " + subject));
        headerLines.add("Message-ID: " + parts.messageId());
        headerLines.add("Date: " + RFC_5322_DATE.format(parts.date()));
        if (parts.references() != null) {
            headerLines.add(folded("References: " + parts.references()));
        }
        headerLines.add("MIME-Version: 1.0");
        headerLines.add("Content-Type: text/plain; charset=UTF-8; format=flowed");
        headerLines.add("Content-Transfer-Encoding: 8bit");
        headerLines.add("User-Agent: Wardroom/" + cleaned(parts.applicationVersion()));

        List<String> bodyLines = FlowedText.encode(parts.body());
        String headers = String.join("\r\n", headerLines) + "\r\n";
        String body = String.join("\r\n", bodyLines) + "\r\n";
        return new Composed(headers + "\r\n" + body, headers, body, NntpHeaderText.decode(from),
                NntpHeaderText.decode(subject), bodyLines.size());
    }

    /** @return a new Message-ID for an article of the owner: {@code <{20 random characters}@{domain}>} */
    static String newMessageId(String email) {
        StringBuilder random = new StringBuilder(MESSAGE_ID_RANDOM_LENGTH);
        for (int position = 0; position < MESSAGE_ID_RANDOM_LENGTH; position++) {
            random.append(MESSAGE_ID_CHARACTERS.charAt(RANDOM.nextInt(MESSAGE_ID_CHARACTERS.length())));
        }
        return "<" + random + "@" + domainOf(email) + ">";
    }

    /** @return whether the Message-ID has the form Wardroom generates for this email address */
    static boolean isOwnMessageId(String messageId, String email) {
        String suffix = "@" + domainOf(email) + ">";
        if (messageId == null || !messageId.startsWith("<") || !messageId.endsWith(suffix)
                || messageId.length() != 1 + MESSAGE_ID_RANDOM_LENGTH + suffix.length()) {
            return false;
        }
        return messageId.substring(1, 1 + MESSAGE_ID_RANDOM_LENGTH).chars()
                .allMatch(character -> MESSAGE_ID_CHARACTERS.indexOf(character) >= 0);
    }

    private static String domainOf(String email) {
        return email.substring(email.lastIndexOf('@') + 1);
    }

    /**
     * The {@code References} of a reply: those of the parent, followed by the Message-ID of the parent. When the
     * header line would be longer than 998 characters, entries are dropped from the middle; the first one and the
     * last three stay (RFC 5537).
     *
     * @param parentReferences the {@code References} of the parent as stored, or {@code null}; untrusted text
     * @param parentMessageId  the Message-ID of the parent
     */
    static String references(String parentReferences, String parentMessageId) {
        List<String> messageIds = new ArrayList<>();
        Matcher token = MESSAGE_ID_TOKEN.matcher(parentReferences == null ? "" : parentReferences);
        while (token.find()) {
            messageIds.add(token.group());
        }
        messageIds.add(parentMessageId);
        while (("References: " + String.join(" ", messageIds)).length() > MAX_HEADER_LINE_LENGTH
                && messageIds.size() > 1 + KEPT_LAST_REFERENCES) {
            messageIds.remove(1);
        }
        return String.join(" ", messageIds);
    }

    /**
     * The groups a reply is posted to (spec 009, "Target groups"): those of {@code Followup-To} of the parent,
     * otherwise those of its {@code Newsgroups}. Entries that are not group names are dropped.
     *
     * @param followupTo the value of the header of the parent, or {@code null}; untrusted text
     * @param newsgroups the value of the header of the parent, or {@code null}; untrusted text
     * @return the groups, without repetitions; empty when the parent names none that is valid
     */
    static List<String> targetGroups(String followupTo, String newsgroups) {
        List<String> ofFollowupTo = groupNames(followupTo);
        return ofFollowupTo.isEmpty() ? groupNames(newsgroups) : ofFollowupTo;
    }

    /** @return whether the author asked for replies by email, with {@code Followup-To: poster} */
    static boolean repliesByEmailOnly(String followupTo) {
        return followupTo != null && followupTo.strip().equalsIgnoreCase(POSTER);
    }

    private static List<String> groupNames(String headerValue) {
        List<String> groupNames = new ArrayList<>();
        if (headerValue == null) {
            return groupNames;
        }
        for (String entry : headerValue.split(",")) {
            String groupName = entry.strip();
            if (NEWSGROUP_NAME.matcher(groupName).matches() && !groupName.equalsIgnoreCase(POSTER)
                    && !groupNames.contains(groupName)) {
                groupNames.add(groupName);
            }
        }
        return groupNames;
    }

    /** Removes line breaks and other control characters, and the spaces around what is left. */
    static String cleaned(String value) {
        StringBuilder cleaned = new StringBuilder(value.length());
        value.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint)
                        // The separators of lines and paragraphs break a line as well.
                        && codePoint != 0x2028 && codePoint != 0x2029)
                .forEach(cleaned::appendCodePoint);
        return cleaned.toString().strip();
    }

    /** Folds a header line at its spaces, so that no line of it is longer than a server has to accept. */
    private static String folded(String headerLine) {
        return MimeUtil.fold(headerLine, 0);
    }
}
