package io.github.stekhub.wardroom.nntp.internal;

import io.github.stekhub.wardroom.nntp.NntpActiveGroup;
import io.github.stekhub.wardroom.nntp.NntpArticle;
import io.github.stekhub.wardroom.nntp.NntpCapabilities;
import io.github.stekhub.wardroom.nntp.NntpClient;
import io.github.stekhub.wardroom.nntp.NntpException;
import io.github.stekhub.wardroom.nntp.NntpFailureType;
import io.github.stekhub.wardroom.nntp.NntpGroupDescription;
import io.github.stekhub.wardroom.nntp.NntpGroupStatus;
import io.github.stekhub.wardroom.nntp.NntpOverview;
import io.github.stekhub.wardroom.nntp.NntpPostResult;
import io.github.stekhub.wardroom.nntp.NntpServerSettings;
import io.github.stekhub.wardroom.nntp.NntpTlsMode;
import io.github.stekhub.wardroom.nntp.internal.OverviewParser.ParsedLine;
import io.github.stekhub.wardroom.platform.LogSafe;
import io.github.stekhub.wardroom.platform.ProtocolAnomaly;
import io.github.stekhub.wardroom.platform.ProtocolAnomalyKind;
import io.github.stekhub.wardroom.platform.ProtocolAnomalyLog;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.MalformedServerReplyException;

/** An {@link NntpClient} on top of Apache Commons Net (ADR 0005). */
@Slf4j
final class CommonsNetClient implements NntpClient {

    private static final int POSTING_ALLOWED = 200;
    private static final int POSTING_PROHIBITED = 201;
    private static final int CAPABILITY_LIST = 101;
    private static final int GROUP_SELECTED = 211;
    private static final int LIST_FOLLOWS = 215;
    private static final int ARTICLE_FOLLOWS = 220;
    private static final int ARTICLE_RECEIVED = 240;
    private static final int SEND_ARTICLE = 340;
    private static final int OVERVIEW_FOLLOWS = 224;
    private static final int NO_SUCH_GROUP = 411;
    private static final int NO_CURRENT_ARTICLE = 420;
    private static final int NO_ARTICLES_IN_RANGE = 423;
    private static final int NO_SUCH_ARTICLE = 430;
    private static final int POSTING_NOT_PERMITTED = 440;
    private static final int POSTING_FAILED = 441;
    private static final int AUTHENTICATION_REQUIRED = 480;
    private static final int AUTHENTICATION_ACCEPTED = 281;
    private static final int PASSWORD_REQUIRED = 381;
    private static final int AUTHENTICATION_FAILED = 481;
    private static final int AUTHENTICATION_REJECTED = 482;
    private static final int ACCESS_DENIED = 502;

    private static final int QUIT_TIMEOUT_MILLIS = 5_000;
    private static final int MESSAGE_LINE_MAX_LENGTH = 200;
    private static final String ACTION_CONNECTION_CLOSED = "connection-closed";
    private static final String ACTION_SKIPPED = "skipped";
    private static final String OVERVIEW_FORMAT_VARIANT = "OVERVIEW.FMT";

    private final NntpServerSettings serverSettings;
    private final OverviewFormats overviewFormats;
    private final WireClient wireClient = new WireClient();
    private final ProtocolAnomalyLog anomalyLog = new ProtocolAnomalyLog(log);

    private NntpCapabilities capabilities = NntpCapabilities.unsupported();
    private boolean postingAllowedByGreeting;
    private boolean readerModeEnsured;
    private String selectedGroup;
    private boolean broken;
    private boolean closed;

    private CommonsNetClient(NntpServerSettings serverSettings, OverviewFormats overviewFormats) {
        this.serverSettings = serverSettings;
        this.overviewFormats = overviewFormats;
    }

