package io.github.stekhub.wardroom.auth.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Request and response bodies of the authentication endpoints. The requests do not print their passwords, so a
 * password cannot reach a log through {@code toString()}.
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
}
