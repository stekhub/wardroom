# 0002. Backend platform

- Status: Accepted
- Date: 2026-11-18
- Supersedes: brief §6 "Backend: Kotlin + Spring Boot"

## Context

The brief proposed Kotlin with Spring Boot. The project owner prefers Java. The backend is a single deployable application (modular monolith) that must stay easy to build for contributors.

## Decision

**Language and framework**

- Java, latest LTS release: **Java 25**.
- **Spring Boot 4.x**, using the latest minor release at project setup.
- **Maven**, with the **Maven Wrapper** (`mvnw`) committed to the repository.
- **Maven Enforcer** pins the required Java and Maven versions.

**Structure**

- Modular monolith. No microservices.
- **Spring Modulith** defines application modules and verifies module boundaries in a test.

**Libraries**

| Library | Purpose |
|---|---|
| Lombok (1.18.40 or later, required for Java 25) | Boilerplate reduction, see usage rules below |
| Bean Validation (`spring-boot-starter-validation`) | Validation of API input and configuration |
| Apache James Mime4j | Parsing incoming articles: MIME structure, RFC 2047 encoded words, quoted-printable and base64, character sets |
| BouncyCastle | Required by Spring Security's `Argon2PasswordEncoder` |
| Spring Boot DevTools | Automatic restart during development (not packaged into the production image) |

AES-256-GCM credential encryption uses the JDK's built-in `javax.crypto`; no extra library.

Further libraries are added when a concrete need arises, each with a short justification.

**Usage rules**

- Configuration is bound with `@ConfigurationProperties` on Java **records**, validated with Bean Validation.
- Value objects and DTOs are Java **records**.
- Lombok is used mainly for `@RequiredArgsConstructor` (constructor injection), `@Slf4j` and `@Builder`. `@Data` is not used on domain classes.

## Consequences

- Contributors need only a JDK 25; Maven is provided by the wrapper.
- Module boundary violations fail the build instead of accumulating unnoticed.
- Article parsing builds on a maintained MIME library rather than hand-written decoding.
