package io.github.stekhub.wardroom.auth.internal;

import java.util.Optional;

/** Where the login throttling keeps its state: with the account (spec 011). */
interface LoginThrottleStore {

    /** @return the stored state of the account with exactly this username, or empty when there is none */
    Optional<LoginThrottleState> findLoginThrottleState(String username);

    /** Stores the state of the account with exactly this username; does nothing when there is none. */
    void storeLoginThrottleState(String username, LoginThrottleState state);
}
