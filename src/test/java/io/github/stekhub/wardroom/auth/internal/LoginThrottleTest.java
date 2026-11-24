package io.github.stekhub.wardroom.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {

    private static final String USERNAME = "owner";

    private Instant now = Instant.parse("2026-11-19T10:00:00Z");
    private final InstantSource clock = () -> now;
    private final AccountRow accountRow = new AccountRow();
    private final LoginThrottle loginThrottle = new LoginThrottle(clock, accountRow);

    @Test
    void firstFourFailuresDoNotCauseWaiting() {
        for (int failure = 1; failure <= 4; failure++) {
            assertThat(loginThrottle.recordFailure(USERNAME)).as("wait started by failure %d", failure).isZero();
            assertThat(loginThrottle.remainingWait(USERNAME)).as("after failure %d", failure).isZero();
        }
    }

    @Test
    void waitingStartsWithOneSecondAfterTheFifthFailureAndDoublesUpToSixtySeconds() {
        fail(4);
        List<Long> waitsInSeconds = new ArrayList<>();
        for (int failure = 5; failure <= 13; failure++) {
            Duration startedWait = loginThrottle.recordFailure(USERNAME);
            Duration wait = loginThrottle.remainingWait(USERNAME);
            assertThat(startedWait).isEqualTo(wait);
            waitsInSeconds.add(wait.toSeconds());
            now = now.plus(wait);
        }

        assertThat(waitsInSeconds).containsExactly(1L, 2L, 4L, 8L, 16L, 32L, 60L, 60L, 60L);
    }

    @Test
    void waitingTimeRunsDown() {
        fail(6);
        assertThat(loginThrottle.remainingWait(USERNAME)).isEqualTo(Duration.ofSeconds(2));

        now = now.plusMillis(1500);
        assertThat(loginThrottle.remainingWait(USERNAME)).isEqualTo(Duration.ofMillis(500));

        now = now.plusMillis(500);
        assertThat(loginThrottle.remainingWait(USERNAME)).isZero();
    }

    @Test
    void successResetsTheCounter() {
        fail(7);

        loginThrottle.recordSuccess(USERNAME);

        assertThat(loginThrottle.remainingWait(USERNAME)).isZero();
        fail(4);
        assertThat(loginThrottle.remainingWait(USERNAME)).isZero();
        loginThrottle.recordFailure(USERNAME);
        assertThat(loginThrottle.remainingWait(USERNAME)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void thereIsNoPermanentLockout() {
        fail(1000);

        assertThat(loginThrottle.remainingWait(USERNAME)).isEqualTo(Duration.ofSeconds(60));
        now = now.plusSeconds(60);
        assertThat(loginThrottle.remainingWait(USERNAME)).isZero();
    }

    /** Spec 011: the state is stored with the account, so another instance, as after a restart, goes on with it. */
    @Test
    void stateIsKeptWithTheAccountAndSurvivesARestart() {
        fail(6);
        assertThat(accountRow.state).isEqualTo(new LoginThrottleState(6, now.plusSeconds(2)));

        LoginThrottle afterRestart = new LoginThrottle(clock, accountRow);

        assertThat(afterRestart.remainingWait(USERNAME)).isEqualTo(Duration.ofSeconds(2));
        now = now.plusSeconds(2);
        assertThat(afterRestart.recordFailure(USERNAME)).as("the seventh failure").isEqualTo(Duration.ofSeconds(4));
    }

    /** Spec 011: a password reset deletes the account row, and the state with it. */
    @Test
    void stateIsGoneWithTheAccount() {
        fail(6);

        accountRow.exists = false;
        accountRow.state = LoginThrottleState.NO_FAILURES;

        assertThat(loginThrottle.remainingWait(USERNAME)).isZero();
    }

    /** Spec 011: an attempt with a username that no account has can never succeed, so it changes nothing. */
    @Test
    void failuresWithAnotherUsernameAreNotCountedAndDoNotWait() {
        for (int failure = 1; failure <= 20; failure++) {
            assertThat(loginThrottle.recordFailure("somebody")).as("failure %d", failure).isZero();
        }

        assertThat(loginThrottle.remainingWait("somebody")).isZero();
        assertThat(loginThrottle.remainingWait(USERNAME)).isZero();
        assertThat(accountRow.state).isEqualTo(LoginThrottleState.NO_FAILURES);
    }

    /** Spec 011: nobody can hold up the login of the account without its username. */
    @Test
    void aWaitingTimeOfTheAccountDoesNotHoldBackOtherUsernames() {
        fail(11);

        assertThat(loginThrottle.remainingWait(USERNAME)).isEqualTo(Duration.ofSeconds(60));
        assertThat(loginThrottle.remainingWait("somebody")).isZero();
    }

    /** Attempts that are not throttled must not stand in the way of a login to the account. */
    @Test
    void anAttemptWithAnotherUsernameDoesNotMakeTheAccountWait() throws Exception {
        CountDownLatch strangerIsInside = new CountDownLatch(1);
        CountDownLatch ownerIsDone = new CountDownLatch(1);
        Thread stranger = Thread.ofVirtual().start(() -> loginThrottle.oneAtATime("somebody", () -> {
            strangerIsInside.countDown();
            return await(ownerIsDone);
        }));
        assertThat(strangerIsInside.await(5, TimeUnit.SECONDS)).isTrue();

        String result = loginThrottle.oneAtATime(USERNAME, () -> "checked");
        ownerIsDone.countDown();
        stranger.join();

        assertThat(result).isEqualTo("checked");
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void fail(int times) {
        for (int failure = 0; failure < times; failure++) {
            loginThrottle.recordFailure(USERNAME);
        }
    }

    /** Stands for the row of the account in the database. */
    private static final class AccountRow implements LoginThrottleStore {

        private boolean exists = true;
        private LoginThrottleState state = LoginThrottleState.NO_FAILURES;

        @Override
        public Optional<LoginThrottleState> findLoginThrottleState(String username) {
            return exists && USERNAME.equals(username) ? Optional.of(state) : Optional.empty();
        }

        @Override
        public void storeLoginThrottleState(String username, LoginThrottleState newState) {
            if (exists && USERNAME.equals(username)) {
                state = newState;
            }
        }
    }
}