    /**
     * Connects, checks the greeting, reads the capabilities and logs in.
     *
     * @param overviewFormats where the client finds and keeps the overview format of the server
     * @throws NntpException when a step fails; the connection is closed then
     */
    static CommonsNetClient open(NntpServerSettings serverSettings, NntpTimings timings, SSLContext sslContext,
            OverviewFormats overviewFormats) {
        CommonsNetClient client = new CommonsNetClient(serverSettings, overviewFormats);
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
        wireClient.setConnectTimeout(Math.toIntExact(timings.connectTimeout().toMillis()));
        wireClient.setDefaultTimeout(Math.toIntExact(timings.readTimeout().toMillis()));
        wireClient.addProtocolCommandListener(new CommandLog(log, serverSettings.serverId()));
        if (serverSettings.tlsMode() == NntpTlsMode.IMPLICIT) {
            wireClient.setSocketFactory(new TlsSocketFactory(sslContext.getSocketFactory(), serverSettings.host()));
        }
        try {
            // Reads the greeting as well; with TLS the handshake happens here.
            wireClient.connect(serverSettings.host(), serverSettings.port());
        } catch (IOException e) {
            throw failure(e, null);
        }
        checkGreeting();
        capabilities = requestCapabilities();
        if (serverSettings.authenticates()) {
            authenticate();
            // A server may offer more after the login, so what was read before is no longer valid (RFC 3977, 5.2).
            capabilities = requestCapabilities();
        }
    }

    private void checkGreeting() {
        switch (wireClient.getReplyCode()) {
            case POSTING_ALLOWED -> postingAllowedByGreeting = true;
            case POSTING_PROHIBITED -> postingAllowedByGreeting = false;
            case ACCESS_DENIED -> throw refused();
            default -> throw unexpectedResponse(null);
        }
    }

