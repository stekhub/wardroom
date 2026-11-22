package io.github.stekhub.wardroom.server.internal;

import io.github.stekhub.wardroom.nntp.NntpCapabilities;
import io.github.stekhub.wardroom.nntp.NntpClient;
import io.github.stekhub.wardroom.nntp.NntpConnector;
import io.github.stekhub.wardroom.nntp.NntpException;
import io.github.stekhub.wardroom.nntp.NntpFailureType;
import io.github.stekhub.wardroom.nntp.NntpServerSettings;
import io.github.stekhub.wardroom.platform.LogSafe;
import io.github.stekhub.wardroom.server.internal.ServerRequests.ConnectionFailureType;
import io.github.stekhub.wardroom.server.internal.ServerRequests.ConnectionTestResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The connection test of spec 002: connect, validate the certificate, read the greeting, request the capabilities,
 * log in when credentials are given, and quit.
 *
 * <p>The test is a direct action of the owner. It uses a dedicated connection outside the connection pool, passes
 * no circuit breaker and is not retried.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ConnectionTester {

    private final NntpConnector connector;

    ConnectionTestResult test(NntpServerSettings serverSettings) {
        String target = LogSafe.escape(serverSettings.host()) + ":" + serverSettings.port();
        try (NntpClient client = connector.connect(serverSettings)) {
            boolean postingAllowed = postingAllowed(client);
            log.info("Connection test with {} succeeded", target);
            return ConnectionTestResult.succeeded(postingAllowed);
        } catch (NntpException e) {
            ConnectionFailureType type = failureType(e.type());
            log.info("Connection test with {} failed: {} ({})", target, type, e.getMessage());
            return ConnectionTestResult.failed(type, e.getMessage());
        }
    }

    /**
     * The test of the separate posting server, after the test of the server itself worked. A failure here is part
     * of a successful result: reading works without the posting server.
     */
    ConnectionTestResult testPostingServer(NntpServerSettings postingServerSettings) {
        String target = LogSafe.escape(postingServerSettings.host()) + ":" + postingServerSettings.port();
        try (NntpClient client = connector.connect(postingServerSettings)) {
            boolean postingAllowed = postingAllowed(client);
            log.info("Connection test with the posting server {} succeeded", target);
            return ConnectionTestResult.succeeded(postingAllowed);
        } catch (NntpException e) {
            ConnectionFailureType type = failureType(e.type());
            log.info("Connection test with the posting server {} failed: {} ({})", target, type, e.getMessage());
            return ConnectionTestResult.postingServerFailed(type, e.getMessage());
        }
    }

    /**
     * Whether the server offers posting. A capability list that names {@code POST} says so. A list that names
     * {@code READER} but not {@code POST} is a complete list of a server that does not offer posting. Every other
     * server is judged by its greeting: older servers have no capability list, and some servers send a list that
     * names only a few of the things they can do.
     */
    private static boolean postingAllowed(NntpClient client) {
        NntpCapabilities capabilities = client.capabilities();
        if (capabilities.has("POST")) {
            return true;
        }
        return !capabilities.has("READER") && client.postingAllowedByGreeting();
    }

    /** The adapter knows more failure types than the result of the connection test has (spec 002). */
    private static ConnectionFailureType failureType(NntpFailureType type) {
        return switch (type) {
            case UNREACHABLE -> ConnectionFailureType.UNREACHABLE;
            case TIMEOUT -> ConnectionFailureType.TIMEOUT;
            case TLS_CERTIFICATE -> ConnectionFailureType.TLS_CERTIFICATE;
            case TLS_HANDSHAKE -> ConnectionFailureType.TLS_HANDSHAKE;
            case AUTH_FAILED -> ConnectionFailureType.AUTH_FAILED;
            // A server that closes the connection during the test does not want to serve this client.
            case SERVER_REFUSED, CONNECTION_LOST -> ConnectionFailureType.SERVER_REFUSED;
            case PROTOCOL -> ConnectionFailureType.PROTOCOL;
            // These two come from the connection pool, which the test does not use.
            case CIRCUIT_OPEN, NO_CONNECTION_AVAILABLE -> ConnectionFailureType.UNREACHABLE;
        };
    }
}
