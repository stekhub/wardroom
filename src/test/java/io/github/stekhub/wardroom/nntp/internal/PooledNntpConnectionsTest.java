package io.github.stekhub.wardroom.nntp.internal;

import static io.github.stekhub.wardroom.nntp.internal.NntpTestSupport.FAST;
import static io.github.stekhub.wardroom.nntp.internal.NntpTestSupport.plain;
import static io.github.stekhub.wardroom.nntp.internal.NntpTestSupport.tlsWithLogin;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.stekhub.wardroom.nntp.NntpClient;
import io.github.stekhub.wardroom.nntp.NntpException;
import io.github.stekhub.wardroom.nntp.NntpFailureType;
import io.github.stekhub.wardroom.nntp.NntpServerSettings;
import io.github.stekhub.wardroom.nntp.mock.MockNntpServer;
import io.github.stekhub.wardroom.nntp.mock.MockReply;
import io.github.stekhub.wardroom.nntp.mock.TestCertificate;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The connection pool, the retries and the circuit breaker against the mock server (ADR 0006, 0026). */
@ExtendWith(OutputCaptureExtension.class)
class PooledNntpConnectionsTest {

    private MockNntpServer mockServer;
    private PooledNntpConnections connections;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @BeforeEach
    void start() throws Exception {
        mockServer = MockNntpServer.start();
        connections = new PooledNntpConnections(new CommonsNetNntpConnector(FAST, SSLContext.getDefault()), FAST);
    }

    @AfterEach
    void stop() throws IOException {
        executor.shutdownNow();
        connections.destroy();
        mockServer.close();
    }

    @Test
    void connectionIsReusedForTheNextAction() {
        connections.background(plain(mockServer), NntpClient::capabilities);
        connections.interactive(plain(mockServer), NntpClient::capabilities);

        assertThat(mockServer.connectionCount()).isEqualTo(1);
    }

    @Test
    void connectionIsClosedWhenTheActionFails() {
        assertThatThrownBy(() -> connections.background(plain(mockServer), client -> {
            throw new IllegalStateException("the caller's own error");
        })).isInstanceOf(IllegalStateException.class).hasMessage("the caller's own error");

        connections.background(plain(mockServer), NntpClient::capabilities);

        assertThat(mockServer.connectionCount()).isEqualTo(2);
    }

    @Test
    void changedSettingsGetANewConnection() {
        NntpServerSettings before = plain(mockServer);
        NntpServerSettings after = new NntpServerSettings(before.serverId(), "127.0.0.1", before.port(),
                before.tlsMode(), null, null, before.maxConnections());

        connections.background(before, NntpClient::capabilities);
        connections.background(after, NntpClient::capabilities);

        assertThat(mockServer.connectionCount()).isEqualTo(2);
        assertThat(mockServer.commands()).containsOnlyOnce("QUIT");
    }

    @Test
    void connectionThatWasIdleTooLongIsReplaced() {
        connections = withoutReuse();

        connections.background(plain(mockServer), NntpClient::capabilities);
        connections.background(plain(mockServer), NntpClient::capabilities);

        assertThat(mockServer.connectionCount()).isEqualTo(2);
    }

    @Test
    void backgroundWorkLeavesOneConnectionForInteractiveActions() throws Exception {
        NntpServerSettings serverSettings = plain(mockServer, 3);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch twoRunning = new CountDownLatch(2);
        AtomicInteger started = new AtomicInteger();
        List<Future<?>> background = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            background.add(executor.submit(() -> connections.background(serverSettings, client -> {
                started.incrementAndGet();
                twoRunning.countDown();
                await(release);
                return null;
            })));
        }
        assertThat(twoRunning.await(5, TimeUnit.SECONDS)).isTrue();

        // All background connections are busy and a third job is waiting; the interactive action still runs.
        String result = connections.interactive(serverSettings, client -> "interactive ran");

