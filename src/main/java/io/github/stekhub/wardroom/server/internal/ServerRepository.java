package io.github.stekhub.wardroom.server.internal;

import static io.github.stekhub.wardroom.jooq.Tables.NNTP_SERVER;

import io.github.stekhub.wardroom.nntp.NntpTlsMode;
import io.github.stekhub.wardroom.server.GroupListStates.GroupListState;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/** Database access for the NNTP server configuration. */
@Repository
@RequiredArgsConstructor
class ServerRepository {

    private final DSLContext database;

    List<StoredServer> findAll() {
        return database.selectFrom(NNTP_SERVER).orderBy(NNTP_SERVER.ID).fetch(ServerRepository::toStoredServer);
    }

    Optional<StoredServer> findById(long serverId) {
        if (!isStorableId(serverId)) {
            return Optional.empty();
        }
        return database.selectFrom(NNTP_SERVER)
                .where(NNTP_SERVER.ID.eq((int) serverId))
                .fetchOptional(ServerRepository::toStoredServer);
    }

    boolean anyServerExists() {
        return database.fetchExists(NNTP_SERVER);
    }

    /** @return the ID of the new server */
    long insert(StoredServer server) {
        return database.insertInto(NNTP_SERVER)
                .set(NNTP_SERVER.NAME, server.name())
                .set(NNTP_SERVER.HOST, server.host())
                .set(NNTP_SERVER.PORT, server.port())
                .set(NNTP_SERVER.TLS_MODE, server.tlsMode().name())
                .set(NNTP_SERVER.AUTH_METHOD, server.authMethod().name())
                .set(NNTP_SERVER.USERNAME, server.username())
                .set(NNTP_SERVER.PASSWORD_ENCRYPTED, server.passwordEncrypted())
                .set(NNTP_SERVER.MAX_CONNECTIONS, server.maxConnections())
                .set(NNTP_SERVER.CREDENTIALS_INVALID, server.credentialsInvalid())
                .returning(NNTP_SERVER.ID)
                .fetchSingle(NNTP_SERVER.ID);
    }

    /** Writes the configuration of an existing server. The data of other features in the row is not touched. */
    void update(StoredServer server) {
        database.update(NNTP_SERVER)
                .set(NNTP_SERVER.NAME, server.name())
                .set(NNTP_SERVER.HOST, server.host())
                .set(NNTP_SERVER.PORT, server.port())
                .set(NNTP_SERVER.TLS_MODE, server.tlsMode().name())
                .set(NNTP_SERVER.AUTH_METHOD, server.authMethod().name())
                .set(NNTP_SERVER.USERNAME, server.username())
                .set(NNTP_SERVER.PASSWORD_ENCRYPTED, server.passwordEncrypted())
                .set(NNTP_SERVER.MAX_CONNECTIONS, server.maxConnections())
                .set(NNTP_SERVER.CREDENTIALS_INVALID, server.credentialsInvalid())
                .where(NNTP_SERVER.ID.eq((int) server.id()))
                .execute();
    }

    /** @return whether a server was deleted */
    boolean delete(long serverId) {
        return isStorableId(serverId)
                && database.deleteFrom(NNTP_SERVER).where(NNTP_SERVER.ID.eq((int) serverId)).execute() > 0;
    }

    void setCredentialsInvalid(long serverId, boolean credentialsInvalid) {
        if (isStorableId(serverId)) {
            database.update(NNTP_SERVER)
                    .set(NNTP_SERVER.CREDENTIALS_INVALID, credentialsInvalid)
                    .where(NNTP_SERVER.ID.eq((int) serverId))
                    .execute();
        }
    }

    /**
     * Marks the credentials of every server with a stored password as invalid, after the key was lost.
     *
     * @return how many servers have a stored password
     */
    int markStoredCredentialsInvalid() {
        return database.update(NNTP_SERVER)
                .set(NNTP_SERVER.CREDENTIALS_INVALID, true)
                .where(NNTP_SERVER.PASSWORD_ENCRYPTED.isNotNull())
                .execute();
    }

    /** Deletes username and password of every server and sets its authentication to "none" (ADR 0017). */
    void removeAllCredentials() {
        database.update(NNTP_SERVER)
                .setNull(NNTP_SERVER.USERNAME)
                .setNull(NNTP_SERVER.PASSWORD_ENCRYPTED)
                .set(NNTP_SERVER.AUTH_METHOD, AuthMethod.NONE.name())
                .set(NNTP_SERVER.CREDENTIALS_INVALID, false)
                .execute();
    }

    Optional<GroupListState> findGroupListState(long serverId) {
        if (!isStorableId(serverId)) {
            return Optional.empty();
        }
        return database.selectFrom(NNTP_SERVER)
                .where(NNTP_SERVER.ID.eq((int) serverId))
                .fetchOptional(ServerRepository::toGroupListState);
    }

    List<GroupListState> findGroupListStates() {
        return database.selectFrom(NNTP_SERVER).orderBy(NNTP_SERVER.ID).fetch(ServerRepository::toGroupListState);
    }

    /** Stores the time of a successful group list refresh and clears the error of an earlier one. */
    void setGroupListRefreshed(long serverId, long refreshedAt) {
        if (isStorableId(serverId)) {
            database.update(NNTP_SERVER)
                    .set(NNTP_SERVER.GROUPS_REFRESHED_AT, refreshedAt)
                    .setNull(NNTP_SERVER.GROUPS_REFRESH_ERROR)
                    .where(NNTP_SERVER.ID.eq((int) serverId))
                    .execute();
        }
    }

    /** Stores why a group list refresh failed. The time of the last successful refresh is kept. */
    void setGroupListRefreshError(long serverId, String error) {
        if (isStorableId(serverId)) {
            database.update(NNTP_SERVER)
                    .set(NNTP_SERVER.GROUPS_REFRESH_ERROR, error)
                    .where(NNTP_SERVER.ID.eq((int) serverId))
                    .execute();
        }
    }

    /** The ID column holds 32-bit numbers; an ID outside that range cannot belong to a server. */
    private static boolean isStorableId(long serverId) {
        return serverId >= Integer.MIN_VALUE && serverId <= Integer.MAX_VALUE;
    }

    private static GroupListState toGroupListState(Record row) {
        return new GroupListState(row.get(NNTP_SERVER.ID), row.get(NNTP_SERVER.CREDENTIALS_INVALID),
                row.get(NNTP_SERVER.GROUPS_REFRESHED_AT), row.get(NNTP_SERVER.GROUPS_REFRESH_ERROR));
    }

    private static StoredServer toStoredServer(Record row) {
        return new StoredServer(row.get(NNTP_SERVER.ID), row.get(NNTP_SERVER.NAME), row.get(NNTP_SERVER.HOST),
                row.get(NNTP_SERVER.PORT), NntpTlsMode.valueOf(row.get(NNTP_SERVER.TLS_MODE)),
                AuthMethod.valueOf(row.get(NNTP_SERVER.AUTH_METHOD)), row.get(NNTP_SERVER.USERNAME),
                row.get(NNTP_SERVER.PASSWORD_ENCRYPTED), row.get(NNTP_SERVER.MAX_CONNECTIONS),
                row.get(NNTP_SERVER.CREDENTIALS_INVALID));
    }
}
