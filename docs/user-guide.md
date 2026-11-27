# Wardroom user guide

This guide covers running Wardroom after the installation. For the installation itself, see the [quick install](../README.md#quick-install) in the README.

- [Light and dark theme](#light-and-dark-theme)
- [Reading with the keyboard](#reading-with-the-keyboard)
- [Login and security](#login-and-security)
- [Backups and updates](#backups-and-updates)
- [Troubleshooting](#troubleshooting)
- [Finding groups](#finding-groups), [Ignoring](#ignoring), [On a phone and with a screen reader](#on-a-phone-and-with-a-screen-reader)
- [Advanced: keeping the encryption key outside the data directory](#advanced-keeping-the-encryption-key-outside-the-data-directory)
- [Building from source](#building-from-source)

## Light and dark theme

Wardroom has a light and a dark theme. Choose one in the settings, section "Preferences":

- **Light** or **Dark** keeps that theme.
- **System**, the default, follows the light or dark setting of your device and changes with it.

The choice is stored in the browser, so your phone can use another theme than your computer. A browser that does not keep site data uses System after every reload.

## Reading with the keyboard

In a group and in a discussion, single keys help you read without the mouse:

| Key | What it does |
|---|---|
| `N` | Opens the next unread article. At the end of a group, it opens the next group with unread articles. |
| `Space` | Scrolls down. At the end of the page, it does what `N` does. |
| `J` and `K` | Go to the next and the previous article. In a group, they move between the discussions; Enter opens one. |
| `R` | Replies to the current article. |
| `C` | Marks the discussion as read and goes on as `N`. |
| `?` | Shows the list of keys. |

An article that a key reaches is marked as read. The keys do nothing while you type in a field.

To read an article again later, choose "Mark as unread" in its menu. It stays unread until you open the discussion again or a key reaches it.

The keys can be turned off in the settings, section "Preferences". The setting is stored in the browser, like the theme.

## Finding groups

"Discover groups" lists the groups of your server, 50 per page. Below the list you go to the first, the previous, the next or the last page, or you type a page number and press Enter.

- **Binary groups are hidden.** Groups whose names show that they carry files, such as `alt.binaries.*`, do not appear in the list. To see them, turn "Hide binary groups" off in the settings, section "Ignored", and save.
- **Ignore a group** with the button with the crossed-out eye in its row. The group no longer appears in the list. A group you are subscribed to cannot be ignored.
- **A few groups are ignored from the start:** `misc.phone.mobile.android`, `alt.dvdnordic.org`, `free.usenet`, `free.pt`, `a2i.b`, `zzz.3`, `de.comp.os.ms-windows` and `de.alt.sources.amiga`. To see one of them, remove it in the settings, section "Ignored", list "Groups".
- A group you are subscribed to always stays in the sidebar.

## Ignoring

Ignoring hides what you do not want to see. It deletes nothing and marks nothing as read: when you stop ignoring, everything is shown as before. The settings section "Ignored" lists everything you ignore, each with a "Remove" button.

| What | How | Effect |
|---|---|---|
| An author | "Ignore author" in the menu of an article | Articles of the author are one line; discussions the author started are hidden |
| A discussion | "Ignore thread" on its card or on its page | The discussion is hidden, also when new replies arrive |
| A group | The button in its row of the group list | The group is hidden in the group list |
| A word in subjects | Typed in the settings section "Ignored", for all groups or for one | Discussions with the word in their subject are hidden |

About words in subjects:

- Upper and lower case do not matter, and only whole words match: "java" hides "Java 25 released", not "JavaScript tips".
- A phrase such as "for sale" matches these words in this order.
- Punctuation counts as a space: "C++" is the same as "C".
- The subject of the first article of a discussion decides. A reply with the word does not hide a discussion.
- A hidden discussion that you open through a link is shown, with a note that names the word.

## On a phone and with a screen reader

- On a narrow screen the sidebar is a menu. Open it with the button at the top left; it closes when you choose a group or a page.
- Some buttons show only their icon on a narrow screen. Hold a finger on one, or point at it, to see what it does.
- The first Tab on every page reaches "Skip to content", which jumps past the sidebar.
- A screen reader announces the heading of a page when you open it, the level of each reply in a discussion, and the start and the end of a synchronization.
- Some screen readers use single keys themselves. If Wardroom's keys get in the way, turn them off in the settings, section "Preferences".

## Login and security

### How long you stay logged in

- Without "Remember me", you are logged out 30 minutes after the browser stopped talking to Wardroom. In practice this means: 30 minutes after you closed the browser or the tab, or after the computer went to sleep.
- An open page keeps you logged in, also in a tab in the background.
- With "Remember me", the browser stays logged in for 7 days after its last visit.

### Two-factor authentication

With two-factor authentication, a login needs your password and a 6-digit code from an authenticator app on your phone. Any app that supports time-based codes (TOTP) works.

**Turning it on**

1. Open the settings, section "Account", and choose "Turn on" under "Two-factor authentication".
2. Enter your password.
3. Scan the QR code with your authenticator app. If you cannot scan it, type the secret below the code into the app.
4. Enter the code the app shows. Until then, nothing changes.
5. Wardroom shows 10 recovery codes. Copy or download them and keep them in a safe place. They are shown only once.

The clocks of your phone and of the computer Wardroom runs on must be right; the codes depend on the time.

**Logging in**

After your password, Wardroom asks for the code. You have 5 minutes for it. Instead of a code from the app, you can enter a recovery code.

**Recovery codes**

- Each recovery code works once. The settings show how many are left.
- "Create new recovery codes" gives you 10 new ones. The old ones stop working.

**Turning it off**

"Turn off" in the same place asks for your password and a code. A recovery code works here too.

**Lost phone**

- Log in with a recovery code. Then turn two-factor authentication off, and on again with your new phone.
- Without recovery codes, reset the password as described under [Forgotten password](#forgotten-password). This also turns two-factor authentication off.

### Logins in the log

Wardroom writes every login to its log, so you can see attacks and from where your account was used:

```text
WARN  ... Failed login from 203.0.113.7
WARN  ... Too many failed logins in a row; the next login has to wait 1 second
INFO  ... Login from 192.168.1.20
```

- The address is the one the connection came from. Behind a reverse proxy, the line may also show `(X-Forwarded-For: ...)` with the address the proxy reported. Anyone who reaches Wardroom directly can make this value up.
- After 5 failed logins in a row with your username, every further login with it has to wait, up to 60 seconds. This also holds after a restart. Attempts during the waiting time are not logged one by one.
- Logins with another username never make you wait. So choose a username that is hard to guess: somebody who knows it can hold up your login with wrong passwords. Sessions that are open keep working.
- A wrong code of two-factor authentication in the settings counts like a failed login. The log then says `Wrong code for two-factor authentication in the settings`.
- The entered username and password are never logged.
- With these lines, a tool of your choice can block addresses that keep failing.

## Backups and updates

**Backup**

Everything Wardroom stores is in the `config` directory.

1. Stop the container: `docker compose stop`
2. Copy the `config` directory.
3. Start the container again: `docker compose start`

To restore, stop the container, put the copy back and start the container.

**Update**

```bash
docker compose pull
```

```bash
docker compose up -d
```

The image has three kinds of tags:

| Tag | Meaning |
|---|---|
| `latest` | The newest release. The reference Compose file uses it. |
| `0.9` | The newest release of 0.9, for example 0.9.2. |
| `0.9.0` | Exactly this release. It never changes. |

The [releases](https://github.com/stekhub/wardroom/releases) list what changed in each version.

Before Wardroom changes the database for a new version, it saves a copy in `config/backups`. It keeps the two newest copies.

**Going back to an earlier version**

The name of a copy says which version of Wardroom left the data. For example, `wardroom-0.9.0-schema13-20270115T093000123Z.db` is the data as Wardroom 0.9.0 left it. Use the copy whose version you go back to.

1. Stop the container: `docker compose stop`
2. In the Compose file, set the image to that version, for example `ghcr.io/stekhub/wardroom:0.9.0`.
3. Copy the backup over the database, and delete the files `wardroom.db-wal` and `wardroom.db-shm` next to it if they exist:

   ```bash
   cp config/backups/wardroom-0.9.0-schema13-20270115T093000123Z.db config/wardroom.db
   ```

4. Start the container: `docker compose up -d`

Everything that changed after the update is lost, for example articles read since then. An earlier version does not start with the database of a newer one; it names the backup directory in the log.

## Troubleshooting

### Forgotten password

The password cannot be reset in the browser. Reset it by deleting the account in the database:

1. Stop the container: `docker compose stop`
2. Delete the account with any SQLite tool, for example:

   ```bash
   sqlite3 config/wardroom.db "DELETE FROM wardroom_user;"
   ```

3. Start the container: `docker compose start`
4. Open Wardroom and create a new account.

Your subscriptions, cached articles and read state are kept. For security, the stored NNTP username and password are removed; enter them again in the settings. Two-factor authentication is turned off with the reset.

### Lost key file

Wardroom encrypts the stored NNTP password and the secret of two-factor authentication with a key in `config/credentials.key`. If this file is lost, Wardroom creates a new key at the next start:

- It asks you to enter the NNTP password again.
- With two-factor authentication on, codes from your authenticator app are no longer accepted, and the log says so. Log in with a recovery code, turn two-factor authentication off and turn it on again. Without recovery codes, reset the password as described under [Forgotten password](#forgotten-password).

Nothing else is affected.

If the file exists but is damaged, Wardroom does not start and names the file in the log. Restore the file from a backup, or delete it to get a new key.

### Posting is not allowed

Your posts are refused with "Posting is not allowed", although the connection test says "Posting: available".

- Some providers take posts only on a separate server. Your provider's help pages name its host.
- Open the settings, section "Server", and tick "Use a separate server for posting". Enter the host, the port and the TLS mode of that server.
- Leave "Use the same login as for reading" ticked if the posting server takes the login you read with. Otherwise untick it and enter the login of the posting server.
- "Test connection" then tests both servers. The posting line is about the posting server.

Some providers also need posting to be activated for your account first.

### More detail in the log

Wardroom writes its log to the container output:

```bash
docker compose logs -f wardroom
```

For more detail, add `WARDROOM_LOG_LEVEL=DEBUG` to `environment` in the Compose file and start the container again.

### Memory

Wardroom limits the memory Java may use for its data to 256 MB. With what Java and the database need besides, the container uses about 400 to 500 MB.

- **If the log shows `OutOfMemoryError`,** raise the limit. This can happen on a server with very many groups or with very large articles. Add this to `environment` in the Compose file and start the container again:

  ```yaml
  - JAVA_OPTS=-Xmx384m -XX:+UseSerialGC
  ```

- **On a machine with much memory** you do not need to change anything. A higher limit does not make Wardroom faster.
- The value replaces the default, so keep `-XX:+UseSerialGC` unless you know that you want another setting.

## Advanced: keeping the encryption key outside the data directory

**How the NNTP password is stored**

Wardroom needs the password of your NNTP server every time it connects, so it has to store it. It stores the password encrypted in the database. The key for it is the file `config/credentials.key`, which Wardroom creates on the first start. The secret of two-factor authentication is stored in the same way, with the same key.

**The weak point of the default**

By default, the key is in `config`, next to the database. A copy of `config`, such as a backup, then contains everything needed to read the password.

**What you can do**

Keep the key in another place: a file outside the data directory that you pass to the container as a Docker secret. Backups of `config` then no longer contain the key.

- This protects copies of your data, for example a backup that gets into the wrong hands.
- It does not protect a running host that someone else controls, because Wardroom needs the key while it runs.

This step is optional. It is worth doing when backups of `config` leave the host, for example to a cloud storage.

**Steps**

1. Create a key file with 32 random bytes, in a directory that is not part of your `config` backups:

   ```bash
   head -c 32 /dev/urandom > wardroom.key
   ```

2. Pass it to the container as a secret and name it in `WARDROOM_ENCRYPTION_KEY_FILE`. Docker makes a secret available inside the container as a file below `/run/secrets`:

   ```yaml
   services:
     wardroom:
       image: ghcr.io/stekhub/wardroom:latest
       restart: unless-stopped
       ports:
         - "8119:8119"
       environment:
         - PUID=1000
         - PGID=1000
         - TZ=Etc/UTC
         - WARDROOM_ENCRYPTION_KEY_FILE=/run/secrets/wardroom_key
       volumes:
         - ./config:/config
       secrets:
         - wardroom_key

   secrets:
     wardroom_key:
       file: /path/outside/config/wardroom.key
   ```

3. Start Wardroom again: `docker compose up -d`

Notes:

- The key file must be readable by the user set with `PUID`.
- If the file is missing or does not contain exactly 32 bytes, Wardroom does not start and names the file in the log. It never creates the key in this location itself.
- Back up the key file separately. If it is lost, see [Lost key file](#lost-key-file).
- **Switching an existing installation:** instead of step 1, move `config/credentials.key` to the new location. The stored password stays valid. If you create a new key instead, Wardroom asks for the NNTP password again.
- If `config/credentials.key` still exists after switching, Wardroom ignores it and says so in the log. You can delete it once the setup works.

## Building from source

You need JDK 25 and, for the image, Docker. Maven, Node.js and pnpm are downloaded by the build.

Build and run all tests (on Windows use `mvnw.cmd`):

```bash
./mvnw verify
```

Build the image locally:

```bash
docker build -t ghcr.io/stekhub/wardroom:latest .
```

Then start it with the Compose file from the [quick install](../README.md#quick-install).

If you want to change Wardroom, read the [contribution guidelines](../CONTRIBUTING.md).