        assertThat(result).isEqualTo("interactive ran");
        assertThat(started).hasValue(2);
        assertThat(mockServer.connectionCount()).isEqualTo(3);

        release.countDown();
        for (Future<?> job : background) {
            job.get(5, TimeUnit.SECONDS);
        }
        assertThat(started).hasValue(3);
        assertThat(mockServer.connectionCount()).isEqualTo(3);
    }

    @Test
    void interactiveActionFailsWhenNoConnectionBecomesFreeInTime() throws Exception {
        NntpServerSettings serverSettings = plain(mockServer, 1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        Future<?> background = executor.submit(() -> connections.background(serverSettings, client -> {
            running.countDown();
            await(release);
            return null;
        }));
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

        NntpException failure = catchThrowableOfType(NntpException.class,
                () -> connections.interactive(serverSettings, NntpClient::capabilities));

        assertThat(failure.type()).isEqualTo(NntpFailureType.NO_CONNECTION_AVAILABLE);
        release.countDown();
        background.get(5, TimeUnit.SECONDS);
        assertThat(mockServer.connectionCount()).isEqualTo(1);
    }

    @Test
    void failedAttemptIsRetriedUntilItSucceeds() {
        mockServer.greeting().once(MockReply.drop()).once(MockReply.lines("400 Try again later"));

        String result = connections.background(plain(mockServer), client -> "done");

        assertThat(result).isEqualTo("done");
        assertThat(mockServer.connectionCount()).isEqualTo(3);
    }

    @Test
    void givesUpAfterThreeAttemptsAndLogsAWarning(CapturedOutput output) {
        mockServer.greeting().reply(MockReply.drop());

        NntpException failure = catchThrowableOfType(NntpException.class,
                () -> connections.background(plain(mockServer), NntpClient::capabilities));

        assertThat(failure.type()).isEqualTo(NntpFailureType.CONNECTION_LOST);
        assertThat(mockServer.connectionCount()).isEqualTo(3);
        assertThat(output).contains("WARN").contains("NNTP request to server 1 failed after 3 attempts");
    }

    @Test
    void actionWithoutRetryRunsOnce() {
        mockServer.greeting().reply(MockReply.drop());

        assertThatThrownBy(() -> connections.interactiveWithoutRetry(plain(mockServer), NntpClient::capabilities))
                .isInstanceOf(NntpException.class);

        assertThat(mockServer.connectionCount()).isEqualTo(1);
    }

    @Test
    void rejectedCredentialsAreNotRetriedAndDoNotOpenTheCircuit() throws IOException {
        TestCertificate certificate = TestCertificate.forHost("localhost");
        try (MockNntpServer tlsServer = MockNntpServer.startTls(certificate.serverContext())) {
            connections = new PooledNntpConnections(
                    new CommonsNetNntpConnector(FAST, certificate.trustingClientContext(tlsServer)), FAST);
            tlsServer.on("AUTHINFO USER").reply("481 Authentication failed");

            for (int i = 0; i < 7; i++) {
                NntpException failure = catchThrowableOfType(NntpException.class,
                        () -> connections.background(tlsWithLogin(tlsServer), NntpClient::capabilities));
                assertThat(failure.type()).isEqualTo(NntpFailureType.AUTH_FAILED);
            }

            // Counted by command, not by connection: creating the client's TLS configuration connects once too.
            assertThat(tlsServer.commands()).filteredOn(command -> command.startsWith("AUTHINFO USER")).hasSize(7);
        }
    }

    @Test
    void circuitOpensAfterFiveFailuresInARowAndClosesAfterASuccessfulTrial(CapturedOutput output)
            throws InterruptedException {
        mockServer.greeting().reply(MockReply.drop());
        NntpServerSettings serverSettings = plain(mockServer);

        assertThat(failureOf(serverSettings)).isEqualTo(NntpFailureType.CONNECTION_LOST);
        // The fourth and fifth failure open the circuit, which ends the retries.
        assertThat(failureOf(serverSettings)).isEqualTo(NntpFailureType.CIRCUIT_OPEN);
        assertThat(mockServer.connectionCount()).isEqualTo(5);
        assertThat(output).contains("Circuit opened for NNTP server 1");

        // While the circuit is open the server is not contacted.
        assertThat(failureOf(serverSettings)).isEqualTo(NntpFailureType.CIRCUIT_OPEN);
        assertThat(mockServer.connectionCount()).isEqualTo(5);

        // After the waiting time one trial is allowed. It succeeds, and the circuit closes.
        mockServer.greeting().reply("200 Back again");
        Thread.sleep(FAST.circuitOpenDuration().plusMillis(100));
        connections.background(serverSettings, NntpClient::capabilities);
        connections.background(serverSettings, NntpClient::capabilities);

        assertThat(mockServer.connectionCount()).isEqualTo(6);
        assertThat(output).contains("Circuit closed again for NNTP server 1");
    }

    @Test
    void failedTrialOpensTheCircuitAgain() throws InterruptedException {
        mockServer.greeting().reply(MockReply.drop());
        NntpServerSettings serverSettings = plain(mockServer);
        failureOf(serverSettings);
        failureOf(serverSettings);
        assertThat(mockServer.connectionCount()).isEqualTo(5);

        Thread.sleep(FAST.circuitOpenDuration().plusMillis(100));

        // One trial reaches the server and fails; the retry is refused by the circuit that opened again.
        assertThat(failureOf(serverSettings)).isEqualTo(NntpFailureType.CIRCUIT_OPEN);
        assertThat(mockServer.connectionCount()).isEqualTo(6);
    }

    @Test
    void successBetweenFailuresKeepsTheCircuitClosed() {
        // Without reuse every action opens a new connection, so every action meets the scripted greetings.
        connections = withoutReuse();
        NntpServerSettings serverSettings = plain(mockServer);
        for (int round = 0; round < 3; round++) {
            // Two failures, then the third attempt succeeds: six failures in total, but never five in a row.
            mockServer.greeting().once(MockReply.drop()).once(MockReply.drop());
            connections.background(serverSettings, NntpClient::capabilities);
        }

        connections.background(serverSettings, NntpClient::capabilities);

        assertThat(mockServer.connectionCount()).isEqualTo(10);
    }

    @Test
    void resetClosesTheCircuitAndTheIdleConnections() {
        NntpServerSettings serverSettings = plain(mockServer);
        connections.background(serverSettings, NntpClient::capabilities);
        mockServer.greeting().reply(MockReply.drop());
        connections.reset(serverSettings.serverId());
        assertThat(mockServer.commands()).containsOnlyOnce("QUIT");
        failureOf(serverSettings);
        assertThat(failureOf(serverSettings)).isEqualTo(NntpFailureType.CIRCUIT_OPEN);

        mockServer.greeting().reply("200 Ready");
        connections.reset(serverSettings.serverId());

        connections.background(serverSettings, NntpClient::capabilities);
    }

    private NntpFailureType failureOf(NntpServerSettings serverSettings) {
        NntpException failure = catchThrowableOfType(NntpException.class,
                () -> connections.background(serverSettings, NntpClient::capabilities));
        assertThat(failure).as("the request must fail").isNotNull();
        return failure.type();
    }

    private static PooledNntpConnections withoutReuse() {
        NntpTimings shortLived = new NntpTimings(FAST.connectTimeout(), FAST.readTimeout(), FAST.firstRetryDelay(),
                FAST.interactiveWait(), FAST.circuitOpenDuration(), Duration.ZERO);
        return new PooledNntpConnections(new CommonsNetNntpConnector(shortLived, sslContext()), shortLived);
    }

    private static SSLContext sslContext() {
        try {
            return SSLContext.getDefault();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The test did not release the action");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
