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
| [011](011-login-throttling-and-session-timeout/spec.md) | Login throttling and session timeout | Accepted |
| [012](012-two-factor-authentication/spec.md) | Two-factor authentication | Accepted |
| [013](013-dark-theme/spec.md) | Dark theme | Accepted |
| [014](014-next-unread-and-mark-as-unread/spec.md) | Next unread, mark as unread and keyboard shortcuts | Accepted |
| [015](015-accessibility-and-mobile-fixes/spec.md) | Accessibility and mobile fixes | Accepted |
| [016](016-full-text-search/spec.md) | Full-text search | Accepted |
| [017](017-installable-web-app/spec.md) | Installable web app | Proposed |
| [018](018-notifications/spec.md) | Notifications | Proposed |
| [019](019-classic-view/spec.md) | Classic view | Accepted |
| [020](020-bookmarks/spec.md) | Bookmarks | Proposed |
| [021](021-newest-articles/spec.md) | Newest articles | Proposed |
| [022](022-group-list-filters-and-paging/spec.md) | Group list filters and paging | Accepted |
| [023](023-ignored-subject-words/spec.md) | Ignored subject words | Accepted |
| [024](024-groups-of-a-post/spec.md) | Choosing the groups of a post | Accepted |
| [025](025-update-hint/spec.md) | Update hint | Accepted |
