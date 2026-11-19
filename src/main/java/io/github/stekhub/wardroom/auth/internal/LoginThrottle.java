package io.github.stekhub.wardroom.auth.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Slows down repeated failed logins (spec 001).
 *
 * <p>After 5 failed logins in a row, the next attempt must wait 1 second. Every further failure doubles the waiting
 * time, up to 60 seconds. A successful login resets the counter. The state is held in memory only, so a restart
 * resets it too. There is one counter for the instance, not one per client address.
 */
@Component
class LoginThrottle {

    static final int FAILURES_WITHOUT_WAITING = 5;
    static final Duration FIRST_WAIT = Duration.ofSeconds(1);
    static final Duration LONGEST_WAIT = Duration.ofSeconds(60);

    private final InstantSource clock;
    private int consecutiveFailures;
    private Instant blockedUntil = Instant.MIN;

    @Autowired
    LoginThrottle() {
        this(InstantSource.system());
    }

    LoginThrottle(InstantSource clock) {
        this.clock = clock;
    }

    /** @return how long the next attempt still has to wait; zero when it may run now */
    synchronized Duration remainingWait() {
        Instant now = clock.instant();
        return now.isBefore(blockedUntil) ? Duration.between(now, blockedUntil) : Duration.ZERO;
    }

    synchronized void recordFailure() {
        consecutiveFailures++;
        if (consecutiveFailures < FAILURES_WITHOUT_WAITING) {
            return;
        }
        Duration wait = FIRST_WAIT;
        for (int failure = FAILURES_WITHOUT_WAITING; failure < consecutiveFailures; failure++) {
            wait = wait.multipliedBy(2);
            if (wait.compareTo(LONGEST_WAIT) >= 0) {
                wait = LONGEST_WAIT;
                break;
            }
        }
        blockedUntil = clock.instant().plus(wait);
    }

    synchronized void recordSuccess() {
        consecutiveFailures = 0;
        blockedUntil = Instant.MIN;
    }
}
