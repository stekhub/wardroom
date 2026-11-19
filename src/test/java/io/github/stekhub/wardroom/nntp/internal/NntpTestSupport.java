package io.github.stekhub.wardroom.nntp.internal;

import io.github.stekhub.wardroom.nntp.NntpServerSettings;
import io.github.stekhub.wardroom.nntp.NntpTlsMode;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import java.time.Duration;

/** Settings and short waiting times for the NNTP tests. */
final class NntpTestSupport {

    static final String USERNAME = "reader";
    static final String PASSWORD = "s3cret-Passw0rd";

    /** Like ADR 0026, but short enough that the failure cases do not slow the build down. */
    static final NntpTimings FAST = new NntpTimings(Duration.ofSeconds(5), Duration.ofMillis(400),
            Duration.ofMillis(20), Duration.ofMillis(300), Duration.ofMillis(400), Duration.ofSeconds(60));

    private NntpTestSupport() {
    }

    static NntpServerSettings plain(MockNntpServer server, int maxConnections) {
        return new NntpServerSettings(1, "localhost", server.port(), NntpTlsMode.NONE, null, null, maxConnections);
    }

    static NntpServerSettings plain(MockNntpServer server) {
        return plain(server, 4);
    }

    static NntpServerSettings tls(MockNntpServer server) {
        return new NntpServerSettings(1, "localhost", server.port(), NntpTlsMode.IMPLICIT, null, null, 4);
    }

    static NntpServerSettings tlsWithLogin(MockNntpServer server) {
        return new NntpServerSettings(1, "localhost", server.port(), NntpTlsMode.IMPLICIT, USERNAME, PASSWORD, 4);
    }
}
