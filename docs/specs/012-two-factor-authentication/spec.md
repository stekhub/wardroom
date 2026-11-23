# 012. Two-factor authentication

- Status: Accepted
- Date: 2026-11-23
- Related: [spec 001](../001-user-authentication/spec.md), [spec 011](../011-login-throttling-and-session-timeout/spec.md), [ADR 0017](../../architecture/decisions/0017-password-reset.md), [ADR 0025](../../architecture/decisions/0025-external-encryption-key.md)

## Scope

A second factor for the login of the owner: a 6-digit code from an authenticator app (TOTP, RFC 6238). The owner turns it on in the settings by scanning a QR code. Recovery codes help when the phone is lost.

## Decisions

Proposed by the AI assistant and approved by the owner on 2026-11-23:

1. Spring Security does not create or check TOTP codes, and Wardroom's login is its own JSON endpoint. The second step is built into the existing login of the `auth` module, and the codes are checked by a small class of Wardroom's own after RFC 6238, tested with the test values of the RFC.
2. 10 one-time recovery codes, shown once when 2FA is turned on, stored only as hashes. Creating new ones makes the old ones invalid.
3. The secret is encrypted with the key of ADR 0025, like the NNTP password. A lost key file then also means a password reset (ADR 0017). This changes a consequence of ADR 0025 and gets a new ADR.
4. The backend sends the `otpauth://` address; the frontend draws the QR code with the highest error correction, with the Wardroom logo in the middle. The secret is also shown as text. The backend draws no image.
5. The setting is in the settings section "Account", below the password change.
6. A lost phone together with lost recovery codes is solved by the password reset of ADR 0017, which also removes 2FA. There is no other way back.
7. Recovery codes are hashed with SHA-256: they are long and random, and checking 10 Argon2id hashes would take about a second.
8. With 2FA on, a correct password does not reset the throttling counter; only a correct code does. Otherwise the codes could be tried out by entering the right password between the attempts.
9. Turning 2FA on or off does not end other sessions. A password change still ends all of them.
10. "Turn on" creates a new secret and stores it encrypted in the account row, with 2FA still off. A correct code turns it on; turning 2FA off deletes the secret. Nothing is kept in the session or in memory. A new secret at every "Turn on", because a QR code that was shown before may have been seen or saved, and accounts that exist today have no secret.
11. The key loader and the cipher of ADR 0025 move from the `server` module to the `platform` module, as one `SecretCipher` used by both modules. Each caller names a purpose, such as `nntp-password` or `totp-secret`, which is part of the encryption as associated data, so a ciphertext of one purpose cannot be decrypted as another. Stored NNTP passwords stay readable: the existing format version 1 has no purpose and is still read; new values are written in version 2 with the purpose.

## User stories

- As the owner, I turn on two-factor authentication in the settings by scanning a QR code with my authenticator app.
- As the owner, I enter a code from the app after my password at every login.
- As the owner, I get back in with a recovery code when my phone is lost.
- As the owner, I turn two-factor authentication off again.

## Functional requirements

**Turning it on**

- In the settings section "Account": "Two-factor authentication", off by default, with the button "Turn on".
- Turning on asks for the current password. Then the QR code is shown, with the Wardroom logo in the middle and the secret as text below it.
- The owner enters a code from the app. Only a correct code turns 2FA on; until then nothing changes.
- After that, the 10 recovery codes are shown once, with "Copy" and "Download". The dialog says that they are not shown again.

**Login**

- With 2FA on, a correct password leads to a second step that asks for the code. "Remember me" is chosen before and applies after the code.
- The second step accepts a 6-digit code or a recovery code. A recovery code works once.
- The second step must be completed within 5 minutes; then the login starts again.
- A wrong code counts in the login throttling (spec 011). The message is generic.
- A code is accepted for the current 30-second period and one period before and after. A code that was used once is not accepted again.

**Managing it**

- With 2FA on, the section shows "On", with "Create new recovery codes" and "Turn off". Both ask for the current password and a code.
- Changing the password does not change 2FA.

