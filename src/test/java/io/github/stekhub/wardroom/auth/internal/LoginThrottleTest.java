package io.github.stekhub.wardroom.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {

    private Instant now = Instant.parse("2026-11-19T10:00:00Z");
    private final InstantSource clock = () -> now;
    private final AccountRow accountRow = new AccountRow();
    private final LoginThrottle loginThrottle = new LoginThrottle(clock, accountRow);

    @Test
    void firstFourFailuresDoNotCauseWaiting() {
        for (int failure = 1; failure <= 4; failure++) {
            assertThat(loginThrottle.recordFailure()).as("wait started by failure %d", failure).isZero();
            assertThat(loginThrottle.remainingWait()).as("after failure %d", failure).isZero();
        }
    }

    @Test
    void waitingStartsWithOneSecondAfterTheFifthFailureAndDoublesUpToSixtySeconds() {
        fail(4);
        List<Long> waitsInSeconds = new ArrayList<>();
        for (int failure = 5; failure <= 13; failure++) {
            Duration startedWait = loginThrottle.recordFailure();
            Duration wait = loginThrottle.remainingWait();
            assertThat(startedWait).isEqualTo(wait);
            waitsInSeconds.add(wait.toSeconds());
            now = now.plus(wait);
        }

        assertThat(waitsInSeconds).containsExactly(1L, 2L, 4L, 8L, 16L, 32L, 60L, 60L, 60L);
    }

    @Test
    void waitingTimeRunsDown() {
        fail(6);
        assertThat(loginThrottle.remainingWait()).isEqualTo(Duration.ofSeconds(2));

        now = now.plusMillis(1500);
        assertThat(loginThrottle.remainingWait()).isEqualTo(Duration.ofMillis(500));

        now = now.plusMillis(500);
        assertThat(loginThrottle.remainingWait()).isZero();
    }

    @Test
    void successResetsTheCounter() {
        fail(7);

        loginThrottle.recordSuccess();

        assertThat(loginThrottle.remainingWait()).isZero();
        fail(4);
        assertThat(loginThrottle.remainingWait()).isZero();
        loginThrottle.recordFailure();
        assertThat(loginThrottle.remainingWait()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void thereIsNoPermanentLockout() {
        fail(1000);

        assertThat(loginThrottle.remainingWait()).isEqualTo(Duration.ofSeconds(60));
        now = now.plusSeconds(60);
        assertThat(loginThrottle.remainingWait()).isZero();
    }

    /** Spec 011: the state is stored with the account, so another instance, as after a restart, goes on with it. */
    @Test
    void stateIsKeptWithTheAccountAndSurvivesARestart() {
        fail(6);
        assertThat(accountRow.state).isEqualTo(new LoginThrottleState(6, now.plusSeconds(2)));

        LoginThrottle afterRestart = new LoginThrottle(clock, accountRow);

        assertThat(afterRestart.remainingWait()).isEqualTo(Duration.ofSeconds(2));
        now = now.plusSeconds(2);
        assertThat(afterRestart.recordFailure()).as("the seventh failure").isEqualTo(Duration.ofSeconds(4));
    }

    /** Spec 011: a password reset deletes the account row, and the state with it. */
    @Test
    void stateIsGoneWithTheAccount() {
        fail(6);

        accountRow.exists = false;
        accountRow.state = LoginThrottleState.NO_FAILURES;

        assertThat(loginThrottle.remainingWait()).isZero();
    }

    @Test
    void withoutAnAccountTheStateIsKeptInMemory() {
        accountRow.exists = false;

        fail(5);

        assertThat(loginThrottle.remainingWait()).isEqualTo(Duration.ofSeconds(1));
        assertThat(accountRow.state).isEqualTo(LoginThrottleState.NO_FAILURES);
        loginThrottle.recordSuccess();
        assertThat(loginThrottle.remainingWait()).isZero();
    }

    private void fail(int times) {
        for (int failure = 0; failure < times; failure++) {
            loginThrottle.recordFailure();
        }
    }

    /** Stands for the row of the account in the database. */
    private static final class AccountRow implements LoginThrottleStore {

        private boolean exists = true;
        private LoginThrottleState state = LoginThrottleState.NO_FAILURES;

        @Override
        public Optional<LoginThrottleState> findLoginThrottleState() {
            return exists ? Optional.of(state) : Optional.empty();
        }

        @Override
        public boolean storeLoginThrottleState(LoginThrottleState newState) {
            if (exists) {
                state = newState;
            }
            return exists;
        }
    }
}
