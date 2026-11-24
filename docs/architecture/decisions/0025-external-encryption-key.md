# 0025. External encryption key

- Status: Accepted; documentation location superseded by [0031](0031-user-guide.md), the cost of a lost key by [0035](0035-two-factor-authentication.md)
- Date: 2026-11-18
- Extends: credential encryption in [spec 002](../../specs/002-nntp-server-configuration/spec.md) and the storage of [ADR 0009](0009-packaging-and-deployment.md)

## Context

The NNTP password is encrypted with a key stored in `/config/credentials.key`, next to the database. Anyone who obtains a copy of `/config` (a backup, a disk, a memory card) can decrypt it. Some owners want backups of `/config` that do not contain the key.

Any design that lets Wardroom synchronize unattended needs the key while it runs. Keeping the key outside `/config` protects copies of the data, not a compromised running host.

## Decision

- An optional environment variable `WARDROOM_ENCRYPTION_KEY_FILE` names a file that holds the key, for example a Docker secret at `/run/secrets/wardroom_key`. The file contains 32 raw bytes.
- When the variable is set:
  - Wardroom reads the key from that file and never creates or writes a key in `/config`.
  - A missing, unreadable or wrongly sized file makes startup fail with an error naming the file. Wardroom does not generate a key there.
  - If `/config/credentials.key` also exists, it is ignored and a warning says it is no longer used and can be deleted once the setup works.
- When the variable is not set, nothing changes: the key is generated in `/config` automatically. The owner is never asked for a key (brief §5).
- Switching an existing installation: move `/config/credentials.key` to the new location and set the variable; the stored password stays valid. With a new key instead, the stored password cannot be decrypted, `credentials_invalid` is set and the owner enters the password again.

**Documentation**

- The option is described only in an advanced section of the project README. The quick start and the reference Compose file do not mention it.

## Consequences

- Backups of `/config` no longer contain the key; the owner backs up the key separately.
- Losing the key costs only re-entering the NNTP password, because nothing else is encrypted with it.
- The key file must be readable by the user set with `PUID`; Wardroom does not check its permissions.
