package io.github.stekhub.wardroom.server.internal;

import io.github.stekhub.wardroom.nntp.NntpTlsMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Request and response bodies of the server endpoints (spec 002). The requests do not print their password, so it
 * cannot reach a log through {@code toString()}. No response has a field for the password.
 */
final class ServerRequests {

    private ServerRequests() {
    }

    @Schema(name = "ServerRequest")
    record ServerRequest(
            @Schema(description = "1 to 64 characters; surrounding whitespace is removed") @NotNull String name,
            @Schema(description = "Host name or IP address, 1 to 253 characters") @NotNull String host,
            @Schema(description = "1 to 65535") @NotNull Integer port,
            @NotNull NntpTlsMode tlsMode,
            @Schema(description = "USER_PASS needs the TLS mode IMPLICIT") @NotNull AuthMethod authMethod,
            @Schema(description = "Needed for USER_PASS, 1 to 255 characters") String username,
            @Schema(description = "1 to 255 characters. Needed for USER_PASS when no password is stored; empty or "
                    + "absent keeps the stored one") String password,
            @Schema(description = "1 to 50") @NotNull Integer maxConnections) {

        @Override
        public String toString() {
            return "ServerRequest[password hidden]";
        }
    }

    @Schema(name = "ConnectionTestRequest")
    record ConnectionTestRequest(
            @Schema(description = "The stored server whose password is used when the password is empty or absent")
            Long id,
            @NotNull String name,
            @NotNull String host,
            @NotNull Integer port,
            @NotNull NntpTlsMode tlsMode,
            @NotNull AuthMethod authMethod,
            String username,
            String password,
            @NotNull Integer maxConnections) {

        ServerRequest server() {
            return new ServerRequest(name, host, port, tlsMode, authMethod, username, password, maxConnections);
        }

        @Override
        public String toString() {
            return "ConnectionTestRequest[password hidden]";
        }
    }

    @Schema(name = "Server")
    record ServerResponse(@NotNull long id, @NotNull String name, @NotNull String host, @NotNull int port,
                          @NotNull NntpTlsMode tlsMode, @NotNull AuthMethod authMethod,
                          @Schema(description = "null unless the authentication is USER_PASS") String username,
                          @Schema(description = "Whether a password is stored; the password itself is never returned")
                          @NotNull boolean passwordStored,
                          @NotNull int maxConnections,
                          @Schema(description = "The server rejected the stored credentials, or the stored "
                                  + "password can no longer be read; the owner has to enter them again")
                          @NotNull boolean credentialsInvalid) {

        static ServerResponse of(StoredServer server) {
            return new ServerResponse(server.id(), server.name(), server.host(), server.port(), server.tlsMode(),
                    server.authMethod(), server.username(), server.passwordStored(), server.maxConnections(),
                    server.credentialsInvalid());
        }
    }

    /** Why a connection test failed. */
    enum ConnectionFailureType {
        UNREACHABLE, TIMEOUT, TLS_CERTIFICATE, TLS_HANDSHAKE, AUTH_FAILED, SERVER_REFUSED, PROTOCOL
    }

    @Schema(name = "ConnectionTestFailure")
    record ConnectionTestFailure(@NotNull ConnectionFailureType type,
                                 @Schema(description = "Names the cause; never contains credentials")
                                 @NotNull String message) {
    }

    @Schema(name = "ConnectionTestResult")
    record ConnectionTestResult(
            @NotNull boolean success,
            @Schema(description = "null on success") ConnectionTestFailure failure,
            @Schema(description = "Whether the server offers posting; null when the test failed")
            Boolean postingAllowed) {

        static ConnectionTestResult succeeded(boolean postingAllowed) {
            return new ConnectionTestResult(true, null, postingAllowed);
        }

        static ConnectionTestResult failed(ConnectionFailureType type, String message) {
            return new ConnectionTestResult(false, new ConnectionTestFailure(type, message), null);
        }
    }

    @Schema(name = "SavedServer")
    record SavedServer(@NotNull ServerResponse server,
                       @Schema(description = "The server is saved also when this test failed")
                       @NotNull ConnectionTestResult connectionTest) {
    }
}
