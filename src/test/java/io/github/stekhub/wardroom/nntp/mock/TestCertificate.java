package io.github.stekhub.wardroom.nntp.mock;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * A self-signed certificate for the TLS tests, created when the test runs with the {@code keytool} of the JDK. No
 * key or certificate is stored in the repository, and nothing can expire.
 */
public final class TestCertificate {

    private static final char[] PASSWORD = "changeit".toCharArray();
    private static final String ALIAS = "mock";

    private final KeyStore keyStore;

    private TestCertificate(KeyStore keyStore) {
        this.keyStore = keyStore;
    }

    /** Creates a certificate that is valid for the given DNS name only. */
    public static TestCertificate forHost(String dnsName) {
        try {
            Path directory = Files.createTempDirectory("wardroom-test-certificate");
            Path file = directory.resolve("keystore.p12");
            try {
                Process keytool = new ProcessBuilder(keytoolPath().toString(), "-genkeypair", "-alias", ALIAS,
                        "-keyalg", "EC", "-dname", "CN=" + dnsName, "-ext", "SAN=dns:" + dnsName, "-validity", "2",
                        "-storetype", "PKCS12", "-keystore", file.toString(), "-storepass", new String(PASSWORD),
                        "-keypass", new String(PASSWORD)).redirectErrorStream(true).start();
                String output = new String(keytool.getInputStream().readAllBytes());
                if (!keytool.waitFor(60, TimeUnit.SECONDS) || keytool.exitValue() != 0) {
                    throw new IllegalStateException("keytool failed: " + output);
                }
                KeyStore keyStore = KeyStore.getInstance("PKCS12");
                try (InputStream input = Files.newInputStream(file)) {
                    keyStore.load(input, PASSWORD);
                }
                return new TestCertificate(keyStore);
            } finally {
                Files.deleteIfExists(file);
                Files.deleteIfExists(directory);
            }
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Could not create a test certificate", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while creating a test certificate", e);
        }
    }

    /** @return the TLS configuration of a server that presents this certificate */
    public SSLContext serverContext() {
        try {
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(keyStore, PASSWORD);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @param mockServer the server that presents this certificate
     * @return the TLS configuration of a client that trusts the certificate it sees when it connects to the
     *         server, and nothing else. Normally that is this certificate. On a machine where security software
     *         intercepts TLS, also for local connections, the client sees a re-signed copy instead; that copy is
     *         then trusted, so the tests can run there as well.
     */
    public SSLContext trustingClientContext(MockNntpServer mockServer) {
        try {
            KeyStore trusted = KeyStore.getInstance("PKCS12");
            trusted.load(null, null);
            trusted.setCertificateEntry(ALIAS, keyStore.getCertificate(ALIAS));
            trusted.setCertificateEntry("as-seen", certificateSeenByClients(mockServer));
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(trusted);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static X509Certificate certificateSeenByClients(MockNntpServer mockServer)
            throws GeneralSecurityException, IOException {
        AtomicReference<X509Certificate> seen = new AtomicReference<>();
        SSLContext acceptAll = SSLContext.getInstance("TLS");
        acceptAll.init(null, new TrustManager[] {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                seen.set(chain[0]);
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, null);
        try (SSLSocket probe = (SSLSocket) acceptAll.getSocketFactory().createSocket("localhost", mockServer.port())) {
            probe.startHandshake();
        }
        return seen.get();
    }

    private static Path keytoolPath() {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "keytool.exe" : "keytool");
    }
}
