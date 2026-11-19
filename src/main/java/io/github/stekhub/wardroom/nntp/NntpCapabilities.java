package io.github.stekhub.wardroom.nntp;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The answer of a server to {@code CAPABILITIES} (RFC 3977, section 5.2).
 *
 * @param supported whether the server knows the command; older servers do not, and are still usable
 * @param entries   the capability labels in upper case, each with its arguments; empty when not supported
 */
public record NntpCapabilities(boolean supported, Map<String, List<String>> entries) {

    public NntpCapabilities {
        entries = Map.copyOf(entries);
    }

    /** The result for a server that does not know {@code CAPABILITIES}. */
    public static NntpCapabilities unsupported() {
        return new NntpCapabilities(false, Map.of());
    }

    /** @return whether the server lists the capability, for example {@code READER} or {@code POST} */
    public boolean has(String label) {
        return entries.containsKey(label.toUpperCase(Locale.ROOT));
    }

    /** @return the arguments of a capability, for example of {@code LIST}; empty when it is not listed */
    public List<String> arguments(String label) {
        return entries.getOrDefault(label.toUpperCase(Locale.ROOT), List.of());
    }
}
