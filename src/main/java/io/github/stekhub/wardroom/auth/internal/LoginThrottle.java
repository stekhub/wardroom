package io.github.stekhub.wardroom.auth.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Slows down repeated failed logins (spec 001, spec 011).
 *
 * <p>After 5 failed logins in a row, the next attempt must wait 1 second. Every further failure doubles the waiting
 * time, up to 60 seconds. A successful login resets the counter. There is one counter for the instance, not one per
 * client address or per entered username. Wrong codes of the second factor in the settings count too, so the codes
 * cannot be tried out there.
 *
 * <p>The state is stored with the account, so a restart does not reset it. While no account exists there is nothing
 * to log in to, and the state is held in memory.
 */
@Component
class LoginThrottle {

    static final int FAILURES_WITHOUT_WAITING = 5;
    static final Duration FIRST_WAIT = Duration.ofSeconds(1);
    static final Duration LONGEST_WAIT = Duration.ofSeconds(60);

    private final InstantSource clock;
    private final LoginThrottleStore store;
    private LoginThrottleState stateWithoutAccount = LoginThrottleState.NO_FAILURES;
    private final ReentrantLock attemptLock = new ReentrantLock();

    LoginThrottle(InstantSource clock, LoginThrottleStore store) {
        this.clock = clock;
        this.store = store;
    }

    /**
     * Runs an attempt, from asking for the waiting time to recording its result, while no other attempt runs.
     * Parallel attempts then cannot get around the throttling.
     */
    <T> T oneAtATime(Supplier<T> attempt) {
        attemptLock.lock();
        try {
            return attempt.get();
        } finally {
            attemptLock.unlock();
        }
    }

    /** @return how long the next attempt still has to wait; zero when it may run now */
    synchronized Duration remainingWait() {
        Instant blockedUntil = state().blockedUntil();
        Instant now = clock.instant();
        return blockedUntil != null && now.isBefore(blockedUntil) ? Duration.between(now, blockedUntil)
                : Duration.ZERO;
    }

    /** @return the waiting time that starts with this failure; zero when the next attempt may run at once */
    synchronized Duration recordFailure() {
        int failedLogins = state().failedLogins() + 1;
        if (failedLogins < FAILURES_WITHOUT_WAITING) {
            save(new LoginThrottleState(failedLogins, null));
            return Duration.ZERO;
        }
        Duration wait = FIRST_WAIT;
        for (int failure = FAILURES_WITHOUT_WAITING; failure < failedLogins; failure++) {
            wait = wait.multipliedBy(2);
            if (wait.compareTo(LONGEST_WAIT) >= 0) {
                wait = LONGEST_WAIT;
                break;
            }
        }
        save(new LoginThrottleState(failedLogins, clock.instant().plus(wait)));
        return wait;
    }

    synchronized void recordSuccess() {
        save(LoginThrottleState.NO_FAILURES);
    }

    private LoginThrottleState state() {
        return store.findLoginThrottleState().orElse(stateWithoutAccount);
    }

    private void save(LoginThrottleState state) {
        stateWithoutAccount = store.storeLoginThrottleState(state) ? LoginThrottleState.NO_FAILURES : state;
    }
}
