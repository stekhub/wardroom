package io.github.stekhub.wardroom.server.internal;

import io.github.stekhub.wardroom.nntp.NntpConnections;
import io.github.stekhub.wardroom.nntp.NntpException;
import io.github.stekhub.wardroom.nntp.NntpFailureType;
import io.github.stekhub.wardroom.nntp.NntpServerSettings;
import io.github.stekhub.wardroom.server.NntpServers;
import io.github.stekhub.wardroom.server.ServerCreated;
import io.github.stekhub.wardroom.server.ServerRemovalCleanup;
import io.github.stekhub.wardroom.server.internal.ServerFailures.ServerExistsException;
import io.github.stekhub.wardroom.server.internal.ServerFailures.ServerNotFoundException;
import io.github.stekhub.wardroom.server.internal.ServerRequests.ConnectionFailureType;
import io.github.stekhub.wardroom.server.internal.ServerRequests.ConnectionTestRequest;
import io.github.stekhub.wardroom.server.internal.ServerRequests.ConnectionTestResult;
import io.github.stekhub.wardroom.server.internal.ServerRequests.SavedServer;
import io.github.stekhub.wardroom.server.internal.ServerRequests.ServerRequest;
import io.github.stekhub.wardroom.server.internal.ServerRequests.ServerResponse;
import io.github.stekhub.wardroom.server.internal.ServerRules.ValidServer;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/** Adding, testing, editing and removing the NNTP server, and its stored credentials (spec 002). */
@Slf4j
@Service
@RequiredArgsConstructor
class ServerService implements NntpServers {

    private static final String PASSWORD_UNREADABLE =
            "The stored password can no longer be read. Enter the password again.";

    private final ServerRepository serverRepository;
    private final CredentialCipher credentialCipher;
    private final ConnectionTester connectionTester;
    private final NntpConnections connections;
    private final TransactionOperations transactionOperations;
    private final ApplicationEventPublisher eventPublisher;
    /**
     * Looked up when a server is removed, not when this service is created: the modules that clean up also ask
     * this service for the settings of the server.
     */
    private final ObjectProvider<ServerRemovalCleanup> removalCleanups;

    /** Servers are saved one after the other, so two requests at the same time cannot both add a server. */
    private final ReentrantLock saveLock = new ReentrantLock();

    List<StoredServer> servers() {
        return serverRepository.findAll();
    }

    /**
     * Adds the server, then tests the connection. The server is saved also when the test fails, so a server that
     * is unreachable for the moment can be set up.
     */
    SavedServer create(ServerRequest request) {
        ValidServer validServer = ServerRules.validate(request, false);
        StoredServer created;
        saveLock.lock();
        try {
            created = transactionOperations.execute(status -> {
                if (serverRepository.anyServerExists()) {
                    throw new ServerExistsException();
                }
                StoredServer newServer = toStoredServer(0, validServer, encrypted(validServer.newPassword()), false);
                long serverId = serverRepository.insert(newServer);
                eventPublisher.publishEvent(new ServerCreated(serverId));
                return toStoredServer(serverId, validServer, newServer.passwordEncrypted(), false);
            });
        } finally {
            saveLock.unlock();
        }
        log.info("The server {} ({}:{}) was added", created.id(), created.host(), created.port());
        return testAfterSaving(created, validServer.newPassword());
    }

    /**
     * Changes the server, then tests the connection. Groups, subscriptions, cache and read state are kept, also
     * when the host changes: providers often serve the same articles under several host names.
     */
    SavedServer update(long serverId, ServerRequest request) {
        StoredServer updated;
        ValidServer validServer;
        saveLock.lock();
        try {
            StoredServer before = serverRepository.findById(serverId).orElseThrow(ServerNotFoundException::new);
            validServer = ServerRules.validate(request,
                    before.authMethod() == AuthMethod.USER_PASS && before.passwordStored());
            updated = toStoredServer(serverId, validServer, passwordAfterUpdate(before, validServer),
                    before.credentialsInvalid() && !credentialsChange(before, validServer));
            serverRepository.update(updated);
        } finally {
            saveLock.unlock();
        }
        log.info("The server {} ({}:{}) was changed", updated.id(), updated.host(), updated.port());
        return testAfterSaving(updated, validServer.newPassword());
    }

    /** Removes the server and, in the same transaction, everything other modules store for it. */
    void remove(long serverId) {
        transactionOperations.executeWithoutResult(status -> {
            if (serverRepository.findById(serverId).isEmpty()) {
                throw new ServerNotFoundException();
            }
            removalCleanups.forEach(removalCleanup -> removalCleanup.removeServerData(serverId));
            serverRepository.delete(serverId);
        });
        connections.reset(serverId);
        log.info("The server {} was removed", serverId);
    }

