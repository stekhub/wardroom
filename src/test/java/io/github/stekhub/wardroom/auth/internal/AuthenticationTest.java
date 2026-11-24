package io.github.stekhub.wardroom.auth.internal;

import static io.github.stekhub.wardroom.TestBrowser.SESSION_COOKIE;
import static io.github.stekhub.wardroom.TestBrowser.json;
import static io.github.stekhub.wardroom.jooq.Tables.SPRING_SESSION;
import static io.github.stekhub.wardroom.jooq.Tables.SPRING_SESSION_ATTRIBUTES;
import static io.github.stekhub.wardroom.jooq.Tables.WARDROOM_USER;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.IntegrationTest;
import io.github.stekhub.wardroom.TestBrowser;
import io.github.stekhub.wardroom.auth.AccountCreationCleanup;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The testing requirements of spec 001, against the whole application and a real SQLite database. The session limits
 * and the stored throttling of spec 011 are in {@link LoginProtectionTest}.
 */
@IntegrationTest
@Import(AuthenticationTest.CountingCleanup.class)
@ExtendWith(OutputCaptureExtension.class)
class AuthenticationTest {

    private static final String USERNAME = "owner";
    private static final String PASSWORD = "correct horse battery";
    private static final String OTHER_PASSWORD = "another long password";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DSLContext database;

    @Autowired
    CountingCleanup countingCleanup;

    TestBrowser browser;

    /** Every test starts like a fresh installation: no account and no session, so no failed logins either. */
    @BeforeEach
    void startWithoutAccount() {
        database.deleteFrom(SPRING_SESSION).execute();
        database.deleteFrom(WARDROOM_USER).execute();
        countingCleanup.calls.set(0);
        browser = new TestBrowser(mvc);
    }

    // Setup

