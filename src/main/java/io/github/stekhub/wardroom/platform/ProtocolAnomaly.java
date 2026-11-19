package io.github.stekhub.wardroom.platform;

import java.util.Objects;
import lombok.Builder;

/**
 * Something a server or an article sent that Wardroom cannot process as the RFCs describe (ADR 0029).
 *
 * @param kind      the stable identifier of the case
 * @param command   the NNTP command that was running, without credentials; may be {@code null}
 * @param group     the newsgroup, where known
 * @param number    the article number in the group, where known
 * @param messageId the Message-ID, where known
 * @param action    what Wardroom did, for example {@code skipped}
 * @param raw       the offending line or header; never article body text
 */
@Builder
public record ProtocolAnomaly(ProtocolAnomalyKind kind, String command, String group, Long number, String messageId,
                              String action, String raw) {

    public ProtocolAnomaly {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(action, "action");
    }
}
