package io.github.stekhub.wardroom.nntp.internal;

import io.github.stekhub.wardroom.platform.LogSafe;
import java.util.Locale;
import org.apache.commons.net.ProtocolCommandEvent;
import org.apache.commons.net.ProtocolCommandListener;
import org.slf4j.Logger;

/**
 * Logs NNTP commands and response codes at {@code DEBUG} (ADR 0029). The argument of {@code AUTHINFO PASS} is
 * masked, and everything is escaped because response lines come from the server.
 */
final class CommandLog implements ProtocolCommandListener {

    static final String MASK = "****";

    private static final int LINE_MAX_LENGTH = 200;
    private static final String PASSWORD_COMMAND = "AUTHINFO PASS";

    private final Logger log;
    private final long serverId;

    CommandLog(Logger log, long serverId) {
        this.log = log;
        this.serverId = serverId;
    }

    @Override
    public void protocolCommandSent(ProtocolCommandEvent event) {
        if (log.isDebugEnabled()) {
            log.debug("NNTP server={} > {}", serverId, LogSafe.escape(mask(event.getMessage()), LINE_MAX_LENGTH));
        }
    }

    @Override
    public void protocolReplyReceived(ProtocolCommandEvent event) {
        if (log.isDebugEnabled()) {
            log.debug("NNTP server={} < {}", serverId, LogSafe.escape(strip(event.getMessage()), LINE_MAX_LENGTH));
        }
    }

    /** @return the command line without its line ending, and without the password if it carries one */
    static String mask(String commandLine) {
        String line = strip(commandLine);
        if (line.toUpperCase(Locale.ROOT).startsWith(PASSWORD_COMMAND)) {
            return PASSWORD_COMMAND + " " + MASK;
        }
        return line;
    }

    private static String strip(String line) {
        return line == null ? "" : line.strip();
    }
}
