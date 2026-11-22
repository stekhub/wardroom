package io.github.stekhub.wardroom.server.internal;

import io.github.stekhub.wardroom.nntp.NntpTlsMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Request and response bodies of the server endpoints (spec 002). The requests do not print their password, so it
 * cannot reach a log through {@code toString()}. No response has a field for the password.
 */
final class ServerRequests {

    private ServerRequests() {
    }

    @Schema(name = "PostingServerRequest", description = "A separate server that takes the posts")
    record PostingServerRequest(
            @Schema(description = "Host name or IP address, 1 to 253 characters") @NotNull String host,
            @Schema(description = "1 to 65535") @NotNull Integer port,
            @NotNull NntpTlsMode tlsMode,
            @Schema(description = "Whether the login of the server is used. Then the three fields below are "
                    + "ignored") @NotNull Boolean sameLogin,
            @Schema(description = "Needed without sameLogin. USER_PASS needs the TLS mode IMPLICIT")
            AuthMethod authMethod,
            @Schema(description = "Needed for USER_PASS, 1 to 255 characters") String username,
            @Schema(description = "1 to 255 characters. Needed for USER_PASS when no password is stored; empty or "
                    + "absent keeps the stored one") String password) {

        @Override
        public String toString() {
            return "PostingServerRequest[password hidden]";
        }
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
            @Schema(description = "1 to 50") @NotNull Integer maxConnections,
            @Schema(description = "null or absent when posts go to the server itself")
            @Valid PostingServerRequest postingServer) {

        /** A server without a separate posting server. */
        ServerRequest(String name, String host, Integer port, NntpTlsMode tlsMode, AuthMethod authMethod,
                String username, String password, Integer maxConnections) {
            this(name, host, port, tlsMode, authMethod, username, password, maxConnections, null);
        }

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
            @NotNull Integer maxConnections,
            @Valid PostingServerRequest postingServer) {

        ServerRequest server() {
            return new ServerRequest(name, host, port, tlsMode, authMethod, username, password, maxConnections,
                    postingServer);
        }

        @Override
        public String toString() {
            return "ConnectionTestRequest[password hidden]";
        }
    }

    @Schema(name = "PostingServer")
    record PostingServerResponse(@NotNull String host, @NotNull int port, @NotNull NntpTlsMode tlsMode,
                                 @Schema(description = "Whether the login of the server is used")
                                 @NotNull boolean sameLogin,
                                 @Schema(description = "null with sameLogin") AuthMethod authMethod,
                                 @Schema(description = "null unless the authentication is USER_PASS") String username,
                                 @Schema(description = "Whether a password of its own is stored; the password "
                                         + "itself is never returned") @NotNull boolean passwordStored) {

        static PostingServerResponse of(StoredPostingServer postingServer) {
            return postingServer == null ? null : new PostingServerResponse(postingServer.host(),
                    postingServer.port(), postingServer.tlsMode(), postingServer.sameLogin(),
                    postingServer.authMethod(), postingServer.username(), postingServer.passwordStored());
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
                          @NotNull boolean credentialsInvalid,
                          @Schema(description = "The separate server for posting; null when posts go to the "
                                  + "server itself") PostingServerResponse postingServer) {

        static ServerResponse of(StoredServer server) {
            return new ServerResponse(server.id(), server.name(), server.host(), server.port(), server.tlsMode(),
                    server.authMethod(), server.username(), server.passwordStored(), server.maxConnections(),
                    server.credentialsInvalid(), PostingServerResponse.of(server.postingServer()));
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
            @Schema(description = "Whether the server that takes the posts offers posting: the separate posting "
                    + "server when one is set. null when the test of that server failed")
            Boolean postingAllowed,
            @Schema(description = "Why the test of the separate posting server failed. null when there is none, "
                    + "when it worked, or when the test of the server itself failed already")
            ConnectionTestFailure postingServerFailure) {

        static ConnectionTestResult succeeded(boolean postingAllowed) {
            return new ConnectionTestResult(true, null, postingAllowed, null);
        }

        static ConnectionTestResult failed(ConnectionFailureType type, String message) {
            return new ConnectionTestResult(false, new ConnectionTestFailure(type, message), null, null);
        }

        /** Reading works; only the separate posting server could not be used. */
        static ConnectionTestResult postingServerFailed(ConnectionFailureType type, String message) {
            return new ConnectionTestResult(true, null, null, new ConnectionTestFailure(type, message));
        }
    }

    @Schema(name = "SavedServer")
    record SavedServer(@NotNull ServerResponse server,
                       @Schema(description = "The server is saved also when this test failed")
                       @NotNull ConnectionTestResult connectionTest) {
    }
}
