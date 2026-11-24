package io.github.stekhub.wardroom.auth.internal;

import io.github.stekhub.wardroom.auth.internal.AccountService.CheckedPassword;
import io.github.stekhub.wardroom.auth.internal.AuthFailures.SecondFactorFailedException;
import io.github.stekhub.wardroom.auth.internal.AuthFailures.SetupUnavailableException;
import io.github.stekhub.wardroom.auth.internal.AuthRequests.CurrentAccount;
import io.github.stekhub.wardroom.auth.internal.AuthRequests.LoginRequest;
import io.github.stekhub.wardroom.auth.internal.AuthRequests.LoginResponse;
import io.github.stekhub.wardroom.auth.internal.AuthRequests.PasswordChangeRequest;
import io.github.stekhub.wardroom.auth.internal.AuthRequests.SecondFactorRequest;
import io.github.stekhub.wardroom.auth.internal.AuthRequests.SetupRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.security.web.authentication.SpringSessionRememberMeServices;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The authentication endpoints of spec 001, with the session limits of spec 011 and the second step of spec 012. */
@Tag(name = "Authentication")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
class AuthController {

    /** A session without "Remember me" ends after this time without requests (spec 011). */
    static final Duration SESSION_LIMIT = Duration.ofMinutes(30);

    /** A session with "Remember me" ends after this time without requests (spec 001). */
    static final Duration REMEMBERED_SESSION_LIMIT = Duration.ofDays(7);

    /** The second step of a login must be completed within this time (spec 012). */
    static final Duration SECOND_STEP_LIMIT = Duration.ofMinutes(5);

    private static final String PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    /**
     * How long the browser keeps the session cookie (spec 001). A session that is created by a request with this
     * attribute gets a cookie with the longest lifetime there is, which browsers cut to their own maximum of about
     * 400 days. Without the attribute the cookie has no lifetime, and the browser drops it when it is closed.
     *
     * <p>The cookie is written once at login and not renewed, so it cannot carry the inactivity timeout; the server
     * enforces that for every session. Spring Boot sets up the session cookie to look for exactly this attribute.
     */
    private static final String REMEMBER_ME_REQUEST_ATTRIBUTE = SpringSessionRememberMeServices.REMEMBER_ME_LOGIN_ATTR;

    /**
     * What a session holds between the two steps of a login with a second factor: whose password was checked, when,
     * and whether "Remember me" was chosen. Such a session has no login and gives no access to anything else.
     */
    private static final String PENDING_USERNAME = "wardroom.login.pendingUsername";
    private static final String PENDING_SINCE = "wardroom.login.pendingSince";
    private static final String PENDING_REMEMBER_ME = "wardroom.login.pendingRememberMe";

    private final AccountService accountService;
    private final SessionSecurityContextRepository securityContextRepository;
    private final InstantSource clock;

