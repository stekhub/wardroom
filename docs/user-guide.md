# Wardroom user guide

This guide covers running Wardroom after the installation. For the installation itself, see the [quick install](../README.md#quick-install) in the README.

- [Backups and updates](#backups-and-updates)
- [Troubleshooting](#troubleshooting)
- [Advanced: keeping the encryption key outside the data directory](#advanced-keeping-the-encryption-key-outside-the-data-directory)
- [Building from source](#building-from-source)

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

Before Wardroom changes the database for a new version, it saves a copy in `config/backups`.

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

Your subscriptions, cached articles and read state are kept. For security, the stored NNTP username and password are removed; enter them again in the settings.

### Lost key file

Wardroom encrypts the stored NNTP password with a key in `config/credentials.key`. If this file is lost, Wardroom creates a new key at the next start and asks you to enter the NNTP password again. Nothing else is affected.

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

## Advanced: keeping the encryption key outside the data directory

**How the NNTP password is stored**

Wardroom needs the password of your NNTP server every time it connects, so it has to store it. It stores the password encrypted in the database. The key for it is the file `config/credentials.key`, which Wardroom creates on the first start.

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
- Back up the key file separately. If it is lost, you only need to enter the NNTP password again.
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
