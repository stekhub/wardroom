package io.github.stekhub.wardroom.nntp.internal;

import io.github.stekhub.wardroom.nntp.NntpAction;
import io.github.stekhub.wardroom.nntp.NntpException;
import io.github.stekhub.wardroom.nntp.NntpFailureType;
import io.github.stekhub.wardroom.nntp.NntpServerSettings;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The connections to one server. It never opens more than the server's connection limit, and it keeps one of them
 * for interactive actions: background work uses at most {@code maxConnections − 1}, but at least one (spec 005).
 *
 * <p>A connection is reused when the settings are unchanged and it was not idle for too long. A connection on which
 * an action failed is closed, because its protocol state is unknown.
 */
final class ServerConnectionPool {

    private final int maxConnections;
    private final NntpTimings timings;
    private final CommonsNetNntpConnector connector;
    private final Semaphore allConnections;
    private final Semaphore backgroundConnections;
    private final Deque<IdleConnection> idle = new ArrayDeque<>();
    private boolean retired;

    private record IdleConnection(CommonsNetClient client, NntpServerSettings settings, long idleSinceNanos) {
    }

    ServerConnectionPool(int maxConnections, NntpTimings timings, CommonsNetNntpConnector connector) {
        this.maxConnections = maxConnections;
        this.timings = timings;
        this.connector = connector;
        this.allConnections = new Semaphore(maxConnections, true);
        this.backgroundConnections = new Semaphore(Math.max(1, maxConnections - 1), true);
    }

    int maxConnections() {
        return maxConnections;
    }

    <T> T run(NntpServerSettings settings, boolean interactive, NntpAction<T> action) {
        acquire(interactive);
        try {
            CommonsNetClient client = borrow(settings);
            boolean succeeded = false;
            try {
                T result = action.run(client);
                succeeded = true;
                return result;
            } finally {
                if (succeeded && client.isUsable()) {
                    giveBack(client, settings);
                } else {
                    client.close();
                }
            }
        } finally {
            release(interactive);
        }
    }

    /** Closes the unused connections. Connections in use are not affected. */
    void closeIdle() {
        drainIdle().forEach(CommonsNetClient::close);
    }

    /** Closes the unused connections, and every connection in use as soon as its action ends. */
    void retire() {
        synchronized (this) {
            retired = true;
        }
        closeIdle();
    }

    private void acquire(boolean interactive) {
        try {
            if (interactive) {
                if (!allConnections.tryAcquire(timings.interactiveWait().toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new NntpException(NntpFailureType.NO_CONNECTION_AVAILABLE,
                            "All connections to the server are in use");
                }
            } else {
                backgroundConnections.acquire();
                try {
                    allConnections.acquire();
                } catch (InterruptedException e) {
                    backgroundConnections.release();
                    throw e;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NntpException(NntpFailureType.NO_CONNECTION_AVAILABLE,
                    "Interrupted while waiting for a connection to the server", e);
        }
    }

    private void release(boolean interactive) {
        allConnections.release();
        if (!interactive) {
            backgroundConnections.release();
        }
    }

    private CommonsNetClient borrow(NntpServerSettings settings) {
        List<CommonsNetClient> unusable = new ArrayList<>();
        CommonsNetClient reusable = null;
        synchronized (this) {
            while (reusable == null && !idle.isEmpty()) {
                IdleConnection candidate = idle.pop();
                if (candidate.settings().equals(settings) && isFresh(candidate) && candidate.client().isUsable()) {
                    reusable = candidate.client();
                } else {
                    unusable.add(candidate.client());
                }
            }
        }
        unusable.forEach(CommonsNetClient::close);
        return reusable != null ? reusable : connector.open(settings);
    }

    private boolean isFresh(IdleConnection connection) {
        return System.nanoTime() - connection.idleSinceNanos() < timings.idleLifetime().toNanos();
    }

    private void giveBack(CommonsNetClient client, NntpServerSettings settings) {
        synchronized (this) {
            if (!retired) {
                idle.push(new IdleConnection(client, settings, System.nanoTime()));
                return;
            }
        }
        client.close();
    }

    private List<CommonsNetClient> drainIdle() {
        synchronized (this) {
            List<CommonsNetClient> clients = idle.stream().map(IdleConnection::client).toList();
            idle.clear();
            return clients;
        }
    }
}