    /** Tests the values of the form without saving them. An empty password uses the one stored for the server. */
    ConnectionTestResult test(ConnectionTestRequest request) {
        StoredServer stored = request.id() == null
                ? null
                : serverRepository.findById(request.id()).orElseThrow(ServerNotFoundException::new);
        boolean storedPasswordAvailable = stored != null && stored.authMethod() == AuthMethod.USER_PASS
                && stored.passwordStored();
        ValidServer validServer = ServerRules.validate(request.server(), storedPasswordAvailable);
        long serverId = stored == null ? 0 : stored.id();
        try {
            String password = validServer.authMethod() == AuthMethod.NONE || validServer.newPassword() != null
                    ? validServer.newPassword()
                    : decryptedPassword(stored);
            return connectionTester.test(new NntpServerSettings(serverId, validServer.host(), validServer.port(),
                    validServer.tlsMode(), validServer.username(), password, validServer.maxConnections()));
        } catch (CredentialDecryptionException e) {
            return ConnectionTestResult.failed(ConnectionFailureType.AUTH_FAILED, PASSWORD_UNREADABLE);
        }
    }

    @Override
    public Optional<NntpServerSettings> connectionSettings(long serverId) {
        Optional<StoredServer> stored = serverRepository.findById(serverId);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(connectionSettings(stored.get(), null));
        } catch (CredentialDecryptionException e) {
            throw new NntpException(NntpFailureType.AUTH_FAILED, PASSWORD_UNREADABLE);
        }
    }

    @Override
    public boolean credentialsInvalid(long serverId) {
        return serverRepository.findById(serverId).map(StoredServer::credentialsInvalid).orElse(false);
    }

    @Override
    public void markCredentialsInvalid(long serverId) {
        serverRepository.setCredentialsInvalid(serverId, true);
        log.warn("The server {} rejected the stored username and password. Enter them again in the server "
                + "settings.", serverId);
    }

    /**
     * Saving closes the idle connections and the circuit breaker of the server, then runs the connection test with
     * what was saved. A login that the server rejects marks the credentials as invalid; one that works clears the
     * mark.
     *
     * @param newPassword the password that was just saved, or {@code null} when the stored one was kept
     */
    private SavedServer testAfterSaving(StoredServer saved, String newPassword) {
        connections.reset(saved.id());
        ConnectionTestResult connectionTest;
        try {
            connectionTest = connectionTester.test(connectionSettings(saved, newPassword));
        } catch (CredentialDecryptionException e) {
            connectionTest = ConnectionTestResult.failed(ConnectionFailureType.AUTH_FAILED, PASSWORD_UNREADABLE);
        }
        if (saved.authMethod() == AuthMethod.USER_PASS) {
            boolean rejected = !connectionTest.success()
                    && connectionTest.failure().type() == ConnectionFailureType.AUTH_FAILED;
            if (rejected || connectionTest.success()) {
                serverRepository.setCredentialsInvalid(saved.id(), rejected);
            }
        }
        StoredServer current = serverRepository.findById(saved.id()).orElse(saved);
        return new SavedServer(ServerResponse.of(current), connectionTest);
    }

    /** @param knownPassword the password in clear text when the caller has it, otherwise the stored one is used */
    private NntpServerSettings connectionSettings(StoredServer server, String knownPassword)
            throws CredentialDecryptionException {
        String password = null;
        if (server.authMethod() == AuthMethod.USER_PASS) {
            password = knownPassword != null ? knownPassword : decryptedPassword(server);
        }
        return new NntpServerSettings(server.id(), server.host(), server.port(), server.tlsMode(), server.username(),
                password, server.maxConnections());
    }

    /** A password that cannot be decrypted marks the credentials as invalid; trying again would not help. */
    private String decryptedPassword(StoredServer server) throws CredentialDecryptionException {
        try {
            return credentialCipher.decrypt(server.passwordEncrypted());
        } catch (CredentialDecryptionException e) {
            serverRepository.setCredentialsInvalid(server.id(), true);
            log.warn("The stored password of the server {} cannot be decrypted: {}. Enter the password again in "
                    + "the server settings.", server.id(), e.getMessage());
            throw e;
        }
    }

    private byte[] passwordAfterUpdate(StoredServer before, ValidServer validServer) {
        if (validServer.authMethod() == AuthMethod.NONE) {
            return null;
        }
        return validServer.newPassword() == null ? before.passwordEncrypted() : encrypted(validServer.newPassword());
    }

    private static boolean credentialsChange(StoredServer before, ValidServer validServer) {
        return before.authMethod() != validServer.authMethod()
                || !Objects.equals(before.username(), validServer.username())
                || validServer.newPassword() != null;
    }

    private byte[] encrypted(String password) {
        return password == null ? null : credentialCipher.encrypt(password);
    }

    private static StoredServer toStoredServer(long serverId, ValidServer validServer, byte[] passwordEncrypted,
            boolean credentialsInvalid) {
        return new StoredServer(serverId, validServer.name(), validServer.host(), validServer.port(),
                validServer.tlsMode(), validServer.authMethod(), validServer.username(), passwordEncrypted,
                validServer.maxConnections(), credentialsInvalid);
    }
}
