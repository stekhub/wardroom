package io.github.stekhub.wardroom.auth.internal;

import static io.github.stekhub.wardroom.TestBrowser.json;
import static io.github.stekhub.wardroom.jooq.Tables.RECOVERY_CODE;
import static io.github.stekhub.wardroom.jooq.Tables.SPRING_SESSION;
import static io.github.stekhub.wardroom.jooq.Tables.WARDROOM_USER;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.IntegrationTest;
import io.github.stekhub.wardroom.TestBrowser;
import java.time.Duration;
import java.time.InstantSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The testing requirements of spec 011: the throttling state with the account, the session limits and the log lines
 * of logins.
 *
 * <p>The time of a session is controlled through its stored row: a session that was last used some time ago is one
 * whose last request is moved back by that time.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class LoginProtectionTest {

    private static final String USERNAME = "owner";
    private static final String PASSWORD = "correct horse battery";
    private static final String OTHER_PASSWORD = "another long password";
    private static final Duration THIRTY_MINUTES = Duration.ofMinutes(30);
    private static final Duration SEVEN_DAYS = Duration.ofDays(7);

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DSLContext database;

    @Autowired
    AccountRepository accountRepository;

    TestBrowser browser;

    @BeforeEach
    void startWithAnAccount() {
        database.deleteFrom(SPRING_SESSION).execute();
        database.deleteFrom(RECOVERY_CODE).execute();
        database.deleteFrom(WARDROOM_USER).execute();
        browser = new TestBrowser(mvc);
        assertThat(browser.post("/api/auth/setup", json("username", USERNAME, "password", PASSWORD)))
                .hasStatus(HttpStatus.CREATED);
    }

    // Throttling

    @Test
    void throttlingStateIsStoredWithTheAccountAndSurvivesARestart() {
        TestBrowser stranger = new TestBrowser(mvc);
        failLogins(stranger, 6);

        assertThat(database.select(WARDROOM_USER.FAILED_LOGINS).from(WARDROOM_USER).fetchSingle().value1())
                .isEqualTo(6);
        assertThat(database.select(WARDROOM_USER.LOGIN_BLOCKED_UNTIL).from(WARDROOM_USER).fetchSingle().value1())
                .isGreaterThan(System.currentTimeMillis());
        // After a restart there is a new throttle, which knows only what is stored.
        LoginThrottle throttleAfterRestart = new LoginThrottle(InstantSource.system(), accountRepository);
        assertThat(throttleAfterRestart.remainingWait(USERNAME)).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(2));
        assertThat(logIn(stranger, USERNAME, PASSWORD)).hasStatus(HttpStatus.TOO_MANY_REQUESTS).containsHeader(
                "Retry-After");
    }

    @Test
    void throttlingStateIsRemovedWithTheAccount() {
        TestBrowser stranger = new TestBrowser(mvc);
        failLogins(stranger, 6);

        // The password reset of ADR 0017: the account row is deleted and the account is created again.
        database.deleteFrom(WARDROOM_USER).execute();
        TestBrowser newOwner = new TestBrowser(mvc);
        assertThat(newOwner.post("/api/auth/setup", json("username", USERNAME, "password", OTHER_PASSWORD)))
                .hasStatus(HttpStatus.CREATED);

        assertThat(database.select(WARDROOM_USER.FAILED_LOGINS).from(WARDROOM_USER).fetchSingle().value1()).isZero();
        assertThat(database.select(WARDROOM_USER.LOGIN_BLOCKED_UNTIL).from(WARDROOM_USER).fetchSingle().value1())
                .isNull();
        assertThat(logIn(stranger, USERNAME, OTHER_PASSWORD)).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void failedLoginsWithAnUnknownUsernameDoNotCountAndDoNotHoldUpTheOwner() {
        TestBrowser stranger = new TestBrowser(mvc);
        for (int failure = 1; failure <= 12; failure++) {
            assertThat(logIn(stranger, "somebody-" + failure, OTHER_PASSWORD)).as("failure %d", failure).hasStatus(
                    HttpStatus.UNAUTHORIZED);
        }

        assertThat(database.select(WARDROOM_USER.FAILED_LOGINS).from(WARDROOM_USER).fetchSingle().value1()).isZero();
        assertThat(logIn(new TestBrowser(mvc), USERNAME, PASSWORD)).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void waitingTimeOfTheAccountHoldsBackOnlyLoginsWithItsUsername() {
        TestBrowser stranger = new TestBrowser(mvc);
        failLogins(stranger, 5);

        assertThat(logIn(stranger, USERNAME, PASSWORD)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(logIn(stranger, "somebody", PASSWORD)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(database.select(WARDROOM_USER.FAILED_LOGINS).from(WARDROOM_USER).fetchSingle().value1())
                .isEqualTo(5);
    }

    // Sessions

    @Test
    void sessionAfterSetupEndsAfterThirtyMinutesWithoutRequests() {
        assertThat(sessionLimit()).isEqualTo(THIRTY_MINUTES);

        lastRequestWas(THIRTY_MINUTES.plusMinutes(1));

        assertThat(browser.get("/api/auth/me")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void sessionWithoutRememberMeEndsAfterThirtyMinutesWithoutRequests() {
        database.deleteFrom(SPRING_SESSION).execute();
        assertThat(logIn(browser, USERNAME, PASSWORD)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(sessionLimit()).isEqualTo(THIRTY_MINUTES);

        lastRequestWas(THIRTY_MINUTES.minusMinutes(1));
        assertThat(browser.get("/api/auth/me")).as("within the limit").hasStatusOk();

        lastRequestWas(THIRTY_MINUTES.plusMinutes(1));
        assertThat(browser.get("/api/auth/me")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void sessionWithRememberMeEndsAfterSevenDaysWithoutRequests() {
        database.deleteFrom(SPRING_SESSION).execute();
        assertThat(browser.post("/api/auth/login",
                "{\"username\": \"owner\", \"password\": \"correct horse battery\", \"rememberMe\": true}"))
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(sessionLimit()).isEqualTo(SEVEN_DAYS);

        lastRequestWas(SEVEN_DAYS.minusHours(1));
        assertThat(browser.get("/api/auth/me")).as("within the limit").hasStatusOk();

        lastRequestWas(SEVEN_DAYS.plusHours(1));
        assertThat(browser.get("/api/auth/me")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void requestWithinTheLimitStartsItAgain() {
        lastRequestWas(THIRTY_MINUTES.minusMinutes(1));
        long before = System.currentTimeMillis();

        assertThat(browser.get("/api/subscriptions")).hasStatusOk();

        assertThat(database.select(SPRING_SESSION.LAST_ACCESS_TIME).from(SPRING_SESSION).fetchSingle().value1())
                .as("the request counts as the last one").isGreaterThanOrEqualTo(before);
        assertThat(database.select(SPRING_SESSION.EXPIRY_TIME).from(SPRING_SESSION).fetchSingle().value1())
                .isGreaterThanOrEqualTo(before + THIRTY_MINUTES.toMillis());
        assertThat(browser.get("/api/auth/me")).hasStatusOk();
    }

    // Logging

    @Test
    void failedAndSuccessfulLoginsAreLoggedWithTheClientAddress(CapturedOutput output) {
        TestBrowser stranger = new TestBrowser(mvc).withHeader("X-Forwarded-For", "203.0.113.7, 198.51.100.2");

        logIn(stranger, "somebody", OTHER_PASSWORD);
        logIn(stranger, USERNAME, PASSWORD);

        assertThat(output.getOut())
                .containsPattern("WARN .*Failed login from 127\\.0\\.0\\.1 "
                        + "\\(X-Forwarded-For: 203\\.0\\.113\\.7, 198\\.51\\.100\\.2\\)")
                .containsPattern("INFO .*Login from 127\\.0\\.0\\.1 "
                        + "\\(X-Forwarded-For: 203\\.0\\.113\\.7, 198\\.51\\.100\\.2\\)");
        assertThat(output.getAll()).doesNotContain("somebody", PASSWORD, OTHER_PASSWORD);
    }

    @Test
    void loginWithoutAForwardedAddressIsLoggedWithTheConnectionAddressOnly(CapturedOutput output) {
        logIn(new TestBrowser(mvc), USERNAME, OTHER_PASSWORD);

        assertThat(output.getOut()).containsPattern("Failed login from 127\\.0\\.0\\.1\\R");
        assertThat(output.getOut()).doesNotContain("X-Forwarded-For");
    }

    @Test
    void forwardedAddressIsEscapedBeforeItIsLogged(CapturedOutput output) {
        TestBrowser stranger = new TestBrowser(mvc).withHeader("X-Forwarded-For", "1.2.3.4\tWARN forged line");

        logIn(stranger, USERNAME, OTHER_PASSWORD);

        assertThat(output.getOut()).contains("(X-Forwarded-For: 1.2.3.4\\tWARN forged line)");
    }

    @Test
    void attemptsDuringAWaitingTimeAreNotLoggedOneByOne(CapturedOutput output) {
        TestBrowser stranger = new TestBrowser(mvc);
        failLogins(stranger, 5);
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(logIn(stranger, USERNAME, OTHER_PASSWORD)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        }

        assertThat(output.getOut().lines().filter(line -> line.contains("Failed login from")).count()).isEqualTo(5);
        assertThat(output.getOut().lines().filter(line -> line.contains("the next login has to wait")).toList())
                .singleElement()
                .asString()
                .contains("WARN")
                .contains("Too many failed logins in a row; the next login has to wait 1 second");
    }

    private void failLogins(TestBrowser stranger, int times) {
        for (int failure = 1; failure <= times; failure++) {
            MvcTestResult result = logIn(stranger, USERNAME, OTHER_PASSWORD);
            if (result.getResponse().getStatus() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                // The waiting time of the failure before has not passed yet; it ends in the stored state.
                database.update(WARDROOM_USER).setNull(WARDROOM_USER.LOGIN_BLOCKED_UNTIL).execute();
                result = logIn(stranger, USERNAME, OTHER_PASSWORD);
            }
            assertThat(result).as("failure %d", failure).hasStatus(HttpStatus.UNAUTHORIZED);
        }
    }

    private Duration sessionLimit() {
        return Duration.ofSeconds(
                database.select(SPRING_SESSION.MAX_INACTIVE_INTERVAL).from(SPRING_SESSION).fetchSingle().value1());
    }

    /** Moves the last request of the only session back in time. */
    private void lastRequestWas(Duration ago) {
        long lastRequest = System.currentTimeMillis() - ago.toMillis();
        database.update(SPRING_SESSION)
                .set(SPRING_SESSION.LAST_ACCESS_TIME, lastRequest)
                .set(SPRING_SESSION.EXPIRY_TIME, lastRequest + sessionLimit().toMillis())
                .execute();
    }

    private static MvcTestResult logIn(TestBrowser browser, String username, String password) {
        return browser.post("/api/auth/login", json("username", username, "password", password));
    }
}
