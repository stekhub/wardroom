package io.github.stekhub.wardroom.auth.internal;

import static io.github.stekhub.wardroom.TestBrowser.SESSION_COOKIE;
import static io.github.stekhub.wardroom.TestBrowser.json;
import static io.github.stekhub.wardroom.jooq.Tables.RECOVERY_CODE;
import static io.github.stekhub.wardroom.jooq.Tables.SPRING_SESSION;
import static io.github.stekhub.wardroom.jooq.Tables.WARDROOM_USER;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.IntegrationTest;
import io.github.stekhub.wardroom.TestBrowser;
import io.github.stekhub.wardroom.TestInstallation;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

/** The testing requirements of spec 012, against the whole application, with a clock the test controls. */
@IntegrationTest
@Import(TwoFactorAuthenticationTest.ControlledClock.class)
@ExtendWith(OutputCaptureExtension.class)
class TwoFactorAuthenticationTest {

    private static final String USERNAME = "owner";
    private static final String PASSWORD = "correct horse battery";
    private static final String OTHER_PASSWORD = "another long password";
    private static final String WRONG_CODE = "000000";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DSLContext database;

    @Autowired
    TestClock clock;

    TestBrowser browser;

    /** Every test starts with an account without a second factor, logged in, and no failed logins. */
    @BeforeEach
    void startWithAnAccount() {
        database.deleteFrom(SPRING_SESSION).execute();
        database.deleteFrom(RECOVERY_CODE).execute();
        database.deleteFrom(WARDROOM_USER).execute();
        clock.now = Instant.now();
        browser = new TestBrowser(mvc);
        assertThat(browser.post("/api/auth/setup", json("username", USERNAME, "password", PASSWORD)))
                .hasStatus(HttpStatus.CREATED);
    }

    // Turning it on

    @Test
    void turningOnNeedsThePassword() {
        assertThat(browser.post("/api/auth/two-factor/setup", json("currentPassword", OTHER_PASSWORD)))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);

