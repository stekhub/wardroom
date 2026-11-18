# 0017. Password reset

- Status: Accepted
- Date: 2026-11-18
- Supersedes: the password recovery part of [ADR 0015](0015-account-setup-and-recovery.md)

## Context

ADR 0015 left the MVP without password recovery. A forgotten password would make the instance unusable without starting over.

The Wardroom account mainly protects the stored NNTP credentials and the ability to post with them. Articles are public anyway. A reset must therefore never hand stored credentials to whoever performs it, and it must not be possible from the browser, where anyone who can reach the instance could use it. Everything else (subscriptions, cached articles, read state) is not sensitive and should survive a forgotten password.

## Decision

**Trigger: deleting the account in the database**

- There is no reset function in the UI or API.
- To reset, the owner stops the container, deletes the row in `wardroom_user` with any SQLite tool, and starts the container again.
- Wardroom then finds no account and shows the setup page ([ADR 0015](0015-account-setup-and-recovery.md)).

**Account creation removes what is security-sensitive and keeps the rest**

Creating the account always runs the same steps, in one transaction, together with storing the new account. On a fresh installation there is nothing to do.

1. Delete all sessions, so no browser that was logged in to the previous account is logged in to the new one.
2. Delete the stored NNTP username and password and set the server's authentication to `NONE`.

Everything else is kept: the server's connection settings (name, host, port, TLS mode, connection limit), groups, subscriptions, cached articles, read state and the instance settings. No other table references the account ([ADR 0018](0018-no-user-reference-in-mvp.md)), so deleting its row affects nothing else.

## Consequences

- A forgotten password can be reset by anyone with access to the database file, which is the same trust level as the data itself.
- The new account never inherits NNTP credentials or sessions. After a reset the owner re-enters the NNTP username and password; subscriptions, cache and read state are unchanged.
- The result does not depend on whether the tool used to delete the row enforces foreign keys.
- Between the restart and the new setup, the instance is open to setup, as on first start (ADR 0015).
- A more convenient trigger (a command in the container or a trigger file) can be added later; it would reuse the same steps.
