<div align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/logo/wardroom-header-dark.svg">
    <img src="assets/logo/wardroom-header-light.svg" alt="Wardroom" width="416">
  </picture>
</div>

**Newsgroups deserve a modern home.**

Newsgroups are among the oldest places for discussion on the internet, and they are still alive: open, decentralized and owned by no single company. But the software for reading them is fading. Many newsreaders are no longer maintained, and few feel at home on today's web.

Wardroom is built to keep newsgroups alive. It is a free, open-source, self-hosted newsreader with a modern reading experience: threads shown as conversations, a clean interface in your browser, and decades of discussions within reach. It runs as a single Docker container.

Wardroom works with any server that speaks NNTP, the newsgroup protocol. That can be a Usenet provider, or a standalone news server that is not part of Usenet, such as one run by a project, a company or yourself.

Wardroom is made for text discussions. It does not download binaries or attachments.

- **Threaded discussions:** articles are grouped into threads and shown as a conversation.
- **Jump to date:** pick a date and read a group as it was back then. Wardroom finds the right place on the server in seconds, without downloading everything in between, so discussions from many years ago are as easy to reach as today's.
- **Subscriptions:** search the groups your server offers, subscribe to the ones you like and see unread counts.
- **Posting:** start new threads and reply to articles.
- **Local cache:** synchronized articles are stored locally, so reading is fast.
- **Ignoring:** hide authors and threads you do not want to see.
- **Simple to run:** one container, one data directory, one owner account, one NNTP server.

<!-- TODO: add a screenshot of the discussion view here once the UI exists. -->

## Quick install

You need Docker with Docker Compose and access to an NNTP server.

1. Create a directory for Wardroom and save this file in it as `compose.yaml`:

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
       volumes:
         - ./config:/config
   ```

2. Start Wardroom:

   ```bash
   docker compose up -d
   ```

3. Open `http://localhost:8119` in your browser.

On the first start, a wizard asks for two things: a username and password for your Wardroom account, and the connection details of your NNTP server. After that, Wardroom shows the groups your server offers.

### Settings in the Compose file

| Setting | Meaning |
|---|---|
| `PUID`, `PGID` | User and group ID that Wardroom runs as. Files in `config` belong to this user. |
| `TZ` | Time zone of the container, for example `Europe/Vienna`. |
| `ports` | Wardroom listens on port 8119. To use another port on the host, change the left number, for example `"9000:8119"`. |
| `./config:/config` | The data directory. It holds the database, the encryption key and automatic backups. |

## Documentation

The [user guide](docs/user-guide.md) covers everything beyond the quick install:

- [Backups and updates](docs/user-guide.md#backups-and-updates)
- [Troubleshooting](docs/user-guide.md#troubleshooting): forgotten password, lost key file, more detail in the log
- [Advanced: keeping the encryption key outside the data directory](docs/user-guide.md#advanced-keeping-the-encryption-key-outside-the-data-directory)
- [Building from source](docs/user-guide.md#building-from-source)
