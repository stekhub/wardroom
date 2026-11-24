package io.github.stekhub.wardroom.auth.internal;

import io.github.stekhub.wardroom.auth.internal.AuthRequests.RecoveryCodesResponse;
import io.github.stekhub.wardroom.auth.internal.AuthRequests.TwoFactorConfirmation;
import io.github.stekhub.wardroom.auth.internal.AuthRequests.TwoFactorEnableRequest;
import io.github.stekhub.wardroom.auth.internal.AuthRequests.TwoFactorSetup;
import io.github.stekhub.wardroom.auth.internal.AuthRequests.TwoFactorSetupRequest;
import io.github.stekhub.wardroom.auth.internal.AuthRequests.TwoFactorStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Turning two-factor authentication on and off (spec 012). The answers with the secret or the recovery codes are
 * not stored by the browser: every answer of Wardroom carries {@code Cache-Control: no-store}.
 */
@Tag(name = "Authentication")
@RestController
@RequestMapping("/api/auth/two-factor")
@RequiredArgsConstructor
class TwoFactorController {

    private static final String PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    private final TwoFactorService twoFactorService;

    @Operation(summary = "Tells whether two-factor authentication is on", operationId = "getTwoFactor")
    @ApiResponse(responseCode = "200", description = "The state of two-factor authentication")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    TwoFactorStatus status(Authentication authentication) {
        return twoFactorService.status(authentication.getName());
    }

    @Operation(summary = "Starts turning two-factor authentication on: creates a new secret",
            operationId = "setUpTwoFactor")
    @ApiResponse(responseCode = "200", description = "The secret for the authenticator app; nothing is on yet")
    @ApiResponse(responseCode = "400",
            description = "The current password is wrong, or two-factor authentication is already on",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(path = "/setup", produces = MediaType.APPLICATION_JSON_VALUE)
    TwoFactorSetup setUp(@Valid @RequestBody TwoFactorSetupRequest setupRequest, Authentication authentication) {
        return twoFactorService.startSetup(authentication.getName(), setupRequest.currentPassword());
    }

    @Operation(summary = "Turns two-factor authentication on with a code from the authenticator app",
            operationId = "enableTwoFactor")
    @ApiResponse(responseCode = "200", description = "Turned on; the recovery codes are shown only here")
    @ApiResponse(responseCode = "400", description = "The code is wrong, or no setup was started",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429",
            description = "Too many wrong codes or failed logins; the Retry-After header has the waiting time",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(path = "/enable", produces = MediaType.APPLICATION_JSON_VALUE)
    RecoveryCodesResponse enable(@Valid @RequestBody TwoFactorEnableRequest enableRequest,
            Authentication authentication) {
        return twoFactorService.enable(authentication.getName(), enableRequest.code());
    }

    @Operation(summary = "Creates new recovery codes; the old ones stop working",
            operationId = "createRecoveryCodes")
    @ApiResponse(responseCode = "200", description = "The new recovery codes; they are shown only here")
    @ApiResponse(responseCode = "400",
            description = "The current password or the code is wrong, or two-factor authentication is off",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429",
            description = "Too many wrong codes or failed logins; the Retry-After header has the waiting time",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(path = "/recovery-codes", produces = MediaType.APPLICATION_JSON_VALUE)
    RecoveryCodesResponse createRecoveryCodes(@Valid @RequestBody TwoFactorConfirmation confirmation,
            Authentication authentication) {
        return twoFactorService.createRecoveryCodes(authentication.getName(), confirmation.currentPassword(),
                confirmation.code());
    }

    @Operation(summary = "Turns two-factor authentication off", operationId = "disableTwoFactor")
    @ApiResponse(responseCode = "204", description = "Turned off; the secret and the recovery codes are deleted")
    @ApiResponse(responseCode = "400",
            description = "The current password or the code is wrong, or two-factor authentication is off",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429",
            description = "Too many wrong codes or failed logins; the Retry-After header has the waiting time",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disable(@Valid @RequestBody TwoFactorConfirmation confirmation, Authentication authentication) {
        twoFactorService.disable(authentication.getName(), confirmation.currentPassword(), confirmation.code());
    }
}
