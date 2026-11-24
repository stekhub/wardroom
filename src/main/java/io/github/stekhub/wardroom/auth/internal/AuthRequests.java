package io.github.stekhub.wardroom.auth.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Request and response bodies of the authentication endpoints. The bodies do not print their passwords, codes and
 * secrets, so none of them can reach a log through {@code toString()}.
 */
final class AuthRequests {

    private AuthRequests() {
    }

    @Schema(name = "SetupRequest")
    record SetupRequest(
            @Schema(description = "1 to 64 characters; surrounding whitespace is removed") @NotNull String username,
            @Schema(description = "12 to 128 characters") @NotNull String password) {

        @Override
        public String toString() {
            return "SetupRequest[password hidden]";
        }
    }

    @Schema(name = "LoginRequest")
    record LoginRequest(@NotNull String username, @NotNull String password,
            @Schema(description = "Whether the browser keeps the session cookie after it is closed; false if absent")
            Boolean rememberMe) {

        /** A request without the field is a login without "Remember me". */
        boolean remembersBrowser() {
            return Boolean.TRUE.equals(rememberMe);
        }

        @Override
        public String toString() {
            return "LoginRequest[password hidden]";
        }
    }

    @Schema(name = "PasswordChangeRequest")
    record PasswordChangeRequest(@NotNull String currentPassword,
            @Schema(description = "12 to 128 characters") @NotNull String newPassword) {

        @Override
        public String toString() {
            return "PasswordChangeRequest[passwords hidden]";
        }
    }

    @Schema(name = "CurrentAccount")
    record CurrentAccount(@NotNull String username) {
    }

    @Schema(name = "LoginResponse", description = "Sent when the password was right and a code is needed")
    record LoginResponse(@NotNull boolean secondFactorRequired) {
    }

    @Schema(name = "SecondFactorRequest")
    record SecondFactorRequest(
            @Schema(description = "A 6-digit code from the authenticator app, or a recovery code") @NotNull
            String code) {

        @Override
        public String toString() {
            return "SecondFactorRequest[code hidden]";
        }
    }

    @Schema(name = "TwoFactorStatus")
    record TwoFactorStatus(@NotNull boolean enabled,
            @Schema(description = "How many recovery codes were not used yet; 0 while it is off") @NotNull
            int recoveryCodesLeft) {
    }

    @Schema(name = "TwoFactorSetupRequest")
    record TwoFactorSetupRequest(@NotNull String currentPassword) {

        @Override
        public String toString() {
            return "TwoFactorSetupRequest[password hidden]";
        }
    }

    @Schema(name = "TwoFactorSetup")
    record TwoFactorSetup(
            @Schema(description = "The otpauth address for the QR code") @NotNull String otpauthUri,
            @Schema(description = "The secret in Base32, to type it into the app") @NotNull String secret) {

        @Override
        public String toString() {
            return "TwoFactorSetup[secret hidden]";
        }
    }

    @Schema(name = "TwoFactorEnableRequest")
    record TwoFactorEnableRequest(
            @Schema(description = "A 6-digit code from the authenticator app") @NotNull String code) {

        @Override
        public String toString() {
            return "TwoFactorEnableRequest[code hidden]";
        }
    }

    @Schema(name = "TwoFactorConfirmation")
    record TwoFactorConfirmation(@NotNull String currentPassword,
            @Schema(description = "A 6-digit code from the authenticator app, or a recovery code") @NotNull
            String code) {

        @Override
        public String toString() {
            return "TwoFactorConfirmation[password and code hidden]";
        }
    }

    @Schema(name = "RecoveryCodes")
    record RecoveryCodesResponse(
            @Schema(description = "10 one-time codes; they are shown once and stored only as hashes") @NotNull
            List<String> recoveryCodes) {

        @Override
        public String toString() {
            return "RecoveryCodes[codes hidden]";
        }
    }
}
