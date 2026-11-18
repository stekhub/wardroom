# 0018. No user reference on per-user data in the MVP

- Status: Accepted
- Date: 2026-11-18
- Supersedes: the per-user data part of [ADR 0012](0012-data-model.md)

## Context

ADR 0012 gives subscriptions and read state a `user_id`, so that several Wardroom users could be added later. The MVP has exactly one user, and multiple users are not planned (brief §4, §11). The column carries no information, and it complicates the password reset: deleting the account row affects these tables differently depending on whether the tool used enforces foreign keys ([ADR 0017](0017-password-reset.md)).

## Decision

- `subscription` and `read_state` have no `user_id`. They belong to the instance, like the server.
- `subscription` is unique on `newsgroup_id`; `read_state` is unique on `article_id`.
- `wardroom_user` is used only for authentication and is referenced by no other table.

### Changes to the brief

- Brief §7 suggests uniqueness on `(wardroom_user_id, server_newsgroup_id)` for subscriptions. Subscriptions are unique on the group alone.

## Consequences

- Deleting the account row never affects other tables, regardless of the tool used.
- If multiple users are introduced, a migration adds `user_id` to `subscription` and `read_state` and fills it with the existing user. NNTP credentials move into a per-user table at the same time (ADR 0012).
