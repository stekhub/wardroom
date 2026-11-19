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

    private MockNntpServer server;
    private PooledNntpConnections connections;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

    @BeforeEach
    void start() throws Exception {
        server = MockNntpServer.start();
        connections = new PooledNntpConnections(new CommonsNetNntpConnector(FAST, SSLContext.getDefault()), FAST);
    }

    @AfterEach
    void stop() throws IOException {
        threads.shutdownNow();
        connections.destroy();
        server.close();
    }

    @Test
    void connectionIsReusedForTheNextAction() {
        connections.background(plain(server), NntpClient::capabilities);
        connections.interactive(plain(server), NntpClient::capabilities);

        assertThat(server.connectionCount()).isEqualTo(1);
    }

    @Test
    void connectionIsClosedWhenTheActionFails() {
        assertThatThrownBy(() -> connections.background(plain(server), client -> {
            throw new IllegalStateException("the caller's own error");
        })).isInstanceOf(IllegalStateException.class).hasMessage("the caller's own error");

        connections.background(plain(server), NntpClient::capabilities);

        assertThat(server.connectionCount()).isEqualTo(2);
    }

    @Test
    void changedSettingsGetANewConnection() {
        NntpServerSettings before = plain(server);
        NntpServerSettings after = new NntpServerSettings(before.serverId(), "127.0.0.1", before.port(),
                before.tlsMode(), null, null, before.maxConnections());

        connections.background(before, NntpClient::capabilities);
        connections.background(after, NntpClient::capabilities);

        assertThat(server.connectionCount()).isEqualTo(2);
        assertThat(server.commands()).containsOnlyOnce("QUIT");
    }

    @Test
    void connectionThatWasIdleTooLongIsReplaced() {
        connections = withoutReuse();

        connections.background(plain(server), NntpClient::capabilities);
        connections.background(plain(server), NntpClient::capabilities);

        assertThat(server.connectionCount()).isEqualTo(2);
    }

    @Test
    void backgroundWorkLeavesOneConnectionForInteractiveActions() throws Exception {
        NntpServerSettings settings = plain(server, 3);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch twoRunning = new CountDownLatch(2);
        AtomicInteger started = new AtomicInteger();
        List<Future<?>> background = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            background.add(threads.submit(() -> connections.background(settings, client -> {
                started.incrementAndGet();
                twoRunning.countDown();
                await(release);
                return null;
            })));
        }
        assertThat(twoRunning.await(5, TimeUnit.SECONDS)).isTrue();

        // All background connections are busy and a third job is waiting; the interactive action still runs.
        String result = connections.interactive(settings, client -> "interactive ran");

        assertThat(result).isEqualTo("interactive ran");
        assertThat(started).hasValue(2);
        assertThat(server.connectionCount()).isEqualTo(3);

        release.countDown();
        for (Future<?> job : background) {
            job.get(5, TimeUnit.SECONDS);
        }
        assertThat(started).hasValue(3);
        assertThat(server.connectionCount()).isEqualTo(3);
    }

    @Test
    void interactiveActionFailsWhenNoConnectionBecomesFreeInTime() throws Exception {
        NntpServerSettings settings = plain(server, 1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        Future<?> background = threads.submit(() -> connections.background(settings, client -> {
            running.countDown();
            await(release);
            return null;
        }));
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

        NntpException failure = catchThrowableOfType(NntpException.class,
                () -> connections.interactive(settings, NntpClient::capabilities));

        assertThat(failure.type()).isEqualTo(NntpFailureType.NO_CONNECTION_AVAILABLE);
        release.countDown();
        background.get(5, TimeUnit.SECONDS);
        assertThat(server.connectionCount()).isEqualTo(1);
    }

    @Test
    void failedAttemptIsRetriedUntilItSucceeds() {
        server.greeting().once(MockReply.drop()).once(MockReply.lines("400 Try again later"));

        String result = connections.background(plain(server), client -> "done");

        assertThat(result).isEqualTo("done");
        assertThat(server.connectionCount()).isEqualTo(3);
    }

    @Test
    void givesUpAfterThreeAttemptsAndLogsAWarning(CapturedOutput output) {
        server.greeting().reply(MockReply.drop());

        NntpException failure = catchThrowableOfType(NntpException.class,
                () -> connections.background(plain(server), NntpClient::capabilities));

        assertThat(failure.type()).isEqualTo(NntpFailureType.CONNECTION_LOST);
        assertThat(server.connectionCount()).isEqualTo(3);
        assertThat(output).contains("WARN").contains("NNTP request to server 1 failed after 3 attempts");
    }

    @Test
    void actionWithoutRetryRunsOnce() {
        server.greeting().reply(MockReply.drop());

        assertThatThrownBy(() -> connections.interactiveWithoutRetry(plain(server), NntpClient::capabilities))
                .isInstanceOf(NntpException.class);

        assertThat(server.connectionCount()).isEqualTo(1);
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
        server.greeting().reply(MockReply.drop());
        NntpServerSettings settings = plain(server);

        assertThat(failureOf(settings)).isEqualTo(NntpFailureType.CONNECTION_LOST);
        // The fourth and fifth failure open the circuit, which ends the retries.
        assertThat(failureOf(settings)).isEqualTo(NntpFailureType.CIRCUIT_OPEN);
        assertThat(server.connectionCount()).isEqualTo(5);
        assertThat(output).contains("Circuit opened for NNTP server 1");

        // While the circuit is open the server is not contacted.
        assertThat(failureOf(settings)).isEqualTo(NntpFailureType.CIRCUIT_OPEN);
        assertThat(server.connectionCount()).isEqualTo(5);

        // After the waiting time one trial is allowed. It succeeds, and the circuit closes.
        server.greeting().reply("200 Back again");
        Thread.sleep(FAST.circuitOpenDuration().plusMillis(100));
        connections.background(settings, NntpClient::capabilities);
        connections.background(settings, NntpClient::capabilities);

        assertThat(server.connectionCount()).isEqualTo(6);
        assertThat(output).contains("Circuit closed again for NNTP server 1");
    }

    @Test
    void failedTrialOpensTheCircuitAgain() throws InterruptedException {
        server.greeting().reply(MockReply.drop());
        NntpServerSettings settings = plain(server);
        failureOf(settings);
        failureOf(settings);
        assertThat(server.connectionCount()).isEqualTo(5);

        Thread.sleep(FAST.circuitOpenDuration().plusMillis(100));

        // One trial reaches the server and fails; the retry is refused by the circuit that opened again.
        assertThat(failureOf(settings)).isEqualTo(NntpFailureType.CIRCUIT_OPEN);
        assertThat(server.connectionCount()).isEqualTo(6);
    }

    @Test
    void successBetweenFailuresKeepsTheCircuitClosed() {
        // Without reuse every action opens a new connection, so every action meets the scripted greetings.
        connections = withoutReuse();
        NntpServerSettings settings = plain(server);
        for (int round = 0; round < 3; round++) {
            // Two failures, then the third attempt succeeds: six failures in total, but never five in a row.
            server.greeting().once(MockReply.drop()).once(MockReply.drop());
            connections.background(settings, NntpClient::capabilities);
        }

        connections.background(settings, NntpClient::capabilities);

        assertThat(server.connectionCount()).isEqualTo(10);
    }

    @Test
    void resetClosesTheCircuitAndTheIdleConnections() {
        NntpServerSettings settings = plain(server);
        connections.background(settings, NntpClient::capabilities);
        server.greeting().reply(MockReply.drop());
        connections.reset(settings.serverId());
        assertThat(server.commands()).containsOnlyOnce("QUIT");
        failureOf(settings);
        assertThat(failureOf(settings)).isEqualTo(NntpFailureType.CIRCUIT_OPEN);

        server.greeting().reply("200 Ready");
        connections.reset(settings.serverId());

        connections.background(settings, NntpClient::capabilities);
    }

    private NntpFailureType failureOf(NntpServerSettings settings) {
        NntpException failure = catchThrowableOfType(NntpException.class,
                () -> connections.background(settings, NntpClient::capabilities));
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
