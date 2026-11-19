# Contributing to Wardroom

Thank you for your interest in Wardroom. This document explains how to contribute.

Wardroom is in early development. The scope of the first version is fixed by the specifications, so please read "Before you start" first.

## Before you start

- **Bugs and small fixes:** open an issue or send a pull request directly.
- **Anything larger:** open an issue first and describe what you want to change. This avoids work on something that does not fit the planned scope.
- **Security problems:** do not open a public issue. See [Reporting a security problem](#reporting-a-security-problem).

## Specification first

Wardroom uses spec-driven development: behaviour is described before it is implemented.

- A change in behaviour starts with a change to the [specifications](docs/specs/README.md) or a new [architecture decision record](docs/architecture/decisions/README.md) (ADR).
- Accepted ADRs are not rewritten. A changed decision gets a new ADR that supersedes the old one.
- The order of the work is in the [implementation plan](docs/implementation-plan.md).

## Building and testing

You need JDK 25. Maven, Node.js and pnpm are downloaded by the build. On Windows use `mvnw.cmd`.

```bash
./mvnw verify
```

This builds the backend and the frontend and runs all tests. It must pass before you open a pull request.

- New behaviour needs tests. Each specification lists its testing requirements.
- Tests never use a real NNTP server. Use the mock NNTP server in the test sources.
- Passwords, session IDs and encryption keys must never appear in log output or error messages.

More commands are listed in the [user guide](docs/user-guide.md#building-from-source).

## Branches and pull requests

- Work on a branch, never directly on `main`.
- Branch names have a type, a slash and a short lowercase name with hyphens: `feature/…`, `fix/…`, `docs/…` or `chore/…`.
- One topic per pull request. Small pull requests are reviewed faster.
- Describe what you changed, how you verified it, and what you did not verify.

## Code rules

**Backend**

- Java 25 and Spring Boot. Each area is its own module; do not use the internals of another module.
- Dependencies are passed in through the constructor. No field injection.
- Records for value objects and data passed between layers.
- Database changes are plain SQL migrations. Database access uses the classes generated from the schema.
- API errors use Problem Details (RFC 9457).

**Frontend**

- React with TypeScript in strict mode.
- API types are generated from `openapi.json`. Do not write them by hand.
- Run `pnpm format` in `frontend/` before you commit.

**Both**

- Write code that is easy to read. Prefer the explicit solution to the clever one.
- Names say what a thing is, in full words: `serverSettings` and `wireClient`, not `server` and `wire`.
- Comments explain why, not what.

## Using AI tools

Wardroom itself is built with the help of an AI coding assistant, under clear rules. Contributions made with AI tools are welcome under the same rules. Please read the [AI guidelines](docs/ai-guidelines.md).

## Reporting a security problem

Please report security problems privately, through GitHub's private vulnerability reporting: open the "Security" tab of the repository and choose "Report a vulnerability".

## License

Wardroom is licensed under the [GNU AGPL-3.0-or-later](LICENSE), with the [additional terms](LICENSE-ADDITIONAL-TERMS.md).

- Your contribution is accepted under the same license.
- There is no contributor license agreement and no sign-off.
- The name "Wardroom" and the logo are covered by the [trademark policy](TRADEMARKS.md).
