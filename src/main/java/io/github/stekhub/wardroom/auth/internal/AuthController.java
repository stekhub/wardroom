package io.github.stekhub.wardroom.auth.internal;

import io.github.stekhub.wardroom.auth.internal.AuthFailures.SetupUnavailableException;
import io.github.stekhub.wardroom.auth.internal.AuthRequests.CurrentAccount;
import io.github.stekhub.wardroom.auth.internal.AuthRequests.LoginRequest;
import io.github.stekhub.wardroom.auth.internal.AuthRequests.PasswordChangeRequest;
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
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The authentication endpoints of spec 001. */
@Tag(name = "Authentication")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
class AuthController {

    private static final String PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    private final AccountService accountService;
    private final SessionSecurityContextRepository securityContextRepository;

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
        startSession(username, request, response);
    }

    @Operation(summary = "Logs in", operationId = "login")
    @ApiResponse(responseCode = "204", description = "Logged in")
    @ApiResponse(responseCode = "401", description = "Username or password is wrong", content = @Content(
            mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429",
            description = "Too many failed logins; the Retry-After header has the waiting time in seconds",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/login")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void login(@Valid @RequestBody LoginRequest loginRequest, HttpServletRequest request,
            HttpServletResponse response) {
        String username = accountService.login(loginRequest.username(), loginRequest.password());
        startSession(username, request, response);
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
     * ID (protection against session fixation).
     */
    private void startSession(String username, HttpServletRequest request, HttpServletResponse response) {
        endSession(request);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(username, null, List.of()));
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    private static void endSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
