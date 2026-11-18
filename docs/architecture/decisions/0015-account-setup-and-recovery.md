# 0015. Account setup and recovery

- Status: Accepted; password recovery superseded by [0017](0017-password-reset.md)
- Date: 2026-11-18

## Context

Wardroom has one local account, created during initial setup, with no public registration (brief §4). Until the account exists, anyone who can reach the instance could create it. Self-hosted applications of this kind typically let the first visitor complete setup in the browser.

## Decision

**First-run setup**

- While no account exists, Wardroom shows a setup page where the account is created.
- The setup page and its API are available only while no account exists. Afterwards they are disabled and every request requires login.
- No setup token or other extra step is required.

**Password recovery**

- The MVP has no password recovery mechanism.

## Consequences

- Setup is a single step in the browser after the first start.
- Between first start and completed setup, the instance must not be reachable by untrusted parties. The installation documentation tells users to complete setup immediately after the first start.
- A forgotten password cannot be reset through Wardroom in the MVP. Recovery can be added later if needed.
