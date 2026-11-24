# 0035. Two-factor authentication with TOTP

- Status: Accepted
- Date: 2026-11-24
- Supersedes: the consequence "nothing else is encrypted with it" of [ADR 0025](0025-external-encryption-key.md)
- Related: [spec 012](../../specs/012-two-factor-authentication/spec.md), [ADR 0017](0017-password-reset.md)

## Context

The owner wants a second factor for the login. Wardroom has one account and its own JSON login. Spring Security does not create or check one-time codes.

The second factor needs a secret that Wardroom can read at every login, so it cannot be stored as a hash. Until now, the key file of ADR 0025 protected only the NNTP password, and losing it cost only entering that password again.

## Decision

**Method**

- Time-based one-time passwords after RFC 6238: HMAC-SHA1, 6 digits, periods of 30 seconds. Every common authenticator app supports these values.
- The codes are created and checked by a small class of Wardroom's own, tested with the test values of the RFC. No library is added for it.
- A code is accepted for the current period and one period before and after. The period of the last accepted code is stored, so a code works once.
- 10 one-time recovery codes are created when the second factor is turned on. They are stored as SHA-256 hashes: they are long and random, so a slow hash adds nothing.

**Storage of the secret**

- The secret is stored in the account row, encrypted with the key file of ADR 0025.
- The key loader and the cipher move from the `server` module to the `platform` module, as one `SecretCipher`. Each caller names a purpose, `nntp-password` or `totp-secret`. The purpose is part of the encryption as associated data, so a stored value of one purpose cannot be decrypted as another.
- New values are written in format version 2, with the purpose. Version 1, which has no purpose, was written only for NNTP passwords and is still read for them, so stored passwords stay valid.

**Lost key file**

- When the key file is lost and a new key is created, the stored secret cannot be read. Codes from the app are then not accepted, and the log says so at the start and at each such login.
- The recovery codes do not depend on the key, so one of them still logs in. The owner then turns the second factor off and on again.
- Without recovery codes, the way back is the password reset of ADR 0017, which removes the second factor with the account row.

## Consequences

- Losing the key file can now cost more than entering the NNTP password again: with the second factor on, it needs a recovery code or a password reset. The user guide says so.
- A backup of `/config` with the key in it contains everything needed to compute the codes. Keeping the key outside `/config` (ADR 0025) protects the secret in the same way as the NNTP password.
- A password reset removes the second factor. Who can change the database can get around it, as before for the password.
- The clocks of Wardroom and of the phone must agree to within about a minute.
