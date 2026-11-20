package io.github.stekhub.wardroom.server.internal;

/** How Wardroom logs in to the NNTP server (spec 002). */
enum AuthMethod {

    /** No login. */
    NONE,

    /** Username and password; only with TLS. */
    USER_PASS
}
