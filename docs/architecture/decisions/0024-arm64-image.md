# 0024. ARM64 image

- Status: Accepted
- Date: 2026-11-18
- Supersedes: "Built for amd64 only" in [ADR 0009](0009-packaging-and-deployment.md)

## Context

ADR 0009 publishes the image for amd64 only. Many self-hosters run their services on ARM hardware: Raspberry Pis, ARM-based NAS devices and ARM cloud servers. Wardroom runs on the JVM, so the application itself is the same on every architecture; only the base image differs.

## Decision

- The image is published as a multi-architecture image for **amd64** and **arm64**, under the same tags.
- The application JAR is built once; only the runtime stage is built per architecture.
- 32-bit ARM (armv7) is not supported.

## Consequences

- Raspberry Pi 3, 4 and 5 are supported with a 64-bit operating system; `docker compose pull` selects the right architecture automatically.
- Both architectures are built on every release, which makes the release build somewhat longer.
- The image must start and pass its health check on arm64 before a release is published.
