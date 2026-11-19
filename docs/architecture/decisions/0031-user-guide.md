# 0031. User guide outside the README

- Status: Accepted
- Date: 2026-11-19
- Supersedes: the documentation location in [ADR 0025](0025-external-encryption-key.md)

## Context

ADR 0025 places the description of the external encryption key in an advanced section of the project README. The implementation plan and [spec 002](../../specs/002-nntp-server-configuration/spec.md) also name the README for backups, updates, the password reset and the lost key file.

The README is the front page of the project. With all of these topics it becomes long, and the topics will grow as the application is built.

## Decision

- The README contains the introduction, the feature list, the quick install with the reference Compose file and its settings, and a link list to the user guide.
- Everything else for people who run Wardroom is in the user guide, [`docs/user-guide.md`](../../user-guide.md): backups and updates, troubleshooting, the external encryption key and building from source.
- The external encryption key is described only in an advanced section of the user guide. As before, the quick install and the reference Compose file do not mention it.

## Consequences

- The README stays short.
- Where older documents name the README for one of these topics, the user guide is meant.
- The user guide is a single file. It can be split into several files later if it grows.
