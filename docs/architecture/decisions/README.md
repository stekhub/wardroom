# Architecture Decision Records

This directory records significant decisions about Wardroom's architecture, technology and project setup.

## Process

- One decision per file, named `NNNN-short-title.md`, numbered sequentially.
- Each record has a status: `Proposed`, `Accepted`, `Superseded by NNNN` or `Deprecated`.
- Accepted records are not rewritten. A changed decision gets a new record that supersedes the old one.
- Where a record changes direction set in the [project brief](../../product/brief.md), it says so explicitly.

## Template

```markdown
# NNNN. Title

- Status: Proposed | Accepted | Superseded by NNNN
- Date: YYYY-MM-DD

## Context
Why a decision is needed; forces and constraints.

## Decision
What we decided.

## Consequences
What follows from it: benefits, costs, rules to follow.
```

## Index

| # | Title | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-backend-platform.md) | Backend platform | Accepted |
| [0003](0003-persistence.md) | Persistence: SQLite, jOOQ and Flyway | Accepted |
| [0004](0004-database-upgrades.md) | Database upgrades and migration rules | Accepted; backup retention in 0028 |
| [0005](0005-nntp-client-library.md) | NNTP client library | Accepted |
| [0006](0006-resilience.md) | Resilience for NNTP communication | Accepted; values in 0026 |
| [0007](0007-frontend-stack.md) | Frontend stack | Accepted; no dark mode in the MVP; client generation in 0027 |
| [0008](0008-testing-strategy.md) | Testing strategy | Accepted; test levels and build gate in 0030 |
| [0009](0009-packaging-and-deployment.md) | Packaging and deployment | Accepted; architectures superseded by 0024 |
| [0010](0010-http-transport-security.md) | HTTP transport security | Accepted |
| [0011](0011-licensing.md) | Licensing, attribution and trademarks | Accepted |
| [0012](0012-data-model.md) | Data model | Accepted; per-user data superseded by 0018 |
| [0013](0013-synchronization-and-caching.md) | Synchronization and caching | Accepted; parts superseded by 0019, 0020, 0021, 0023 |
| [0014](0014-plain-text-article-display.md) | Plain-text article display | Accepted; links and quote display in spec 007 |
| [0015](0015-account-setup-and-recovery.md) | Account setup and recovery | Accepted; recovery superseded by 0017 |
| [0016](0016-nntp-transport-security.md) | NNTP transport security | Accepted |
| [0017](0017-password-reset.md) | Password reset | Accepted |
| [0018](0018-no-user-reference-in-mvp.md) | No user reference on per-user data in the MVP | Accepted |
| [0019](0019-initial-sync-depth-instance-only.md) | Initial synchronization depth as an instance setting only | Accepted |
| [0020](0020-scheduled-group-list-refresh.md) | Scheduled group list refresh | Accepted |
| [0021](0021-jump-to-date.md) | Jump to date and multiple synchronized ranges | Accepted; read state superseded by 0022 |
| [0022](0022-older-articles-arrive-read.md) | Older articles arrive read | Accepted |
| [0023](0023-cache-size-limit.md) | Cache size limit | Accepted |
| [0024](0024-arm64-image.md) | ARM64 image | Accepted |
| [0025](0025-external-encryption-key.md) | External encryption key | Accepted |
| [0026](0026-nntp-timeouts-and-retry-values.md) | NNTP timeouts and retry values | Accepted |
| [0027](0027-committed-openapi-specification.md) | Committed OpenAPI specification | Accepted |
| [0028](0028-backup-retention.md) | Backup retention | Accepted |
| [0029](0029-logging.md) | Logging | Accepted |
| [0030](0030-test-levels-and-build-gate.md) | Test levels and build gate | Accepted |
