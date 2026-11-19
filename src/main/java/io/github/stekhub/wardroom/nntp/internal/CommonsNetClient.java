package io.github.stekhub.wardroom.nntp.internal;

import io.github.stekhub.wardroom.nntp.NntpCapabilities;
import io.github.stekhub.wardroom.nntp.NntpClient;
import io.github.stekhub.wardroom.nntp.NntpException;
import io.github.stekhub.wardroom.nntp.NntpFailureType;
import io.github.stekhub.wardroom.nntp.NntpServerSettings;
import io.github.stekhub.wardroom.nntp.NntpTlsMode;
import io.github.stekhub.wardroom.platform.LogSafe;
import io.github.stekhub.wardroom.platform.ProtocolAnomaly;
import io.github.stekhub.wardroom.platform.ProtocolAnomalyKind;
import io.github.stekhub.wardroom.platform.ProtocolAnomalyLog;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.MalformedServerReplyException;

/** An {@link NntpClient} on top of Apache Commons Net (ADR 0005). */
@Slf4j
final class CommonsNetClient implements NntpClient {

    private static final int POSTING_ALLOWED = 200;
    private static final int POSTING_PROHIBITED = 201;
    private static final int CAPABILITY_LIST = 101;
    private static final int AUTHENTICATION_ACCEPTED = 281;
    private static final int PASSWORD_REQUIRED = 381;
    private static final int AUTHENTICATION_FAILED = 481;
    private static final int AUTHENTICATION_REJECTED = 482;
    private static final int ACCESS_DENIED = 502;

    private static final int QUIT_TIMEOUT_MILLIS = 5_000;
    private static final int MESSAGE_LINE_MAX_LENGTH = 200;
    private static final String ACTION_CONNECTION_CLOSED = "connection-closed";

    private final NntpServerSettings server;
    private final WireClient wire = new WireClient();
    private final ProtocolAnomalyLog anomalies = new ProtocolAnomalyLog(log);

    private NntpCapabilities capabilities = NntpCapabilities.unsupported();
    private boolean postingAllowedByGreeting;
    private boolean broken;
    private boolean closed;

    private CommonsNetClient(NntpServerSettings server) {
        this.server = server;
    }

    /**
     * Connects, checks the greeting, reads the capabilities and logs in.
     *
     * @throws NntpException when a step fails; the connection is closed then
     */
    static CommonsNetClient open(NntpServerSettings server, NntpTimings timings, SSLContext sslContext) {
        CommonsNetClient client = new CommonsNetClient(server);
        try {
            client.connect(timings, sslContext);
            return client;
        } catch (RuntimeException e) {
            client.broken = true;
            client.close();
            throw e;
        }
    }

    private void connect(NntpTimings timings, SSLContext sslContext) {
        wire.setConnectTimeout(Math.toIntExact(timings.connectTimeout().toMillis()));
        wire.setDefaultTimeout(Math.toIntExact(timings.readTimeout().toMillis()));
        wire.addProtocolCommandListener(new CommandLog(log, server.serverId()));
        if (server.tlsMode() == NntpTlsMode.IMPLICIT) {
            wire.setSocketFactory(new TlsSocketFactory(sslContext.getSocketFactory(), server.host()));
        }
        try {
            // Reads the greeting as well; with TLS the handshake happens here.
            wire.connect(server.host(), server.port());
        } catch (IOException e) {
            throw failure(e, null);
        }
        checkGreeting();
        capabilities = requestCapabilities();
        if (server.authenticates()) {
            authenticate();
            // A server may offer more after the login, so what was read before is no longer valid (RFC 3977, 5.2).
            capabilities = requestCapabilities();
        }
    }

    private void checkGreeting() {
        switch (wire.getReplyCode()) {
            case POSTING_ALLOWED -> postingAllowedByGreeting = true;
            case POSTING_PROHIBITED -> postingAllowedByGreeting = false;
            case ACCESS_DENIED -> throw refused();
            default -> throw unexpectedResponse(null);
        }
    }