## API contracts

| Method and path | Access | Request | Responses |
|---|---|---|---|
| `POST /api/auth/login` | Public | as in spec 001 | `204` logged in; `200 { "secondFactorRequired": true }` password correct, code needed; `401`; `429` |
| `POST /api/auth/login/second-factor` | After a correct password | `{ "code" }` | `204` logged in; `401` wrong code or step expired; `429` |
| `GET /api/auth/two-factor` | Logged in | | `200 { "enabled", "recoveryCodesLeft" }` |
| `POST /api/auth/two-factor/setup` | Logged in | `{ "currentPassword" }` | `200 { "otpauthUri", "secret" }`; `400` wrong password |
| `POST /api/auth/two-factor/enable` | Logged in | `{ "code" }` | `200 { "recoveryCodes" }`; `400` wrong code or no setup started |
| `POST /api/auth/two-factor/recovery-codes` | Logged in | `{ "currentPassword", "code" }` | `200 { "recoveryCodes" }`; `400` |
| `DELETE /api/auth/two-factor` | Logged in | `{ "currentPassword", "code" }` | `204`; `400` |

- Responses that contain the secret or recovery codes are sent with `Cache-Control: no-store`.

## Technical design

- After a correct password with 2FA on, the session holds only "password checked, code pending" and the time. It gives no access to anything else. The session ID is changed again when the code is accepted.
- The secret has 20 random bytes, in Base32 in the address: `otpauth://totp/Wardroom:{username}?secret=...&issuer=Wardroom&algorithm=SHA1&digits=6&period=30`. SHA1, 6 digits and 30 seconds, because every common authenticator app supports them.
- "Turn on" stores a new secret in the account row, with 2FA still off; a correct code turns it on, and turning it off deletes the secret.
- Recovery codes have 10 characters from a set without characters that are easy to confuse; they are hashed with SHA-256.
- The time period of the last accepted code is stored, so a code cannot be used twice.

## Data model changes

- `wardroom_user`: new columns `totp_secret` (nullable; encrypted with the key of ADR 0025), `totp_enabled` (`BOOLEAN`, default false) and `totp_last_used_period` (`BIGINT`, nullable).
- New table `recovery_code` with `id` and `code_hash`. It belongs to the single account and is emptied with it.

## Error handling

| Situation | Behavior |
|---|---|
| Wrong code or recovery code at login | `401`, generic message; counts in the throttling |
| Second step after more than 5 minutes | `401`; the login starts again with the password |
| Wrong code when turning on | `400`; the QR code stays, the owner tries again |
| Secret cannot be decrypted (key file lost) | Login with 2FA is not possible; the log says so and points to the password reset |

## Security considerations

- The secret, the codes and the recovery codes are never logged or sent in errors. The secret leaves the backend only during setup.
- The login reveals that the password was right only by asking for the code.
- Throttling covers both steps, so the 6 digits cannot be tried out.
- A password reset (ADR 0017) removes the secret and the recovery codes with the account row.

## Testing requirements

- The TOTP class against the test values of RFC 6238 (SHA1).
- Turning on needs the password and a correct code; nothing is stored before.
- Login with 2FA: the second step is required, a session in the second step has no access, and the step ends after 5 minutes.
- A code is accepted in the window of one period before and after, and not twice; a recovery code works once.
- Wrong codes are throttled like wrong passwords.
- Turning off and creating new recovery codes need password and code.
- The password reset removes 2FA.
- Frontend: the QR code shows the logo and can be read (decoded in the test); the recovery codes are shown once.

## Implementation tasks

1. ADR for the TOTP method and the storage of the secret.
2. Move the key loader and the cipher to the `platform` module as `SecretCipher`, with the purpose and format version 2.
3. Migration for the columns and the table.
4. TOTP class and recovery codes.
5. Two-step login; setup, enable, disable and recovery code endpoints.
6. Frontend: second login step, the section in "Account" with the QR code, the recovery code dialog.
7. User guide: turning on 2FA, recovery codes, lost phone.