        assertThat(storedSecret()).as("nothing is stored").isNull();
        assertThat(browser.get("/api/auth/two-factor")).hasStatusOk().bodyJson().isStrictlyEqualTo(
                "{\"enabled\": false, \"recoveryCodesLeft\": 0}");
    }

    @Test
    void turningOnNeedsACorrectCodeAndUntilThenNothingChanges() {
        MvcTestResult setup = browser.post("/api/auth/two-factor/setup", json("currentPassword", PASSWORD));
        assertThat(setup).hasStatusOk();
        assertThat(setup.getResponse().getHeader("Cache-Control")).contains("no-store");
        String secret = TestInstallation.body(setup).get("secret").asString();
        assertThat(secret).matches("[A-Z2-7]{32}");
        assertThat(TestInstallation.body(setup).get("otpauthUri").asString()).isEqualTo(
                "otpauth://totp/Wardroom%3Aowner?secret=" + secret
                        + "&issuer=Wardroom&algorithm=SHA1&digits=6&period=30");
        assertThat(new String(storedSecret(), StandardCharsets.ISO_8859_1))
                .as("the secret is stored encrypted").doesNotContain(secret);

        assertThat(browser.post("/api/auth/two-factor/enable", json("code", wrongCode(secret))))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(twoFactorEnabled()).isFalse();
        assertThat(logIn(new TestBrowser(mvc))).as("a login needs no code yet").hasStatus(HttpStatus.NO_CONTENT);

        MvcTestResult enabled = browser.post("/api/auth/two-factor/enable", json("code", code(secret)));

        assertThat(enabled).hasStatusOk();
        assertThat(enabled.getResponse().getHeader("Cache-Control")).contains("no-store");
        List<String> recoveryCodes = recoveryCodes(enabled);
        assertThat(recoveryCodes).hasSize(10).doesNotHaveDuplicates();
        assertThat(twoFactorEnabled()).isTrue();
        assertThat(database.select(RECOVERY_CODE.CODE_HASH).from(RECOVERY_CODE).fetch(RECOVERY_CODE.CODE_HASH))
                .as("only hashes are stored")
                .hasSize(10)
                .doesNotContainAnyElementsOf(recoveryCodes)
                .contains(RecoveryCodes.hash(recoveryCodes.getFirst()));
        assertThat(browser.get("/api/auth/two-factor")).hasStatusOk().bodyJson().isStrictlyEqualTo(
                "{\"enabled\": true, \"recoveryCodesLeft\": 10}");
        assertThat(browser.get("/api/auth/me")).as("the session that turned it on stays").hasStatusOk();
    }

    @Test
    void enablingWithoutASetupAndASecondSetupWhileItIsOnAreRejected() {
        assertThat(browser.post("/api/auth/two-factor/enable", json("code", WRONG_CODE)))
                .hasStatus(HttpStatus.BAD_REQUEST);
        turnOn();

        assertThat(browser.post("/api/auth/two-factor/setup", json("currentPassword", PASSWORD)))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.detail").isEqualTo("Two-factor authentication is already on.");
        assertThat(twoFactorEnabled()).isTrue();
    }

    @Test
    void everySetupCreatesANewSecret() {
        String first = TestInstallation.body(
                browser.post("/api/auth/two-factor/setup", json("currentPassword", PASSWORD))).get("secret")
                .asString();
        String second = TestInstallation.body(
                browser.post("/api/auth/two-factor/setup", json("currentPassword", PASSWORD))).get("secret")
                .asString();

        assertThat(second).isNotEqualTo(first);
        assertThat(browser.post("/api/auth/two-factor/enable", json("code", code(first))))
                .as("the secret of the first setup is worthless").hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(browser.post("/api/auth/two-factor/enable", json("code", code(second)))).hasStatusOk();
    }

    // Login

    @Test
    void loginNeedsTheSecondStepAndASessionInTheSecondStepHasNoAccess() {
        TwoFactor twoFactor = turnOn();
        TestBrowser otherBrowser = new TestBrowser(mvc);

        assertThat(logIn(otherBrowser)).hasStatusOk().bodyJson().isStrictlyEqualTo(
                "{\"secondFactorRequired\": true}");

        String sessionOfTheSecondStep = otherBrowser.cookieValue(SESSION_COOKIE);
        assertThat(sessionOfTheSecondStep).isNotNull();
        assertThat(otherBrowser.get("/api/auth/me")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(otherBrowser.get("/api/subscriptions")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(otherBrowser.get("/api/auth/two-factor")).hasStatus(HttpStatus.UNAUTHORIZED);

        assertThat(secondStep(otherBrowser, code(twoFactor.secret))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(otherBrowser.get("/api/auth/me")).hasStatusOk();
        assertThat(otherBrowser.cookieValue(SESSION_COOKIE))
                .as("the session ID is changed again when the code is accepted")
                .isNotNull()
                .isNotEqualTo(sessionOfTheSecondStep);
    }

    @Test
    void secondStepWithoutACorrectPasswordBeforeIsRejected() {
        TwoFactor twoFactor = turnOn();

        assertThat(secondStep(new TestBrowser(mvc), code(twoFactor.secret))).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(secondStep(browser, code(twoFactor.secret)))
                .as("a session that is logged in has not started a second step")
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void secondStepEndsAfterFiveMinutes() {
        TwoFactor twoFactor = turnOn();
        TestBrowser otherBrowser = new TestBrowser(mvc);
        logIn(otherBrowser);
        assertThat(sessionLimitOf(otherBrowser)).isEqualTo(Duration.ofMinutes(5));

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        assertThat(secondStep(otherBrowser, code(twoFactor.secret)))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.detail").isEqualTo("The login took too long. Enter your password again.");
        assertThat(secondStep(otherBrowser, code(twoFactor.secret)))
                .as("the login starts again with the password").hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(logIn(otherBrowser)).hasStatusOk();
        assertThat(secondStep(otherBrowser, code(twoFactor.secret))).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void rememberMeIsChosenBeforeAndAppliesAfterTheCode() {
        TwoFactor twoFactor = turnOn();
        TestBrowser otherBrowser = new TestBrowser(mvc);

        assertThat(otherBrowser.post("/api/auth/login",
                "{\"username\": \"owner\", \"password\": \"correct horse battery\", \"rememberMe\": true}"))
                .hasStatusOk();
        assertThat(secondStep(otherBrowser, code(twoFactor.secret))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(sessionLimitOf(otherBrowser)).isEqualTo(Duration.ofDays(7));

        TestBrowser thirdBrowser = new TestBrowser(mvc);
        logIn(thirdBrowser);
        clock.advance(Duration.ofSeconds(30));
        assertThat(secondStep(thirdBrowser, code(twoFactor.secret))).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(sessionLimitOf(thirdBrowser)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void codeIsAcceptedOnePeriodBeforeAndAfterButNotFurtherAway() {
        TwoFactor twoFactor = turnOn();
        // Far enough from the code that turned it on, which counts as used.
        clock.advance(Duration.ofMinutes(5));
        long period = Totp.period(clock.instant());

        assertThat(loginWithCode(codeOfPeriod(twoFactor.secret, period - 2))).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(loginWithCode(codeOfPeriod(twoFactor.secret, period + 2))).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(loginWithCode(codeOfPeriod(twoFactor.secret, period - 1))).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(loginWithCode(codeOfPeriod(twoFactor.secret, period))).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(loginWithCode(codeOfPeriod(twoFactor.secret, period + 1))).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void codeIsNotAcceptedTwice() {
        TwoFactor twoFactor = turnOn();
        String code = code(twoFactor.secret);

        assertThat(loginWithCode(code)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(loginWithCode(code)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(loginWithCode(codeOfPeriod(twoFactor.secret, Totp.period(clock.instant()) - 1)))
                .as("nor a code of an earlier period").hasStatus(HttpStatus.UNAUTHORIZED);
        clock.advance(Duration.ofSeconds(30));
        assertThat(loginWithCode(code(twoFactor.secret))).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void recoveryCodeWorksOnce() {
        TwoFactor twoFactor = turnOn();
        String recoveryCode = twoFactor.recoveryCodes.getFirst();

        assertThat(loginWithCode(recoveryCode)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(loginWithCode(recoveryCode)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(browser.get("/api/auth/two-factor")).bodyJson().extractingPath("$.recoveryCodesLeft").isEqualTo(9);
        String second = twoFactor.recoveryCodes.get(1);
        assertThat(loginWithCode(" " + second.substring(0, 5).toLowerCase() + "-" + second.substring(5) + " "))
                .as("small letters, a hyphen and spaces are accepted").hasStatus(HttpStatus.NO_CONTENT);
    }

    // Throttling

    @Test
    void wrongCodesAreThrottledLikeWrongPasswords() {
        TwoFactor twoFactor = turnOn();
        TestBrowser otherBrowser = new TestBrowser(mvc);
        logIn(otherBrowser);
        String wrongCode = wrongCode(twoFactor.secret);
        for (int failure = 1; failure <= 5; failure++) {
            assertThat(secondStep(otherBrowser, wrongCode))
                    .as("failure %d", failure)
                    .hasStatus(HttpStatus.UNAUTHORIZED)
                    .bodyJson().extractingPath("$.detail").isEqualTo("The code is wrong.");
        }

        assertThat(secondStep(otherBrowser, code(twoFactor.secret)))
                .as("even the correct code is rejected during the waiting time")
                .hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasHeader("Retry-After", "1");
        assertThat(logIn(new TestBrowser(mvc))).as("and so is the password").hasStatus(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void correctPasswordDoesNotResetTheCounterOnlyACorrectCodeDoes() {
        TwoFactor twoFactor = turnOn();
        String wrongCode = wrongCode(twoFactor.secret);
        for (int failure = 1; failure <= 4; failure++) {
            TestBrowser otherBrowser = new TestBrowser(mvc);
            assertThat(logIn(otherBrowser)).as("the right password, attempt %d", failure).hasStatusOk();
            assertThat(secondStep(otherBrowser, wrongCode)).hasStatus(HttpStatus.UNAUTHORIZED);
        }
        assertThat(failedLogins()).isEqualTo(4);

        TestBrowser otherBrowser = new TestBrowser(mvc);
        assertThat(logIn(otherBrowser)).hasStatusOk();
        assertThat(failedLogins()).as("the right password did not reset the counter").isEqualTo(4);
        assertThat(secondStep(otherBrowser, code(twoFactor.secret))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(failedLogins()).isZero();
    }

    @Test
    void wrongCodesInTheSettingsAreThrottledToo() {
        TwoFactor twoFactor = turnOn();
        String wrongCode = wrongCode(twoFactor.secret);
        for (int failure = 1; failure <= 5; failure++) {
            assertThat(browser.delete("/api/auth/two-factor", json("currentPassword", PASSWORD, "code", wrongCode)))
                    .as("failure %d", failure)
                    .hasStatus(HttpStatus.BAD_REQUEST);
        }

        assertThat(browser.delete("/api/auth/two-factor",
                json("currentPassword", PASSWORD, "code", code(twoFactor.secret))))
                .as("even the correct code is rejected during the waiting time")
                .hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasHeader("Retry-After", "1");
        assertThat(browser.post("/api/auth/two-factor/recovery-codes",
                json("currentPassword", PASSWORD, "code", twoFactor.recoveryCodes.getFirst())))
                .as("and so is a recovery code")
                .hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(logIn(new TestBrowser(mvc))).as("and a login").hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(twoFactorEnabled()).isTrue();

        clock.advance(Duration.ofSeconds(1));
        assertThat(browser.delete("/api/auth/two-factor",
                json("currentPassword", PASSWORD, "code", code(twoFactor.secret))))
                .as("after the waiting time")
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(failedLogins()).as("a correct code resets the counter").isZero();
    }

    @Test
    void wrongCodesWhileTurningOnAreThrottledToo() {
        String secret = TestInstallation.body(
                browser.post("/api/auth/two-factor/setup", json("currentPassword", PASSWORD))).get("secret")
                .asString();
        for (int failure = 1; failure <= 5; failure++) {
            assertThat(browser.post("/api/auth/two-factor/enable", json("code", wrongCode(secret))))
                    .as("failure %d", failure)
                    .hasStatus(HttpStatus.BAD_REQUEST);
        }

        assertThat(browser.post("/api/auth/two-factor/enable", json("code", code(secret))))
                .hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(twoFactorEnabled()).isFalse();
    }

    // Managing it

    @Test
    void turningOffNeedsPasswordAndCode() {
        TwoFactor twoFactor = turnOn();
        clock.advance(Duration.ofSeconds(30));

        assertThat(browser.delete("/api/auth/two-factor",
                json("currentPassword", OTHER_PASSWORD, "code", code(twoFactor.secret))))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(browser.delete("/api/auth/two-factor",
                json("currentPassword", PASSWORD, "code", wrongCode(twoFactor.secret))))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(twoFactorEnabled()).isTrue();

        assertThat(browser.delete("/api/auth/two-factor",
                json("currentPassword", PASSWORD, "code", code(twoFactor.secret))))
                .hasStatus(HttpStatus.NO_CONTENT);

        assertThat(twoFactorEnabled()).isFalse();
        assertThat(storedSecret()).as("the secret is deleted").isNull();
        assertThat(database.fetchCount(RECOVERY_CODE)).isZero();
        assertThat(logIn(new TestBrowser(mvc))).as("a login needs no code").hasStatus(HttpStatus.NO_CONTENT);
        assertThat(browser.get("/api/auth/me")).as("the session that turned it off stays").hasStatusOk();
    }

    @Test
    void turningOffWorksWithARecoveryCode() {
        TwoFactor twoFactor = turnOn();

        assertThat(browser.delete("/api/auth/two-factor",
                json("currentPassword", PASSWORD, "code", twoFactor.recoveryCodes.getFirst())))
                .hasStatus(HttpStatus.NO_CONTENT);

        assertThat(twoFactorEnabled()).isFalse();
    }

    @Test
    void newRecoveryCodesNeedPasswordAndCodeAndMakeTheOldOnesInvalid() {
        TwoFactor twoFactor = turnOn();
        clock.advance(Duration.ofSeconds(30));

        assertThat(browser.post("/api/auth/two-factor/recovery-codes",
                json("currentPassword", OTHER_PASSWORD, "code", code(twoFactor.secret))))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(browser.post("/api/auth/two-factor/recovery-codes",
                json("currentPassword", PASSWORD, "code", wrongCode(twoFactor.secret))))
                .hasStatus(HttpStatus.BAD_REQUEST);

        MvcTestResult created = browser.post("/api/auth/two-factor/recovery-codes",
                json("currentPassword", PASSWORD, "code", code(twoFactor.secret)));

        assertThat(created).hasStatusOk();
        assertThat(created.getResponse().getHeader("Cache-Control")).contains("no-store");
        List<String> newCodes = recoveryCodes(created);
        assertThat(newCodes).hasSize(10).doesNotContainAnyElementsOf(twoFactor.recoveryCodes);
        assertThat(loginWithCode(twoFactor.recoveryCodes.getFirst())).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(loginWithCode(newCodes.getFirst())).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void changingThePasswordDoesNotChangeTheSecondFactor() {
        TwoFactor twoFactor = turnOn();

        assertThat(browser.put("/api/auth/password",
                json("currentPassword", PASSWORD, "newPassword", OTHER_PASSWORD))).hasStatus(HttpStatus.NO_CONTENT);

        TestBrowser otherBrowser = new TestBrowser(mvc);
        assertThat(otherBrowser.post("/api/auth/login", json("username", USERNAME, "password", OTHER_PASSWORD)))
                .hasStatusOk();
        clock.advance(Duration.ofSeconds(30));
        assertThat(secondStep(otherBrowser, code(twoFactor.secret))).hasStatus(HttpStatus.NO_CONTENT);
    }

    // Reset and lost key

    /** ADR 0017: the password reset deletes the account row; the second factor goes with it. */
    @Test
    void passwordResetRemovesTheSecondFactor() {
        turnOn();

        database.deleteFrom(WARDROOM_USER).execute();
        TestBrowser newOwner = new TestBrowser(mvc);
        assertThat(newOwner.post("/api/auth/setup", json("username", USERNAME, "password", OTHER_PASSWORD)))
                .hasStatus(HttpStatus.CREATED);

        assertThat(twoFactorEnabled()).isFalse();
        assertThat(storedSecret()).isNull();
        assertThat(database.fetchCount(RECOVERY_CODE)).isZero();
        TestBrowser otherBrowser = new TestBrowser(mvc);
        assertThat(otherBrowser.post("/api/auth/login", json("username", USERNAME, "password", OTHER_PASSWORD)))
                .as("a login needs no code").hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void secretThatCannotBeDecryptedIsLoggedAndOnlyARecoveryCodeLogsIn(CapturedOutput output) {
        TwoFactor twoFactor = turnOn();
        clock.advance(Duration.ofSeconds(30));
        // As after the loss of the key file: the stored bytes do not match the key.
        byte[] unreadable = storedSecret();
        unreadable[unreadable.length - 1] ^= 1;
        database.update(WARDROOM_USER).set(WARDROOM_USER.TOTP_SECRET, unreadable).execute();

        assertThat(loginWithCode(code(twoFactor.secret))).hasStatus(HttpStatus.UNAUTHORIZED);

        assertThat(output.getOut()).contains("WARN")
                .contains("The secret of two-factor authentication cannot be read with the key file")
                .contains("reset the password");
        assertThat(loginWithCode(twoFactor.recoveryCodes.getFirst())).hasStatus(HttpStatus.NO_CONTENT);
    }

    // Security

    @Test
    void secretCodesAndRecoveryCodesNeverAppearInTheLog(CapturedOutput output) {
        TwoFactor twoFactor = turnOn();
        clock.advance(Duration.ofSeconds(30));
        String code = code(twoFactor.secret);
        String wrongCode = wrongCode(twoFactor.secret);
        TestBrowser otherBrowser = new TestBrowser(mvc);
        logIn(otherBrowser);
        secondStep(otherBrowser, wrongCode);
        secondStep(otherBrowser, code);
        loginWithCode(twoFactor.recoveryCodes.getFirst());
        loginWithCode("NOT-A-CODE-AT-ALL");
        browser.post("/api/auth/two-factor/setup", json("currentPassword", OTHER_PASSWORD));

        List<String> secrets = new ArrayList<>(twoFactor.recoveryCodes);
        secrets.addAll(List.of(twoFactor.secret, code, wrongCode, "NOT-A-CODE-AT-ALL", PASSWORD, OTHER_PASSWORD));
        assertThat(output.getAll()).doesNotContain(secrets.toArray(String[]::new));
    }

    @Test
    void twoFactorEndpointsNeedASession() {
        TestBrowser stranger = new TestBrowser(mvc);

        assertThat(stranger.get("/api/auth/two-factor")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(stranger.post("/api/auth/two-factor/setup", json("currentPassword", PASSWORD)))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(stranger.post("/api/auth/two-factor/enable", json("code", WRONG_CODE)))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(stranger.post("/api/auth/two-factor/recovery-codes",
                json("currentPassword", PASSWORD, "code", WRONG_CODE))).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(stranger.delete("/api/auth/two-factor", json("currentPassword", PASSWORD, "code", WRONG_CODE)))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    // Helpers

    /** What the owner has after turning the second factor on. */
    private record TwoFactor(String secret, List<String> recoveryCodes) {
    }

    /**
     * Turns the second factor on in the logged-in browser. The clock moves to the next period afterwards, because
     * the code that turned it on cannot be used again.
     */
    private TwoFactor turnOn() {
        String secret = TestInstallation.body(
                browser.post("/api/auth/two-factor/setup", json("currentPassword", PASSWORD))).get("secret")
                .asString();
        MvcTestResult enabled = browser.post("/api/auth/two-factor/enable", json("code", code(secret)));
        assertThat(enabled).hasStatusOk();
        clock.advance(Duration.ofSeconds(30));
        return new TwoFactor(secret, recoveryCodes(enabled));
    }

    /** Logs in with the password in a new browser and sends the code. */
    private MvcTestResult loginWithCode(String code) {
        TestBrowser otherBrowser = new TestBrowser(mvc);
        assertThat(logIn(otherBrowser)).hasStatusOk();
        MvcTestResult result = secondStep(otherBrowser, code);
        // A wrong code must not start a waiting time for the next step of the test.
        database.update(WARDROOM_USER)
                .set(WARDROOM_USER.FAILED_LOGINS, 0)
                .setNull(WARDROOM_USER.LOGIN_BLOCKED_UNTIL)
                .execute();
        return result;
    }

    private static MvcTestResult logIn(TestBrowser browser) {
        return browser.post("/api/auth/login", json("username", USERNAME, "password", PASSWORD));
    }

    private static MvcTestResult secondStep(TestBrowser browser, String code) {
        return browser.post("/api/auth/login/second-factor", json("code", code));
    }

    /** @return the code the authenticator app shows now */
    private String code(String secret) {
        return codeOfPeriod(secret, Totp.period(clock.instant()));
    }

    private static String codeOfPeriod(String secret, long period) {
        return Totp.code(Base32.decode(secret), period);
    }

    /** @return 6 digits that are not the code of the current period or of one next to it */
    private String wrongCode(String secret) {
        long period = Totp.period(clock.instant());
        List<String> accepted = List.of(codeOfPeriod(secret, period - 1), codeOfPeriod(secret, period),
                codeOfPeriod(secret, period + 1));
        return accepted.contains(WRONG_CODE) ? (accepted.contains("111111") ? "222222" : "111111") : WRONG_CODE;
    }

    private static List<String> recoveryCodes(MvcTestResult result) {
        List<String> codes = new ArrayList<>();
        for (JsonNode code : TestInstallation.body(result).get("recoveryCodes")) {
            codes.add(code.asString());
        }
        return codes;
    }

    private byte[] storedSecret() {
        return database.select(WARDROOM_USER.TOTP_SECRET).from(WARDROOM_USER).fetchSingle().value1();
    }

    private boolean twoFactorEnabled() {
        return database.select(WARDROOM_USER.TOTP_ENABLED).from(WARDROOM_USER).fetchSingle().value1();
    }

    private int failedLogins() {
        return database.select(WARDROOM_USER.FAILED_LOGINS).from(WARDROOM_USER).fetchSingle().value1();
    }

    private Duration sessionLimitOf(TestBrowser browser) {
        // The cookie holds the session ID in Base64.
        String sessionId = new String(java.util.Base64.getDecoder().decode(browser.cookieValue(SESSION_COOKIE)),
                StandardCharsets.UTF_8);
        return Duration.ofSeconds(database.select(SPRING_SESSION.MAX_INACTIVE_INTERVAL)
                .from(SPRING_SESSION)
                .where(SPRING_SESSION.SESSION_ID.eq(sessionId))
                .fetchSingle()
                .value1());
    }

    /** The time of the login throttling and of the codes; it stands still unless the test moves it. */
    static final class TestClock implements InstantSource {

        private volatile Instant now = Instant.now();

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    @TestConfiguration
    static class ControlledClock {

        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }
    }
}