    @Operation(summary = "Tells whether setup is available", operationId = "getSetup")
    @ApiResponse(responseCode = "200", description = "No account exists yet; setup is available")
    @ApiResponse(responseCode = "404", description = "An account exists", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/setup")
    void setupAvailable() {
        if (!accountService.setupAvailable()) {
            throw new SetupUnavailableException();
        }
    }

    @Operation(summary = "Creates the account and logs in", operationId = "setup")
    @ApiResponse(responseCode = "201", description = "Account created and logged in")
    @ApiResponse(responseCode = "400", description = "Username or password breaks a rule", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "An account exists", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/setup")
    @ResponseStatus(HttpStatus.CREATED)
    void setup(@Valid @RequestBody SetupRequest setupRequest, HttpServletRequest request,
            HttpServletResponse response) {
        String username = accountService.createAccount(setupRequest.username(), setupRequest.password());
        startSession(username, false, request, response);
    }

    @Operation(summary = "Logs in, or starts the second step when two-factor authentication is on",
            operationId = "login")
    @ApiResponse(responseCode = "204", description = "Logged in", content = @Content)
    @ApiResponse(responseCode = "200", description = "The password is right; a code is needed to log in",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = LoginResponse.class)))
    @ApiResponse(responseCode = "401", description = "Username or password is wrong", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429",
            description = "Too many failed logins; the Retry-After header has the waiting time in seconds",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/login")
    ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest loginRequest, HttpServletRequest request,
            HttpServletResponse response) {
        CheckedPassword checkedPassword = accountService.checkPassword(loginRequest.username(),
                loginRequest.password(), ClientAddress.of(request));
        if (checkedPassword.secondFactorRequired()) {
            startSecondStep(checkedPassword.username(), loginRequest.remembersBrowser(), request);
            return ResponseEntity.ok(new LoginResponse(true));
        }
        startSession(checkedPassword.username(), loginRequest.remembersBrowser(), request, response);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Completes a login with a code from the authenticator app or a recovery code",
            operationId = "loginWithSecondFactor")
    @ApiResponse(responseCode = "204", description = "Logged in")
    @ApiResponse(responseCode = "401",
            description = "The code is wrong, or the step was not started or took longer than 5 minutes",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429",
            description = "Too many failed logins; the Retry-After header has the waiting time in seconds",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/login/second-factor")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void loginWithSecondFactor(@Valid @RequestBody SecondFactorRequest secondFactorRequest,
            HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        String username = session == null ? null : (String) session.getAttribute(PENDING_USERNAME);
        Long pendingSince = session == null ? null : (Long) session.getAttribute(PENDING_SINCE);
        if (username == null || pendingSince == null
                || clock.instant().isAfter(Instant.ofEpochMilli(pendingSince).plus(SECOND_STEP_LIMIT))) {
            endSession(request);
            throw new SecondFactorFailedException("The login took too long. Enter your password again.");
        }
        boolean rememberMe = Boolean.TRUE.equals(session.getAttribute(PENDING_REMEMBER_ME));
        accountService.checkSecondFactor(username, secondFactorRequest.code(), ClientAddress.of(request));
        startSession(username, rememberMe, request, response);
    }

    @Operation(summary = "Logs out", operationId = "logout")
    @ApiResponse(responseCode = "204", description = "The session has ended")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request) {
        endSession(request);
        SecurityContextHolder.clearContext();
    }

    @Operation(summary = "Returns the logged-in account", operationId = "getCurrentAccount")
    @ApiResponse(responseCode = "200", description = "The logged-in account")
    @ApiResponse(responseCode = "401", description = "Not logged in", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(path = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    CurrentAccount currentAccount(Authentication authentication) {
        return new CurrentAccount(authentication.getName());
    }

    @Operation(summary = "Changes the password and ends all sessions, including the current one",
            operationId = "changePassword")
    @ApiResponse(responseCode = "204", description = "Password changed and logged out")
    @ApiResponse(responseCode = "400", description = "The current password is wrong or the new one breaks a rule",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(@Valid @RequestBody PasswordChangeRequest passwordChangeRequest,
            Authentication authentication, HttpServletRequest request) {
        accountService.changePassword(authentication.getName(), passwordChangeRequest.currentPassword(),
                passwordChangeRequest.newPassword());
        endSession(request);
        SecurityContextHolder.clearContext();
    }

    /**
     * Logs the request in. A session the browser already had is ended first, so the login always gets a new session
     * ID (protection against session fixation). The same happens after the second step of a login.
     *
     * @param rememberMe whether the browser keeps the session cookie after it is closed; the session then also
     *                   lasts longer without requests
     */
    private void startSession(String username, boolean rememberMe, HttpServletRequest request,
            HttpServletResponse response) {
        endSession(request);
        if (rememberMe) {
            request.setAttribute(REMEMBER_ME_REQUEST_ATTRIBUTE, Boolean.TRUE);
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(username, null, List.of()));
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        Duration limit = rememberMe ? REMEMBERED_SESSION_LIMIT : SESSION_LIMIT;
        request.getSession().setMaxInactiveInterval((int) limit.toSeconds());
    }

    /** Remembers that the password was right. The session gets a new ID and no login. */
    private void startSecondStep(String username, boolean rememberMe, HttpServletRequest request) {
        endSession(request);
        HttpSession session = request.getSession();
        session.setAttribute(PENDING_USERNAME, username);
        session.setAttribute(PENDING_SINCE, clock.instant().toEpochMilli());
        session.setAttribute(PENDING_REMEMBER_ME, rememberMe);
        session.setMaxInactiveInterval((int) SECOND_STEP_LIMIT.toSeconds());
    }

    private static void endSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
