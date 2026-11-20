package io.github.stekhub.wardroom.nntp.mock;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;

/**
 * A scriptable NNTP server for tests (ADR 0008). It listens on a free local port, sends a greeting, and answers
 * every command with the reply scripted for it. Replies can break the protocol or the connection on purpose.
 *
 * <pre>{@code
 * try (MockNntpServer server = MockNntpServer.start()) {
 *     server.on("CAPABILITIES").reply("101 Capability list:", "VERSION 2", "READER", ".");
 *     server.on("GROUP").once(MockReply.drop()).reply("211 2 1 2 example.group");
 *     ...
 *     assertThat(server.commands()).containsExactly("CAPABILITIES", "QUIT");
 * }
 * }</pre>
 *
 * <p>A command is matched by its beginning, without regard to case; the longest scripted beginning wins. Unscripted
 * commands get {@code 500}. {@code QUIT} gets {@code 205} and closes the connection unless scripted otherwise.
 */
public final class MockNntpServer implements AutoCloseable {

    private static final MockReply UNKNOWN_COMMAND = MockReply.lines("500 Unknown command");

    /** How long {@link #close()} waits at most until the port refuses connections. */
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(2);

    private final ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Script> scripts = new ConcurrentHashMap<>();
    private final Script greeting = new Script().reply("200 Mock NNTP server ready, posting allowed");
    private final List<String> commands = new CopyOnWriteArrayList<>();
    private final List<String> postedArticles = new CopyOnWriteArrayList<>();
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();
    private final AtomicInteger connectionCount = new AtomicInteger();

    private MockNntpServer(ServerSocket serverSocket) {
        this.serverSocket = serverSocket;
        on("QUIT").reply("205 Bye");
        executor.execute(this::acceptConnections);
    }

    /** Starts a server without TLS. */
    public static MockNntpServer start() throws IOException {
        return new MockNntpServer(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
    }

    /** Starts a server with implicit TLS that presents the certificate of the given context. */
    public static MockNntpServer startTls(SSLContext serverContext) throws IOException {
        return new MockNntpServer(serverContext.getServerSocketFactory()
                .createServerSocket(0, 50, InetAddress.getLoopbackAddress()));
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    /** @return the script for the greeting that is sent when a client connects */
    public Script greeting() {
        return greeting;
    }

    /** @return the script for commands that start with the given text, for example {@code "AUTHINFO USER"} */
    public Script on(String commandStart) {
        return scripts.computeIfAbsent(commandStart.toUpperCase(Locale.ROOT), key -> new Script());
    }

    /** @return every command line received so far, over all connections, in order of arrival */
    public List<String> commands() {
        return List.copyOf(commands);
    }

    /** @return the articles received through {@code POST}, exactly as sent, with CRLF line endings */
    public List<String> postedArticles() {
        return List.copyOf(postedArticles);
    }

    /** @return how many connections clients have opened so far */
    public int connectionCount() {
        return connectionCount.get();
    }

    /** @return how many connections are open right now */
    public int openConnections() {
        return openSockets.size();
    }

    @Override
    public void close() throws IOException {
        InetSocketAddress address = new InetSocketAddress(serverSocket.getInetAddress(), serverSocket.getLocalPort());
        serverSocket.close();
        awaitRefusedConnections(address);
        for (Socket socket : openSockets) {
            socket.close();
        }
        executor.shutdownNow();
    }

    /**
     * Returns when the port refuses connections. For a moment after the listening socket is closed, the operating
     * system can still accept a connection and reset it afterwards; a test that expects "connection refused" must
     * not see that.
     */
    private static void awaitRefusedConnections(InetSocketAddress address) {
        long deadline = System.nanoTime() + CLOSE_WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            try (Socket probe = new Socket()) {
                probe.connect(address, (int) CLOSE_WAIT.toMillis());
            } catch (IOException e) {
                return;
            }
            Thread.onSpinWait();
        }
    }

    /** The replies to one command: some that are given once, in order, and then one that is given every time. */
    public final class Script {

        private final Deque<MockReply> once = new ArrayDeque<>();
        private MockReply always = UNKNOWN_COMMAND;

        /** Adds a reply that is given a single time, before the permanent one. */
        public synchronized Script once(MockReply reply) {
            once.add(reply);
            return this;
        }

        /** Sets the reply that is given every time, after the single ones are used up. */
        public synchronized Script reply(MockReply reply) {
            always = reply;
            return this;
        }

        /** Short for {@code reply(MockReply.lines(lines))}. */
        public Script reply(String... lines) {
            return reply(MockReply.lines(lines));
        }

        private synchronized MockReply next() {
            MockReply single = once.poll();
            return single != null ? single : always;
        }
    }

    private void acceptConnections() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                connectionCount.incrementAndGet();
                openSockets.add(socket);
                executor.execute(() -> serve(socket));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            InputStream input = new BufferedInputStream(socket.getInputStream());
            OutputStream output = socket.getOutputStream();
            if (!perform(greeting.next(), input, output)) {
                return;
            }
            String line;
            while ((line = readLine(input)) != null) {
                commands.add(line);
                if (!perform(replyTo(line), input, output) || line.equalsIgnoreCase("QUIT")) {
                    return;
                }
            }
        } catch (IOException | InterruptedException e) {
            // The client went away or the test ended; nothing to do.
        } finally {
            openSockets.remove(socket);
        }
    }

    private MockReply replyTo(String line) {
        String upper = line.toUpperCase(Locale.ROOT);
        String best = null;
        for (String start : scripts.keySet()) {
            boolean matches = upper.equals(start) || upper.startsWith(start + " ");
            if (matches && (best == null || start.length() > best.length())) {
                best = start;
            }
        }
        return best == null ? UNKNOWN_COMMAND : scripts.get(best).next();
    }

    /** @return whether the connection stays open */
    private boolean perform(MockReply reply, InputStream input, OutputStream output)
            throws IOException, InterruptedException {
        if (!reply.delay().isZero()) {
            Thread.sleep(reply.delay());
        }
        switch (reply.kind()) {
            case SEND -> send(output, reply.data());
            case DROP -> {
                return false;
            }
            case CUT_OFF -> {
                send(output, reply.data());
                return false;
            }
            case SILENCE -> {
                while (input.read() >= 0) {
                    // Swallow whatever the client sends until it gives up and closes the connection.
                }
                return false;
            }
            case ARTICLE -> {
                send(output, reply.data());
                List<String> lines = new ArrayList<>();
                String line;
                while ((line = readLine(input)) != null && !line.equals(".")) {
                    lines.add(line);
                }
                postedArticles.add(String.join("\r\n", lines) + "\r\n");
                send(output, reply.rest());
            }
            case TWO_PARTS -> {
                send(output, reply.data());
                reply.gate().await();
                send(output, reply.rest());
            }
        }
        return true;
    }

    private static void send(OutputStream output, byte[] bytes) throws IOException {
        output.write(bytes);
        output.flush();
    }

    /** Reads one line ended by CRLF, as bytes, so that nothing is lost to a character set. */
    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int nextByte;
        while ((nextByte = input.read()) >= 0) {
            if (nextByte == '\n') {
                String text = line.toString(StandardCharsets.ISO_8859_1);
                return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
            }
            line.write(nextByte);
        }
        return null;
    }
}
