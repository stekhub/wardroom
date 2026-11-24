package io.github.stekhub.wardroom.auth.internal;

import java.time.Instant;

/**
 * What the login throttling remembers (spec 011).
 *
 * @param failedLogins how many logins failed in a row
 * @param blockedUntil when the waiting time ends, or {@code null} when no waiting time was started
 */
record LoginThrottleState(int failedLogins, Instant blockedUntil) {

    static final LoginThrottleState NO_FAILURES = new LoginThrottleState(0, null);
}
