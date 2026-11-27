# 0037. Key protection with DPAPI in the desktop mode

- Status: Accepted
- Date: 2026-11-26
- Extends: [ADR 0025](0025-external-encryption-key.md), [ADR 0035](0035-two-factor-authentication.md)
- Related: [ADR 0036](0036-windows-app.md), [spec 026](../../specs/026-windows-app/spec.md)

## Context

The NNTP password and the secret of the second factor are encrypted with a key file (ADR 0025, ADR 0035). In the image, the key is in `/config` by default, or outside it with `WARDROOM_ENCRYPTION_KEY_FILE`.

In the desktop mode (ADR 0036), the data folder is in the owner's own application data folder. Key and database lie next to each other, and a copy of the folder, for example in a backup, contains both. Windows has a protection for this: the Data Protection API (DPAPI) encrypts data so that only the same Windows user on the same computer can decrypt it, without a password prompt.

## Decision

- How the key is stored becomes one interface with two kinds:
  - **Key file**, as today, on all systems. The image keeps it, also when it runs on Windows.
  - **DPAPI**, only in the desktop mode. The key is stored encrypted with DPAPI for the current user, in `credentials.dpapi` in the data folder.
- `SecretCipher` and the format of the stored values stay as they are. Only the way the key is read and written changes, so the NNTP password and the secret of the second factor are protected alike.
- DPAPI is called through JNA (`jna-platform`), with a fixed purpose text as additional entropy.
- When the desktop mode finds an unprotected `credentials.key`, for example in a data folder copied in by hand, it protects the key with DPAPI, checks that it can read it back, and deletes the unprotected file. The log says so.
- `WARDROOM_ENCRYPTION_KEY_FILE` is not used in the desktop mode. When it is set, Wardroom does not start and the log says why, so the owner does not believe the key is elsewhere.

## Consequences

- A copy of the data folder, also in a backup, does not reveal the NNTP password or the secret of the second factor.
- Moving the data folder to another computer, or to another Windows user, loses the key. This is the case "Lost key file" of the user guide: the NNTP password is entered again, and with the second factor, a recovery code is needed (ADR 0035).
- Resetting the Windows password through an administrator can also make the key unreadable. The user guide says so.
- A program that runs as the same Windows user can still decrypt the key, as it could read the key file before. DPAPI protects copies, not a compromised account.
