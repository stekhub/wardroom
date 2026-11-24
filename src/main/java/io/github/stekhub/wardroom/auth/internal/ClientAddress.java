package io.github.stekhub.wardroom.auth.internal;

import io.github.stekhub.wardroom.platform.LogSafe;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Where a login came from, for the log (spec 011).
 *
 * @param connectionAddress the address the connection came from
 * @param forwardedFor      the value of {@code X-Forwarded-For}, or {@code null} when the request has none. Anyone
 *                          who reaches Wardroom directly can forge it, so it is logged as what the request claims.
 */
record ClientAddress(String connectionAddress, String forwardedFor) {

    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
    private static final int LONGEST_LOGGED_VALUE = 200;

    static ClientAddress of(HttpServletRequest request) {
        return new ClientAddress(request.getRemoteAddr(), request.getHeader(FORWARDED_FOR_HEADER));
    }

    /** @return the text for a log line; both values are untrusted and escaped */
    String forLog() {
        String address = LogSafe.escape(connectionAddress, LONGEST_LOGGED_VALUE);
        return forwardedFor == null ? address
                : address + " (X-Forwarded-For: " + LogSafe.escape(forwardedFor, LONGEST_LOGGED_VALUE) + ")";
    }
}