    private NntpCapabilities requestCapabilities() {
        String command = "CAPABILITIES";
        int responseCode;
        List<String> lines;
        try {
            responseCode = wireClient.sendCommand(command);
            if (responseCode != CAPABILITY_LIST) {
                // Servers from before RFC 3977 do not know the command. They are still usable (spec 002).
                return NntpCapabilities.unsupported();
            }
            lines = wireClient.readMultiLineResponse();
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
        int responseCode;
        try {
            responseCode = wireClient.authinfoUser(serverSettings.username());
            if (responseCode == PASSWORD_REQUIRED) {
                command = "AUTHINFO PASS";
                responseCode = wireClient.authinfoPass(serverSettings.password());
            }
        } catch (IOException e) {
            throw failure(e, command);
        }
        switch (responseCode) {
            case AUTHENTICATION_ACCEPTED -> {
            }
            case AUTHENTICATION_FAILED, AUTHENTICATION_REJECTED -> throw new NntpException(NntpFailureType.AUTH_FAILED,
                    "The server rejected the user name or password (" + responseCode + ")");
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

    @Override
    public void listActive(Consumer<NntpActiveGroup> groupConsumer) {
        ensureReaderMode();
        String command = "LIST ACTIVE";
        int responseCode = send(command, "LIST", "ACTIVE");
        switch (responseCode) {
            case LIST_FOLLOWS -> {
            }
            case AUTHENTICATION_REQUIRED -> throw loginRequired();
            case ACCESS_DENIED -> throw refused();
            default -> throw unexpectedResponse(command);
        }
        readList(command, line -> {
            NntpActiveGroup group = parseActiveGroup(line);
            if (group == null) {
                reportSkippedLine(ProtocolAnomalyKind.LIST_ACTIVE_MALFORMED_LINE, command, line);
            } else {
                groupConsumer.accept(group);
            }
        });
    }

    @Override
    public boolean listNewsgroups(Consumer<NntpGroupDescription> descriptionConsumer) {
        ensureReaderMode();
        String command = "LIST NEWSGROUPS";
        if (send(command, "LIST", "NEWSGROUPS") != LIST_FOLLOWS) {
            // A server does not have to offer descriptions (spec 003).
            log.debug("NNTP server={} rejected {}: {}", serverSettings.serverId(), command, replyLine());
            return false;
        }
        readList(command, line -> {
            NntpGroupDescription description = parseGroupDescription(line);
            if (description == null) {
                reportSkippedLine(ProtocolAnomalyKind.LIST_NEWSGROUPS_MALFORMED_LINE, command, line);
            } else {
                descriptionConsumer.accept(description);
            }
        });
        return true;
    }

    @Override
    public Optional<NntpGroupStatus> selectGroup(String groupName) {
        ensureReaderMode();
        String command = "GROUP";
        switch (send(command, command, WireText.encode(groupName))) {
            case GROUP_SELECTED -> {
            }
            case NO_SUCH_GROUP -> {
                return Optional.empty();
            }
            case AUTHENTICATION_REQUIRED -> throw loginRequired();
            case ACCESS_DENIED -> throw refused();
            default -> throw unexpectedResponse(command);
        }
        NntpGroupStatus groupStatus = parseGroupStatus(wireClient.getReplyString());
        if (groupStatus == null) {
            broken = true;
            anomalyLog.report(ProtocolAnomaly.builder()
                    .kind(ProtocolAnomalyKind.GROUP_MALFORMED_RESPONSE)
                    .command(command)
                    .group(groupName)
                    .action(ACTION_CONNECTION_CLOSED)
                    .raw(wireClient.getReplyString())
                    .build());
            throw new NntpException(NntpFailureType.PROTOCOL, "Unexpected response to GROUP: " + replyLine());
        }
        selectedGroup = groupName;
        return Optional.of(groupStatus);
    }

    @Override
    public void overviews(long firstNumber, long lastNumber, Consumer<NntpOverview> overviewConsumer) {
        OverviewFormat overviewFormat = overviewFormat();
        // Servers from before RFC 3977 know the command under its old name only.
        String keyword = capabilities.has("OVER") ? "OVER" : "XOVER";
        String range = firstNumber + "-" + lastNumber;
        String command = keyword + " " + range;
        switch (send(command, keyword, range)) {
            case OVERVIEW_FOLLOWS -> {
            }
            // The range has no articles, or the group is empty. Both are normal results.
            case NO_ARTICLES_IN_RANGE, NO_CURRENT_ARTICLE -> {
                return;
            }
            case AUTHENTICATION_REQUIRED -> throw loginRequired();
            case ACCESS_DENIED -> throw refused();
            default -> throw unexpectedResponse(command);
        }
        readList(command, line -> {
            ParsedLine parsedLine = OverviewParser.parse(line, overviewFormat);
            switch (parsedLine.result()) {
                case OVERVIEW -> overviewConsumer.accept(parsedLine.overview());
                case MALFORMED -> reportSkippedOverview(ProtocolAnomalyKind.OVERVIEW_MALFORMED_LINE, command,
                        parsedLine, line);
                case MISSING_MESSAGE_ID -> reportSkippedOverview(ProtocolAnomalyKind.OVERVIEW_MISSING_MESSAGE_ID,
                        command, parsedLine, line);
            }
        });
    }

    @Override
    public Optional<NntpArticle> article(String messageId, int maxBytes) {
        if (!canBeSent(messageId)) {
            log.debug("NNTP server={} is not asked for an article whose Message-ID cannot be sent",
                    serverSettings.serverId());
            return Optional.empty();
        }
        ensureReaderMode();
        String keyword = "ARTICLE";
        String command = keyword + " " + messageId;
        switch (send(command, keyword, WireText.encode(messageId))) {
            case ARTICLE_FOLLOWS -> {
            }
            case NO_SUCH_ARTICLE -> {
                return Optional.empty();
            }
            case AUTHENTICATION_REQUIRED -> throw loginRequired();
            case ACCESS_DENIED -> throw refused();
            default -> throw unexpectedResponse(command);
        }
        try {
            NntpArticle article = wireClient.readLimitedResponse(maxBytes);
            if (!article.complete()) {
                // The rest of the article is still on its way.
                broken = true;
            }
            return Optional.of(article);
        } catch (IOException e) {
            throw failure(e, command);
        }
    }

    @Override
    public NntpPostResult post(String article) {
        ensureReaderMode();
        String command = "POST";
        try {
            Writer articleWriter = wireClient.postArticle();
            if (articleWriter == null) {
                return switch (wireClient.getReplyCode()) {
                    case POSTING_NOT_PERMITTED, ACCESS_DENIED ->
                            new NntpPostResult(NntpPostResult.Outcome.NOT_ALLOWED, replyText());
                    case AUTHENTICATION_REQUIRED ->
                            new NntpPostResult(NntpPostResult.Outcome.AUTHENTICATION_REQUIRED, replyText());
                    default -> throw unexpectedResponse(command);
                };
            }
            // The writer applies dot-stuffing, writes CRLF and ends the article with the terminating dot.
            try (articleWriter) {
                articleWriter.write(WireText.encode(article));
            }
            wireClient.completePendingCommand();
        } catch (IOException e) {
            throw failure(e, command);
        }
        return switch (wireClient.getReplyCode()) {
            case ARTICLE_RECEIVED -> new NntpPostResult(NntpPostResult.Outcome.ACCEPTED, replyText());
            case POSTING_FAILED -> new NntpPostResult(NntpPostResult.Outcome.REJECTED, replyText());
            default -> throw unexpectedResponse(command);
        };
    }

    /** A Message-ID with whitespace or control characters would be read as more than one argument or command. */
    private static boolean canBeSent(String messageId) {
        return !messageId.isEmpty()
                && messageId.chars().noneMatch(character -> character <= ' ' || character == 0x7f);
    }

    /**
     * The order of the overview fields of this server. It is asked with {@code LIST OVERVIEW.FMT} when no connection
     * has asked before. A server that rejects the command, or whose capability list names the variants of
     * {@code LIST} without this one, gets the order of RFC 3977.
     */
    private OverviewFormat overviewFormat() {
        Optional<OverviewFormat> known = overviewFormats.find(serverSettings.serverId());
        if (known.isPresent()) {
            return known.get();
        }
        OverviewFormat overviewFormat = requestOverviewFormat();
        overviewFormats.remember(serverSettings.serverId(), overviewFormat);
        return overviewFormat;
    }

    private OverviewFormat requestOverviewFormat() {
        List<String> listVariants = capabilities.arguments("LIST");
        if (!listVariants.isEmpty() && listVariants.stream().noneMatch(OVERVIEW_FORMAT_VARIANT::equalsIgnoreCase)) {
            return OverviewFormat.RFC_3977;
        }
        String command = "LIST " + OVERVIEW_FORMAT_VARIANT;
        switch (send(command, "LIST", OVERVIEW_FORMAT_VARIANT)) {
            case LIST_FOLLOWS -> {
            }
            case AUTHENTICATION_REQUIRED -> throw loginRequired();
            default -> {
                log.debug("NNTP server={} rejected {}: {}", serverSettings.serverId(), command, replyLine());
                return OverviewFormat.RFC_3977;
            }
        }
        List<String> formatLines = new ArrayList<>();
        readList(command, formatLines::add);
        Optional<OverviewFormat> overviewFormat = OverviewFormat.ofListOverviewFmt(formatLines);
        if (overviewFormat.isEmpty()) {
            anomalyLog.report(ProtocolAnomaly.builder()
                    .kind(ProtocolAnomalyKind.OVERVIEW_UNUSABLE_FORMAT)
                    .command(command)
                    .action("default-order-used")
                    .raw(String.join(" ", formatLines))
                    .build());
            anomalyLog.finish();
        }
        return overviewFormat.orElse(OverviewFormat.RFC_3977);
    }

    /** @return the status, or {@code null} when the line has no article count and two watermarks after its code */
    static NntpGroupStatus parseGroupStatus(String replyLine) {
        String[] fields = replyLine == null ? new String[0] : replyLine.strip().split("\\s+");
        if (fields.length < 4) {
            return null;
        }
        try {
            long articleCount = Long.parseLong(fields[1]);
            long lowWatermark = Long.parseLong(fields[2]);
            long highWatermark = Long.parseLong(fields[3]);
            if (articleCount < 0 || lowWatermark < 0 || highWatermark < 0) {
                return null;
            }
            return new NntpGroupStatus(articleCount, lowWatermark, highWatermark);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void reportSkippedOverview(ProtocolAnomalyKind kind, String command, ParsedLine parsedLine, String line) {
        anomalyLog.report(ProtocolAnomaly.builder()
                .kind(kind)
                .command(command)
                .group(selectedGroup)
                .number(parsedLine.number())
                .action(ACTION_SKIPPED)
                .raw(line)
                .build());
    }

    /**
     * Sends {@code MODE READER} before the first reading command, unless the server said that it is a reading
     * server already. Some servers offer the reading commands only after it, and a capability list may be
     * incomplete, so its silence is no reason to leave the command out (RFC 3977, section 5.3).
     */
    private void ensureReaderMode() {
        if (readerModeEnsured) {
            return;
        }
        if (!capabilities.has("READER")) {
            String command = "MODE READER";
            switch (send(command, "MODE", "READER")) {
                case AUTHENTICATION_REQUIRED -> throw loginRequired();
                case ACCESS_DENIED -> throw refused();
                // 200 and 201 confirm the mode. A server that does not know the command needs no switch.
                default -> {
                }
            }
        }
        readerModeEnsured = true;
    }

    /** @return the response code of the status line */
    private int send(String command, String keyword, String argument) {
        try {
            return wireClient.sendCommand(keyword, argument);
        } catch (IOException e) {
            throw failure(e, command);
        }
    }

    /** Reads a multi-line answer line by line. After a failure in the middle, the connection is not used again. */
    private void readList(String command, Consumer<String> lineConsumer) {
        try {
            wireClient.forEachResponseLine(lineConsumer);
        } catch (IOException e) {
            throw failure(e, command);
        } catch (RuntimeException e) {
            // The receiver of the lines failed; the rest of the answer is still on its way.
            broken = true;
            throw e;
        } finally {
            anomalyLog.finish();
        }
    }

    /** @return the entry, or {@code null} when the line is not a group name, two numbers and a status */
    static NntpActiveGroup parseActiveGroup(String line) {
        String[] fields = line.strip().split("\\s+");
        if (fields.length != 4 || fields[0].isEmpty()) {
            return null;
        }
        try {
            long highWatermark = Long.parseLong(fields[1]);
            long lowWatermark = Long.parseLong(fields[2]);
            if (highWatermark < 0 || lowWatermark < 0) {
                return null;
            }
            return new NntpActiveGroup(WireText.decode(fields[0]), highWatermark, lowWatermark, fields[3]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** @return the description, or {@code null} when the line does not start with a group name */
    static NntpGroupDescription parseGroupDescription(String line) {
        String[] fields = line.split("\\s+", 2);
        if (fields[0].isEmpty()) {
            return null;
        }
        String description = fields.length < 2 ? "" : WireText.decode(fields[1]).strip();
        return new NntpGroupDescription(WireText.decode(fields[0]), description);
    }

    private void reportSkippedLine(ProtocolAnomalyKind kind, String command, String line) {
        anomalyLog.report(ProtocolAnomaly.builder()
                .kind(kind)
                .command(command)
                .action(ACTION_SKIPPED)
                .raw(line)
                .build());
    }

    /** @return whether further commands can be sent on this connection */
    boolean isUsable() {
        return !closed && !broken && wireClient.isConnected();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (wireClient.isConnected()) {
            if (!broken) {
                try {
                    wireClient.setSoTimeout(QUIT_TIMEOUT_MILLIS);
                    wireClient.quit();
                } catch (IOException e) {
                    log.debug("NNTP server={} did not answer QUIT", serverSettings.serverId());
                }
            }
            try {
                wireClient.disconnect();
            } catch (IOException e) {
                log.debug("NNTP server={} could not be disconnected cleanly", serverSettings.serverId());
            }
        }
        anomalyLog.finish();
    }

    private NntpException refused() {
        return new NntpException(NntpFailureType.SERVER_REFUSED, "The server refused access: " + replyLine());
    }

    private NntpException loginRequired() {
        return new NntpException(NntpFailureType.SERVER_REFUSED,
                "The server requires a login (" + AUTHENTICATION_REQUIRED + ")");
    }

    /** The server answered with a response code that the protocol does not allow at this point. */
    private NntpException unexpectedResponse(String command) {
        broken = true;
        anomalyLog.report(ProtocolAnomaly.builder()
                .kind(ProtocolAnomalyKind.RESPONSE_UNEXPECTED_CODE)
                .command(command)
                .action(ACTION_CONNECTION_CLOSED)
                .raw(wireClient.getReplyString())
                .build());
        String where = command == null ? "as greeting" : "to " + command;
        return new NntpException(NntpFailureType.PROTOCOL, "Unexpected response " + where + ": " + replyLine());
    }

    private NntpException failure(IOException e, String command) {
        broken = true;
        if (e instanceof MalformedServerReplyException) {
            anomalyLog.report(ProtocolAnomaly.builder()
                    .kind(ProtocolAnomalyKind.RESPONSE_MALFORMED_LINE)
                    .command(command)
                    .action(ACTION_CONNECTION_CLOSED)
                    .raw(malformedLine(e))
                    .build());
        }
        return NntpFailures.fromIo(e, serverSettings.host(), serverSettings.port());
    }

    /** The library puts the offending line at the end of its message, after "reply: ". */
    private static String malformedLine(IOException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        int marker = message.toLowerCase(Locale.ROOT).lastIndexOf("reply: ");
        return marker < 0 ? message : message.substring(marker + "reply: ".length());
    }

    /** The text of the last answer after its response code, escaped and shortened. */
    private String replyText() {
        return replyLine().replaceFirst("^\\d{3}[ -]?", "").strip();
    }

    private String replyLine() {
        String reply = wireClient.getReplyString();
        return LogSafe.escape(reply == null ? "" : reply.strip(), MESSAGE_LINE_MAX_LENGTH);
    }
}
