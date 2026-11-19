package io.github.stekhub.wardroom.auth;

/**
 * Removes security-sensitive data that must not pass from a previous account to a new one (ADR 0017).
 *
 * <p>A module that stores such data implements this interface as a Spring bean. Every implementation runs when the
 * account is created, in the same database transaction as storing the account. On a fresh installation there is
 * nothing to remove.
 */
public interface AccountCreationCleanup {

    /** Called inside the transaction that creates the account. Must only change the database. */
    void removeSensitiveData();
}
