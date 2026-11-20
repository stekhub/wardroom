package io.github.stekhub.wardroom.platform;

/**
 * The stable identifiers of protocol anomalies (ADR 0029). This is the one place where they are defined.
 *
 * <p>An identifier is never renamed or reused, because logs are searched for it. A new case gets a new constant.
 */
public enum ProtocolAnomalyKind {

    /** A line of a {@code LIST ACTIVE} response that is not a group name, two numbers and a status. */
    LIST_ACTIVE_MALFORMED_LINE("list.malformed-active-line"),

    /** A line of a {@code LIST NEWSGROUPS} response that does not start with a group name. */
    LIST_NEWSGROUPS_MALFORMED_LINE("list.malformed-newsgroups-line"),

    /**
     * A description in a {@code LIST NEWSGROUPS} response that is the rest of a {@code LIST ACTIVE} line: two numbers
     * and a status.
     */
    LIST_NEWSGROUPS_MALFORMED_DESCRIPTION("list.malformed-description"),

    /** A line of an {@code OVER} or {@code XOVER} response that cannot be split into the expected fields. */
    OVERVIEW_MALFORMED_LINE("overview.malformed-line"),

    /** A line of an {@code OVER} or {@code XOVER} response whose Message-ID field is empty. */
    OVERVIEW_MISSING_MESSAGE_ID("overview.missing-message-id"),

    /** A {@code LIST OVERVIEW.FMT} response that does not name all fields Wardroom reads. */
    OVERVIEW_UNUSABLE_FORMAT("overview.unusable-format"),

    /** A {@code 211} response to {@code GROUP} without the article count and the two watermarks. */
    GROUP_MALFORMED_RESPONSE("group.malformed-response"),

    /** A status line that does not start with a three-digit response code. */
    RESPONSE_MALFORMED_LINE("response.malformed-line"),

    /** A well-formed status line with a response code that the protocol does not allow for the command. */
    RESPONSE_UNEXPECTED_CODE("response.unexpected-code");

    private final String id;

    ProtocolAnomalyKind(String id) {
        this.id = id;
    }

    /** @return the identifier written to the log as {@code kind} */
    public String id() {
        return id;
    }
}
