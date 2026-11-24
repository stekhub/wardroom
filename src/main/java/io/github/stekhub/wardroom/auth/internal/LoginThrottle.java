package io.github.stekhub.wardroom.auth.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Slows down repeated failed logins to an account (spec 001, spec 011).
 *
 * <p>After 5 failed logins in a row, the next attempt must wait 1 second. Every further failure doubles the waiting
 * time, up to 60 seconds. A successful login resets the counter. The counter belongs to the account: only attempts
 * with its username count and wait. An attempt with a username that no account has can never succeed, so it is
 * neither counted nor delayed, and nobody can hold up the login of an account without knowing its username. Wrong
 * codes of the second factor in the settings count too, so the codes cannot be tried out there.
 *
 * <p>The state is stored with the account, so a restart does not reset it.
 */
@Component
class LoginThrottle {

    static final int FAILURES_WITHOUT_WAITING = 5;
    static final Duration FIRST_WAIT = Duration.ofSeconds(1);
    static final Duration LONGEST_WAIT = Duration.ofSeconds(60);

    private final InstantSource clock;
    private final LoginThrottleStore store;
    private final ReentrantLock accountAttemptLock = new ReentrantLock();
    private final ReentrantLock unknownUsernameAttemptLock = new ReentrantLock();

    LoginThrottle(InstantSource clock, LoginThrottleStore store) {
        this.clock = clock;
        this.store = store;
    }

    /**
     * Runs an attempt, from asking for the waiting time to recording its result, while no other attempt of its kind
     * runs. Parallel attempts then cannot get around the throttling.
     *
     * <p>Attempts with a username that no account has wait for each other only, never for the account's. They are
     * not throttled, so this keeps them from computing many password hashes at once and from standing in the way of
     * the owner's login.
     */
    <T> T oneAtATime(String username, Supplier<T> attempt) {
        ReentrantLock attemptLock = store.findLoginThrottleState(username).isPresent()
                ? accountAttemptLock
                : unknownUsernameAttemptLock;
        attemptLock.lock();
        try {
            return attempt.get();
        } finally {
            attemptLock.unlock();
        }
    }

    /** @return how long the next attempt with this username still has to wait; zero when it may run now */
    synchronized Duration remainingWait(String username) {
        Instant blockedUntil = store.findLoginThrottleState(username)
                .map(LoginThrottleState::blockedUntil)
                .orElse(null);
        Instant now = clock.instant();
        return blockedUntil != null && now.isBefore(blockedUntil) ? Duration.between(now, blockedUntil)
                : Duration.ZERO;
    }

    /**
     * @return the waiting time that starts with this failure; zero when the next attempt may run at once, and
     *         always for a username that no account has
     */
    synchronized Duration recordFailure(String username) {
        LoginThrottleState state = store.findLoginThrottleState(username).orElse(null);
        if (state == null) {
            return Duration.ZERO;
        }
        int failedLogins = state.failedLogins() + 1;
        if (failedLogins < FAILURES_WITHOUT_WAITING) {
            store.storeLoginThrottleState(username, new LoginThrottleState(failedLogins, null));
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
        store.storeLoginThrottleState(username, new LoginThrottleState(failedLogins, clock.instant().plus(wait)));
        return wait;
    }

    synchronized void recordSuccess(String username) {
        store.storeLoginThrottleState(username, LoginThrottleState.NO_FAILURES);
    }
}
