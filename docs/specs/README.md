# Feature Specifications

One directory per feature, named `NNN-short-title/`, with the specification in `spec.md`. Each specification covers user stories, functional requirements, API contracts, technical design, data model changes, error handling, security considerations, testing requirements and implementation tasks (brief §12).

The layout and interaction rules shared by all specifications are in the [UI concept](../product/ui-concept.md).

Architecture decisions that a specification relies on are recorded as ADRs in [`architecture/decisions`](../architecture/decisions/README.md).

The order in which the specifications are implemented is in the [implementation plan](../implementation-plan.md).

## Index

| # | Title | Status |
|---|---|---|
| [001](001-user-authentication/spec.md) | User authentication | Accepted |
| [002](002-nntp-server-configuration/spec.md) | NNTP server configuration, including credential storage | Accepted |
| [003](003-newsgroup-discovery/spec.md) | Newsgroup discovery | Accepted |
| [004](004-subscriptions/spec.md) | Subscriptions | Accepted |
| [005](005-article-synchronization/spec.md) | Article synchronization | Accepted |
| [006](006-threading/spec.md) | Threading | Accepted |
| [007](007-article-reading/spec.md) | Article reading | Accepted |
| [008](008-discussion-view/spec.md) | Discussion view | Accepted |
| [009](009-article-posting/spec.md) | Article posting | Accepted |
| [010](010-ignoring/spec.md) | Ignoring authors and threads | Accepted |
