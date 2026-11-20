package io.github.stekhub.wardroom.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {

    private Instant now = Instant.parse("2026-11-19T10:00:00Z");
    private final InstantSource clock = () -> now;
    private final LoginThrottle loginThrottle = new LoginThrottle(clock);

    @Test
    void firstFourFailuresDoNotCauseWaiting() {
        for (int failure = 1; failure <= 4; failure++) {
            loginThrottle.recordFailure();
            assertThat(loginThrottle.remainingWait()).as("after failure %d", failure).isZero();
        }
    }

    @Test
    void waitingStartsWithOneSecondAfterTheFifthFailureAndDoublesUpToSixtySeconds() {
        fail(4);
        List<Long> waitsInSeconds = new ArrayList<>();
        for (int failure = 5; failure <= 13; failure++) {
            loginThrottle.recordFailure();
            Duration wait = loginThrottle.remainingWait();
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

    private void fail(int times) {
        for (int failure = 0; failure < times; failure++) {
            loginThrottle.recordFailure();
        }
    }
}
