package io.github.stekhub.wardroom.nntp.internal;

import io.github.stekhub.wardroom.nntp.NntpConnector;
import java.security.NoSuchAlgorithmException;
import javax.net.ssl.SSLContext;

/**
 * Connectors for the tests of other modules. They use short timeouts, so the failure cases do not slow the build
 * down, and they can trust the certificate of the mock server.
 */
public final class TestNntpConnectors {

    private TestNntpConnectors() {
    }

    /** @return a connector with short timeouts that trusts what the given TLS configuration trusts */
    public static NntpConnector trusting(SSLContext clientContext) {
        return new CommonsNetNntpConnector(NntpTestSupport.FAST, clientContext);
    }

    /** @return a connector with short timeouts and the trust store of the Java runtime, like the application */
    public static NntpConnector withRuntimeTrust() {
        try {
            return trusting(SSLContext.getDefault());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
