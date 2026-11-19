package io.github.stekhub.wardroom.platform;

/**
 * The stable identifiers of protocol anomalies (ADR 0029). This is the one place where they are defined.
 *
 * <p>An identifier is never renamed or reused, because logs are searched for it. A new case gets a new constant.
 */
public enum ProtocolAnomalyKind {

    /** A line of an {@code OVER} or {@code XOVER} response that cannot be split into the expected fields. */
    OVERVIEW_MALFORMED_LINE("overview.malformed-line");

    private final String id;

    ProtocolAnomalyKind(String id) {
        this.id = id;
    }

    /** @return the identifier written to the log as {@code kind} */
    public String id() {
        return id;
    }
}
