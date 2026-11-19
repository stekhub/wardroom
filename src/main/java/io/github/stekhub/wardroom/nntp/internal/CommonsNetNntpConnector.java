package io.github.stekhub.wardroom.nntp.internal;

import io.github.stekhub.wardroom.nntp.NntpClient;
import io.github.stekhub.wardroom.nntp.NntpConnector;
import io.github.stekhub.wardroom.nntp.NntpServerSettings;
import java.security.NoSuchAlgorithmException;
import javax.net.ssl.SSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Opens NNTP connections with Apache Commons Net. */
@Component
public class CommonsNetNntpConnector implements NntpConnector {

    private final NntpTimings timings;
    private final SSLContext sslContext;

    /** Uses the timeouts of ADR 0026 and the trust store of the Java runtime (ADR 0016). */
    @Autowired
    public CommonsNetNntpConnector() {
        this(NntpTimings.DEFAULTS, defaultSslContext());
    }

    CommonsNetNntpConnector(NntpTimings timings, SSLContext sslContext) {
        this.timings = timings;
        this.sslContext = sslContext;
    }

    @Override
    public NntpClient connect(NntpServerSettings serverSettings) {
        return open(serverSettings);
    }

    CommonsNetClient open(NntpServerSettings serverSettings) {
        return CommonsNetClient.open(serverSettings, timings, sslContext);
    }

    private static SSLContext defaultSslContext() {
        try {
            return SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("The Java runtime has no default TLS configuration", e);
        }
    }
}
