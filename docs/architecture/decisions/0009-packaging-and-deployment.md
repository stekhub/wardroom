# 0009. Packaging and deployment

- Status: Accepted; architectures superseded by [0024](0024-arm64-image.md)
- Date: 2026-11-18
- Supersedes: brief §10 (two volumes `/data` and `/config`)

## Context

Wardroom is self-hosted and deployed with Docker Compose by its users. Setup should follow common self-hosting conventions: a single published image, configurable user and group IDs for file ownership on the host, and simple backups. The encryption key and database must be backed up together (brief §5).

## Decision

**Image**

- Published to `ghcr.io/stekhub/wardroom`.
- Base image `eclipse-temurin:25-jre`.
- Built for **amd64** only.
- Tags: semantic version tags and `latest`.
- A Docker `HEALTHCHECK` uses the application's health endpoint.

**Runtime**

- The application listens on port **8119**.
- `PUID` and `PGID` set the user and group the application runs as. The entrypoint starts as root, creates the user, sets ownership of `/config`, then drops privileges before starting the application.
- `TZ` sets the container time zone.

**Storage**

A single volume mounted at `/config` holds all persistent state:

| Content | Notes |
|---|---|
| SQLite database | |
| Credential encryption key | Separate file with restricted permissions; can be kept outside `/config` ([ADR 0025](0025-external-encryption-key.md)) |
| Application configuration | Optional |
| `backups/` | Pre-migration backups ([ADR 0004](0004-database-upgrades.md)) |

Exact file names are defined in the relevant specifications.

**Reference Compose file**

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

## Consequences

- Backing up the `/config` directory backs up everything, including the key and database together.
- The encryption key survives container recreation and upgrades.
- Files on the host are owned by the user the operator chooses.
- Users on ARM hardware cannot use the published image; multi-architecture builds can be added later if needed.
