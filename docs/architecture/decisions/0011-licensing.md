# 0011. Licensing, attribution and trademarks

- Status: Accepted
- Date: 2026-11-18

## Context

Wardroom is free and open source (brief §2). A license, attribution rules and a policy for the project name are needed.

## Decision

**License: GNU AGPL-3.0-or-later**

- Any distributed or network-hosted modified version must publish its source code under the same license. Improvements in derived versions therefore remain available to be merged back into Wardroom.
- `LICENSE` contains the unmodified AGPL-3.0 text, so the license is recognized correctly by tooling and visitors.

**Additional terms under AGPL Section 7** (in [`LICENSE-ADDITIONAL-TERMS.md`](../../../LICENSE-ADDITIONAL-TERMS.md))

- Modified versions must preserve a visible attribution, "Based on Wardroom", with a link to the original repository, in the application's About view.
- Modified versions must be marked as different from the original.
- The license grants no rights to the Wardroom name or logo.

The terms are kept minimal so they do not discourage contributors.

**Trademark policy** (in [`TRADEMARKS.md`](../../../TRADEMARKS.md))

The name "Wardroom" and its logo identify the official project. Derived versions must use a different name.

**Contributions**

- No Developer Certificate of Origin (DCO) and no Contributor License Agreement (CLA).
- Contributions are accepted under the project license (inbound = outbound, as provided by the GitHub Terms of Service).
- The project will not be relicensed. AGPL-3.0-or-later allows adopting later AGPL versions without contributor consent.

## Consequences

- A rebranded version must still credit Wardroom and cannot use its name.
- A competing version cannot keep its improvements closed.
- Some companies do not allow employees to contribute to AGPL projects; this is accepted.
- Without a CLA, relicensing would require consent from every contributor; this is acceptable because relicensing is not intended.
- This decision is not legal advice; registering the trademark or any commercial involvement warrants legal review.
