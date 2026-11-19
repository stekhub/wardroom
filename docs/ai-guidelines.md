# AI guidelines

Wardroom is built with the help of an AI coding assistant. This document says openly how the assistant is used, and which rules keep humans in control of the project.

The rules apply to the maintainer and to everyone who contributes.

## How Wardroom is built

### Specification first

Wardroom uses spec-driven development.

- Every feature is described in a specification before any code is written. Decisions about architecture and technology are recorded as architecture decision records (ADRs).
- The specifications are in [`docs/specs`](specs/README.md), the ADRs in [`docs/architecture/decisions`](architecture/decisions/README.md).
- The code implements what the specifications say. It adds no features, settings or endpoints they do not describe.
- If a specification turns out to be unclear or wrong, the specification is corrected first, then the code.

### Small steps a human can follow

- The work is split into small steps, listed in the [implementation plan](implementation-plan.md). Each step is one branch and one pull request.
- A step is meant to be small enough that a human can follow it in one review.
- Each step ends with a summary: what was built, how to try it, what deviates from the specification and what is still open.
- The next step starts only after the previous one was reviewed.

## Rules for the AI

### Humans decide

- All architecture and design decisions are made by a human, or worked out together with the AI and then reviewed and approved by a human.
- The AI makes no unmonitored decisions. When a specification is ambiguous or contradicts another document, it stops, describes the problem and proposes a solution.
- Smaller decisions the AI makes while implementing a step are listed for the maintainer, who can reject each of them. They are recorded in the notes of the implementation plan.

### Humans review and test

- A human reviews every change before it is merged. The review aims to understand what the change does and why. It does not claim to catch every mistake, which is why automated tests and small steps are part of the process.
- A human tries out each step by hand where there is something to try, in addition to the automated tests.

### Humans control the repository

- The AI commits and pushes only when the maintainer explicitly tells it to.
- The AI never merges. Merging into the main branch is done by a human.
- Commits written with the AI carry a co-author line, so the history shows which ones they are.

### Automated checks

- The full build with all tests must pass before a step is handed over for review.
- Automated tests never use a real NNTP server.
- Passwords, session IDs and encryption keys are never written to the log. Tests check this.

## Code that humans can maintain

AI-generated code must be as easy to read and change as code written by hand. A human must be able to maintain Wardroom without any AI.

- **Clear structure:** the backend is a modular monolith. Each area is its own module, and a test fails the build when one module uses the internals of another.
- **Common, well-documented technology:** Spring Boot, React and SQLite, used in their standard way.
- **Explicit over clever:** plain SQL migrations, typed database access generated from the schema, dependencies passed in through constructors.
- **Comments that explain why:** code comments describe the reason for a choice, not what the line does.
- **Tests as documentation:** every specification lists its testing requirements, and they are implemented together with the feature.

## Responsibility

The human who merges a change is responsible for it, whoever or whatever wrote it.

## If you contribute with AI

You may use AI tools for contributions. The same rules apply:

- You understand what you submit and can explain it.
- You have built and tested the change yourself.
- You say in the pull request that AI was used.
- You do not submit AI output that you have not reviewed.

See the [contribution guidelines](../CONTRIBUTING.md) for everything else.
