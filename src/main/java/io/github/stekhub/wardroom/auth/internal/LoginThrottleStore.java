package io.github.stekhub.wardroom.auth.internal;

import java.util.Optional;

/** Where the login throttling keeps its state: with the account (spec 011). */
interface LoginThrottleStore {

    /** @return the stored state, or empty when no account exists */
    Optional<LoginThrottleState> findLoginThrottleState();

    /** @return whether the state was stored; {@code false} when no account exists */
    boolean storeLoginThrottleState(LoginThrottleState state);
}
