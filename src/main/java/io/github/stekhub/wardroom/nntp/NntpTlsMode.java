package io.github.stekhub.wardroom.nntp;

/** How the connection to an NNTP server is secured (ADR 0016). */
public enum NntpTlsMode {

    /** TLS from the first byte, usually on port 563. */
    IMPLICIT,

    /** No encryption, usually on port 119. Credentials are never sent in this mode. */
    NONE
}
