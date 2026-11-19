package io.github.stekhub.wardroom.platform;

import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;

/**
 * Writes the {@code PROTOCOL_ANOMALY} lines of one run, for example one synchronization of one group (ADR 0029).
 *
 * <p>Create one instance per run, {@link #report(ProtocolAnomaly) report} every anomaly and call {@link #finish()}
 * when the run ends. The first {@value #FULL_LINES_PER_KIND} anomalies of a kind are logged in full; further ones are
 * counted and summarized by {@link #finish()}. With {@code DEBUG} enabled every anomaly is logged in full.
 */
public final class ProtocolAnomalyLog {

    /** Every anomaly line starts with this marker, so logs can be searched for it. */
    public static final String MARKER = "PROTOCOL_ANOMALY";

    static final int FULL_LINES_PER_KIND = 5;
    static final int RAW_MAX_LENGTH = 500;

    private final Logger log;
    private final Map<ProtocolAnomalyKind, Integer> totals = new EnumMap<>(ProtocolAnomalyKind.class);
    private final Map<ProtocolAnomalyKind, Integer> logged = new EnumMap<>(ProtocolAnomalyKind.class);

    public ProtocolAnomalyLog(Logger log) {
        this.log = log;
    }

    public synchronized void report(ProtocolAnomaly anomaly) {
        int total = totals.merge(anomaly.kind(), 1, Integer::sum);
        if (total <= FULL_LINES_PER_KIND || log.isDebugEnabled()) {
            logged.merge(anomaly.kind(), 1, Integer::sum);
            log.warn(format(anomaly));
        }
    }

    /** Writes one summary line for every kind of which not all anomalies were logged in full, and starts over. */
    public synchronized void finish() {
        totals.forEach((kind, total) -> {
            int full = logged.getOrDefault(kind, 0);
            if (total > full) {
                log.warn("{} kind={} summary=true total={} logged={}", MARKER, kind.id(), total, full);
            }
        });
        totals.clear();
        logged.clear();
    }

    static String format(ProtocolAnomaly anomaly) {
        StringBuilder line = new StringBuilder(MARKER);
        append(line, "kind", anomaly.kind().id());
        append(line, "command", anomaly.command());
        append(line, "group", anomaly.group());
        append(line, "number", anomaly.number() == null ? null : anomaly.number().toString());
        append(line, "messageId", anomaly.messageId());
        append(line, "action", anomaly.action());
        if (anomaly.raw() != null) {
            line.append(" raw=").append(quote(LogSafe.escape(anomaly.raw(), RAW_MAX_LENGTH)));
        }
        return line.toString();
    }

    private static void append(StringBuilder line, String key, String value) {
        if (value == null) {
            return;
        }
        String escaped = LogSafe.escape(value);
        boolean needsQuotes = escaped.isEmpty() || escaped.chars().anyMatch(c -> c == ' ' || c == '"' || c == '=');
        line.append(' ').append(key).append('=').append(needsQuotes ? quote(escaped) : escaped);
    }

    /** The text is already escaped, so a backslash in it is doubled; only the quote itself is left to escape. */
    private static String quote(String escaped) {
        return '"' + escaped.replace("\"", "\\\"") + '"';
    }
}