    private NntpCapabilities requestCapabilities() {
        String command = "CAPABILITIES";
        int code;
        List<String> lines;
        try {
            code = wire.sendCommand(command);
            if (code != CAPABILITY_LIST) {
                // Servers from before RFC 3977 do not know the command. They are still usable (spec 002).
                return NntpCapabilities.unsupported();
            }
            lines = wire.readMultiLineResponse();
        } catch (IOException e) {
            throw failure(e, command);
        }
        Map<String, List<String>> entries = new LinkedHashMap<>();
        for (String line : lines) {
            String[] tokens = line.strip().split("\\s+");
            if (!tokens[0].isEmpty()) {
                entries.put(tokens[0].toUpperCase(Locale.ROOT), List.of(Arrays.copyOfRange(tokens, 1, tokens.length)));
            }
        }
        return new NntpCapabilities(true, entries);
    }

    private void authenticate() {
        String command = "AUTHINFO USER";
        int code;
        try {
            code = wire.authinfoUser(server.username());
            if (code == PASSWORD_REQUIRED) {
                command = "AUTHINFO PASS";
                code = wire.authinfoPass(server.password());
            }
        } catch (IOException e) {
            throw failure(e, command);
        }
        switch (code) {
            case AUTHENTICATION_ACCEPTED -> {
            }
            case AUTHENTICATION_FAILED, AUTHENTICATION_REJECTED -> throw new NntpException(NntpFailureType.AUTH_FAILED,
                    "The server rejected the user name or password (" + code + ")");
            case ACCESS_DENIED -> throw refused();
            default -> throw unexpectedResponse(command);
        }
    }

    @Override
    public NntpCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public boolean postingAllowedByGreeting() {
        return postingAllowedByGreeting;
    }

    /** @return whether further commands can be sent on this connection */
    boolean isUsable() {
        return !closed && !broken && wire.isConnected();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (wire.isConnected()) {
            if (!broken) {
                try {
                    wire.setSoTimeout(QUIT_TIMEOUT_MILLIS);
                    wire.quit();
                } catch (IOException e) {
                    log.debug("NNTP server={} did not answer QUIT", server.serverId());
                }
            }
            try {
                wire.disconnect();
            } catch (IOException e) {
                log.debug("NNTP server={} could not be disconnected cleanly", server.serverId());
            }
        }
        anomalies.finish();
    }

    private NntpException refused() {
        return new NntpException(NntpFailureType.SERVER_REFUSED, "The server refused access: " + replyLine());
    }

    /** The server answered with a response code that the protocol does not allow at this point. */
    private NntpException unexpectedResponse(String command) {
        broken = true;
        anomalies.report(ProtocolAnomaly.builder()
                .kind(ProtocolAnomalyKind.RESPONSE_UNEXPECTED_CODE)
                .command(command)
                .action(ACTION_CONNECTION_CLOSED)
                .raw(wire.getReplyString())
                .build());
        String where = command == null ? "as greeting" : "to " + command;
        return new NntpException(NntpFailureType.PROTOCOL, "Unexpected response " + where + ": " + replyLine());
    }

    private NntpException failure(IOException e, String command) {
        broken = true;
        if (e instanceof MalformedServerReplyException) {
            anomalies.report(ProtocolAnomaly.builder()
                    .kind(ProtocolAnomalyKind.RESPONSE_MALFORMED_LINE)
                    .command(command)
                    .action(ACTION_CONNECTION_CLOSED)
                    .raw(malformedLine(e))
                    .build());
        }
        return NntpFailures.fromIo(e, server.host(), server.port());
    }

    /** The library puts the offending line at the end of its message, after "reply: ". */
    private static String malformedLine(IOException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        int marker = message.toLowerCase(Locale.ROOT).lastIndexOf("reply: ");
        return marker < 0 ? message : message.substring(marker + "reply: ".length());
    }

    private String replyLine() {
        String reply = wire.getReplyString();
        return LogSafe.escape(reply == null ? "" : reply.strip(), MESSAGE_LINE_MAX_LENGTH);
    }
}