    @Test
    void setupIsAvailableOnlyWithoutAnAccount() {
        assertThat(browser.get("/api/auth/setup")).hasStatusOk();

        assertThat(setUp(browser)).hasStatus(HttpStatus.CREATED);

        assertThat(browser.get("/api/auth/setup")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(new TestBrowser(mvc).get("/api/auth/setup"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(new TestBrowser(mvc).post("/api/auth/setup", json("username", "second", "password", PASSWORD)))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(database.fetchCount(WARDROOM_USER)).isEqualTo(1);
    }

    @Test
    void setupLogsInAndStoresOnlyAHash() {
        assertThat(setUp(browser)).hasStatus(HttpStatus.CREATED).body().isEmpty();

        assertThat(browser.get("/api/auth/me"))
                .hasStatusOk()
                .bodyJson()
                .isStrictlyEqualTo("{\"username\": \"owner\"}");
        String passwordHash = database.select(WARDROOM_USER.PASSWORD_HASH).from(WARDROOM_USER).fetchSingle().value1();
        assertThat(passwordHash).startsWith("$argon2id$").doesNotContain(PASSWORD);
    }

    @Test
    void ofTwoSimultaneousSetupRequestsExactlyOneSucceeds() throws Exception {
        TestBrowser firstBrowser = new TestBrowser(mvc);
        TestBrowser secondBrowser = new TestBrowser(mvc);
        // Both browsers get their CSRF token first, so the two setup requests really run at the same time.
        firstBrowser.get("/api/auth/setup");
        secondBrowser.get("/api/auth/setup");
        CyclicBarrier bothReady = new CyclicBarrier(2);

        List<Integer> statuses;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(setupAtTheSameTime(firstBrowser, "first", bothReady));
            Future<Integer> second = executor.submit(setupAtTheSameTime(secondBrowser, "second", bothReady));
            statuses = List.of(first.get(), second.get());
        }

        assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.CREATED.value(), HttpStatus.NOT_FOUND.value());
        assertThat(database.fetchCount(WARDROOM_USER)).isEqualTo(1);
    }

    /**
     * ADR 0017: the reset. The part about the NNTP credentials is tested with the server configuration, which
     * registers its cleanup the same way as the one counted here.
     */
    @Test
    void setupAfterTheAccountRowWasDeletedRemovesSessionsAndRunsTheCleanups() {
        setUp(browser);
        TestBrowser secondBrowser = new TestBrowser(mvc);
        logIn(secondBrowser, USERNAME, PASSWORD);
        assertThat(countingCleanup.calls).hasValue(1);

        database.deleteFrom(WARDROOM_USER).execute();

        assertThat(browser.get("/api/auth/me")).as("the account of this session is gone").hasStatus(
                HttpStatus.UNAUTHORIZED);
        TestBrowser newOwner = new TestBrowser(mvc);
        assertThat(newOwner.get("/api/auth/setup")).hasStatusOk();
        assertThat(newOwner.post("/api/auth/setup", json("username", USERNAME, "password", OTHER_PASSWORD)))
                .hasStatus(HttpStatus.CREATED);

        assertThat(countingCleanup.calls).hasValue(2);
        assertThat(database.fetchCount(SPRING_SESSION)).as("only the session of the new account").isEqualTo(1);
        assertThat(secondBrowser.get("/api/auth/me"))
                .as("a browser of the previous account is not logged in to the new one, even with the same username")
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(newOwner.get("/api/auth/me")).hasStatusOk();
    }

    @Test
    void setupRejectsInvalidUsernameAndPasswordWithFieldAndRule() {
        assertThat(browser.post("/api/auth/setup", json("username", "   ", "password", PASSWORD)))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .satisfies(body -> {
                    assertThat(body).extractingPath("$.errors[0].field").isEqualTo("username");
                    assertThat(body).extractingPath("$.detail").isEqualTo("The username must have 1 to 64 characters.");
                });
        assertThat(browser.post("/api/auth/setup", json("username", USERNAME, "password", "elevenchars")))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .satisfies(body -> {
                    assertThat(body).extractingPath("$.errors[0].field").isEqualTo("password");
                    assertThat(body).extractingPath("$.detail")
                            .isEqualTo("The password must have 12 to 128 characters.");
                });
        assertThat(browser.post("/api/auth/setup", "{\"username\": \"owner\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.errors[0].field")
                .isEqualTo("password");
        assertThat(database.fetchCount(WARDROOM_USER)).isZero();
    }

    @Test
    void usernameIsStoredWithoutSurroundingWhitespaceAndComparedExactly() {
        browser.post("/api/auth/setup", json("username", "  Owner  ", "password", PASSWORD));

        assertThat(database.select(WARDROOM_USER.USERNAME).from(WARDROOM_USER).fetchSingle().value1())
                .isEqualTo("Owner");
        assertThat(logIn(new TestBrowser(mvc), "owner", PASSWORD)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(logIn(new TestBrowser(mvc), " Owner ", PASSWORD)).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void passwordIsNotTrimmed() {
        browser.post("/api/auth/setup", json("username", USERNAME, "password", " " + PASSWORD + " "));

        assertThat(logIn(new TestBrowser(mvc), USERNAME, PASSWORD)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(logIn(new TestBrowser(mvc), USERNAME, " " + PASSWORD + " ")).hasStatus(HttpStatus.NO_CONTENT);
    }

    // Login and logout

    @Test
    void loginSucceedsWithCorrectCredentials() {
        setUp(new TestBrowser(mvc));

        assertThat(logIn(browser, USERNAME, PASSWORD)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(browser.get("/api/auth/me")).hasStatusOk().bodyJson().extractingPath("$.username").isEqualTo(
                USERNAME);
    }

    @Test
    void loginFailsWithTheSameAnswerForAWrongUsernameAndAWrongPassword() {
        setUp(new TestBrowser(mvc));

        MvcTestResult wrongPassword = logIn(browser, USERNAME, OTHER_PASSWORD);
        MvcTestResult wrongUsername = logIn(browser, "somebody", PASSWORD);
        MvcTestResult tooLongPassword = logIn(browser, USERNAME, PASSWORD.repeat(10));

        assertThat(wrongPassword)
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.detail")
                .isEqualTo("Username or password is wrong.");
        assertThat(wrongUsername).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(tooLongPassword).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(body(wrongUsername)).isEqualTo(body(wrongPassword)).isEqualTo(body(tooLongPassword));
        assertThat(browser.get("/api/auth/me")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginGetsANewSessionId() {
        setUp(browser);
        String sessionAfterSetup = browser.cookieValue(SESSION_COOKIE);

        logIn(browser, USERNAME, PASSWORD);

        assertThat(browser.cookieValue(SESSION_COOKIE)).isNotNull().isNotEqualTo(sessionAfterSetup);
        assertThat(database.fetchCount(SPRING_SESSION)).as("the earlier session of this browser has ended")
                .isEqualTo(1);
    }

    @Test
    void logoutEndsTheSession() {
        setUp(browser);
        String session = browser.cookieValue(SESSION_COOKIE);

        assertThat(browser.post("/api/auth/logout")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(browser.get("/api/auth/me")).hasStatus(HttpStatus.UNAUTHORIZED);
        TestBrowser browserWithTheOldCookie = new TestBrowser(mvc);
        browserWithTheOldCookie.setCookie(SESSION_COOKIE, session);
        assertThat(browserWithTheOldCookie.get("/api/auth/me")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(database.fetchCount(SPRING_SESSION)).isZero();
    }

    // Throttling

    @Test
    void throttlingStartsAfterTheFifthFailureAndDoesNotCheckThePassword() {
        setUp(new TestBrowser(mvc));
        for (int failure = 1; failure <= 5; failure++) {
            assertThat(logIn(browser, USERNAME, OTHER_PASSWORD)).as("failure %d", failure).hasStatus(
                    HttpStatus.UNAUTHORIZED);
        }

        assertThat(logIn(browser, USERNAME, PASSWORD))
                .as("even the correct password is rejected during the waiting time")
                .hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasHeader("Retry-After", "1")
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.detail")
                .isEqualTo("Too many failed logins. Try again in 1 second.");
        assertThat(browser.get("/api/auth/me")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void successfulLoginResetsTheFailureCounter() {
        setUp(new TestBrowser(mvc));
        for (int failure = 1; failure <= 4; failure++) {
            logIn(browser, USERNAME, OTHER_PASSWORD);
        }
        assertThat(logIn(browser, USERNAME, PASSWORD)).hasStatus(HttpStatus.NO_CONTENT);

        for (int failure = 1; failure <= 4; failure++) {
            assertThat(logIn(browser, USERNAME, OTHER_PASSWORD)).hasStatus(HttpStatus.UNAUTHORIZED);
        }
        assertThat(logIn(browser, USERNAME, PASSWORD)).hasStatus(HttpStatus.NO_CONTENT);
    }

    // Sessions

    @Test
    void unreadableStoredSessionIsDeletedAndCountsAsLoggedOut(CapturedOutput output) {
        setUp(browser);
        database.update(SPRING_SESSION_ATTRIBUTES)
                .set(SPRING_SESSION_ATTRIBUTES.ATTRIBUTE_BYTES, new byte[] {1, 2, 3})
                .execute();

        assertThat(browser.get("/api/auth/me")).hasStatus(HttpStatus.UNAUTHORIZED);

        assertThat(database.fetchCount(SPRING_SESSION)).isZero();
        assertThat(output.getOut()).contains("A stored session could not be read and was deleted");
        assertThat(logIn(browser, USERNAME, PASSWORD)).hasStatus(HttpStatus.NO_CONTENT);
    }

    // Password change

    @Test
    void passwordChangeRequiresTheCurrentPassword() {
        setUp(browser);

        assertThat(browser.put("/api/auth/password",
                json("currentPassword", OTHER_PASSWORD, "newPassword", OTHER_PASSWORD)))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .satisfies(body -> {
                    assertThat(body).extractingPath("$.errors[0].field").isEqualTo("currentPassword");
                    assertThat(body).extractingPath("$.detail").isEqualTo("The current password is wrong.");
                });
        assertThat(browser.put("/api/auth/password", json("currentPassword", PASSWORD, "newPassword", "short")))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.errors[0].field")
                .isEqualTo("newPassword");

        assertThat(logIn(new TestBrowser(mvc), USERNAME, PASSWORD)).as("the password is unchanged").hasStatus(
                HttpStatus.NO_CONTENT);
    }

    @Test
    void passwordChangeEndsAllSessionsIncludingTheCurrentOne() {
        setUp(browser);
        String sessionOfThisBrowser = browser.cookieValue(SESSION_COOKIE);
        TestBrowser otherBrowser = new TestBrowser(mvc);
        logIn(otherBrowser, USERNAME, PASSWORD);
        assertThat(otherBrowser.get("/api/auth/me")).hasStatusOk();

        assertThat(browser.put("/api/auth/password", json("currentPassword", PASSWORD, "newPassword", OTHER_PASSWORD)))
                .hasStatus(HttpStatus.NO_CONTENT);

        assertThat(browser.get("/api/auth/me")).as("the browser that changed the password is logged out").hasStatus(
                HttpStatus.UNAUTHORIZED);
        assertThat(otherBrowser.get("/api/auth/me")).as("the other session has ended").hasStatus(
                HttpStatus.UNAUTHORIZED);
        TestBrowser browserWithACopyOfTheSession = new TestBrowser(mvc);
        browserWithACopyOfTheSession.setCookie(SESSION_COOKIE, sessionOfThisBrowser);
        assertThat(browserWithACopyOfTheSession.get("/api/auth/me"))
                .as("a copied session ID of the browser that changed the password is worthless")
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(database.fetchCount(SPRING_SESSION)).isZero();

        assertThat(logIn(new TestBrowser(mvc), USERNAME, PASSWORD)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(logIn(browser, USERNAME, OTHER_PASSWORD)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(browser.get("/api/auth/me")).hasStatusOk();
    }

    // Access rules

    @Test
    void protectedEndpointsAnswer401WithoutASessionAndNeverRedirect() {
        setUp(new TestBrowser(mvc));

        assertThat(browser.get("/api/auth/me"))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .doesNotContainHeader("Location")
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo(401);
        assertThat(browser.get("/api/anything-else")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(browser.post("/api/auth/logout")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(browser.put("/api/auth/password",
                json("currentPassword", PASSWORD, "newPassword", OTHER_PASSWORD))).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void healthEndpointAndFrontendArePublic() {
        setUp(new TestBrowser(mvc));

        assertThat(browser.get("/actuator/health")).hasStatusOk();
        assertThat(browser.get("/index.html")).hasStatusOk();
        assertThat(browser.get("/settings/account")).hasStatusOk();
    }

    @Test
    void everyResponseCarriesTheContentSecurityPolicyAndTheOtherSecurityHeaders() {
        setUp(new TestBrowser(mvc));

        for (String path : new String[] {"/index.html", "/settings/account", "/leaving", "/api/auth/session",
            "/api/no-such-endpoint", "/actuator/health"}) {
            assertThat(browser.get(path)).as(path)
                    .hasHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; "
                            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'")
                    .hasHeader("X-Content-Type-Options", "nosniff")
                    .hasHeader("Referrer-Policy", "no-referrer");
        }
    }

    @Test
    void stateChangingRequestsWithoutTheCsrfTokenAnswer403() {
        TestBrowser browserWithoutToken = new TestBrowser(mvc).withoutCsrfToken();

        assertThat(browserWithoutToken.post("/api/auth/setup", json("username", USERNAME, "password", PASSWORD)))
                .hasStatus(HttpStatus.FORBIDDEN)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo(403);
        assertThat(database.fetchCount(WARDROOM_USER)).isZero();

        setUp(browser);
        browser.withoutCsrfToken();
        assertThat(browser.post("/api/auth/login", json("username", USERNAME, "password", PASSWORD))).hasStatus(
                HttpStatus.FORBIDDEN);
        assertThat(browser.put("/api/auth/password", json("currentPassword", PASSWORD, "newPassword", OTHER_PASSWORD)))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(browser.post("/api/auth/logout")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(browser.get("/api/auth/me")).as("the logout was rejected").hasStatusOk();
    }

    // Security

    @Test
    void passwordsAndHashesNeverAppearInTheLogOrInResponses(CapturedOutput output) {
        MvcTestResult setup = setUp(browser);
        MvcTestResult failedLogin = logIn(browser, USERNAME, OTHER_PASSWORD);
        MvcTestResult login = logIn(browser, USERNAME, PASSWORD);
        MvcTestResult me = browser.get("/api/auth/me");
        MvcTestResult rejectedChange = browser.put("/api/auth/password",
                json("currentPassword", OTHER_PASSWORD, "newPassword", "short"));
        String passwordHash = database.select(WARDROOM_USER.PASSWORD_HASH).from(WARDROOM_USER).fetchSingle().value1();

        assertThat(output.getAll()).doesNotContain(PASSWORD, OTHER_PASSWORD, passwordHash,
                browser.cookieValue(SESSION_COOKIE));
        for (MvcTestResult result : List.of(setup, failedLogin, login, me, rejectedChange)) {
            assertThat(body(result)).doesNotContain(PASSWORD, OTHER_PASSWORD, passwordHash, "argon2");
        }
    }

    private static MvcTestResult setUp(TestBrowser browser) {
        return browser.post("/api/auth/setup", json("username", USERNAME, "password", PASSWORD));
    }

    private static MvcTestResult logIn(TestBrowser browser, String username, String password) {
        return browser.post("/api/auth/login", json("username", username, "password", password));
    }

    private static Callable<Integer> setupAtTheSameTime(TestBrowser browser, String username, CyclicBarrier bothReady) {
        return () -> {
            bothReady.await();
            return browser.post("/api/auth/setup", json("username", username, "password", PASSWORD))
                    .getResponse().getStatus();
        };
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Stands for a module that stores sensitive data, such as the server configuration with its NNTP credentials. */
    @TestComponent
    static class CountingCleanup implements AccountCreationCleanup {

        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void removeSensitiveData() {
            calls.incrementAndGet();
        }
    }
}
