# syntax=docker/dockerfile:1

# Build stage. It runs on the architecture of the build machine: the jar is the same for every
# architecture, so it is built once and only the runtime stage is built per architecture (ADR 0024).
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml openapi.json ./
COPY frontend/ frontend/
COPY src/ src/

# The tests run in CI, not while building the image.
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -DskipTests package \
    && cp target/wardroom-*.jar /build/wardroom.jar


# Runtime stage (ADR 0009).
FROM eclipse-temurin:25-jre

# curl is needed by the HEALTHCHECK. The base image comes with a user that has the ID 1000; it is
# replaced by Wardroom's own user, whose IDs the entrypoint changes to PUID and PGID.
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && (userdel --remove ubuntu 2>/dev/null || true) \
    && groupadd --gid 1000 wardroom \
    && useradd --uid 1000 --gid wardroom --no-create-home --home-dir /config --shell /usr/sbin/nologin wardroom \
    && mkdir /config

COPY --from=build /build/wardroom.jar /app/wardroom.jar
COPY --chmod=755 docker/entrypoint.sh /usr/local/bin/wardroom-entrypoint

# JAVA_OPTS: without a limit, Java lets its memory grow to a quarter of the memory of the machine and rarely
# gives it back. Wardroom works in small parts, so 256 MB are enough; the serial collector needs the least
# memory itself. The Compose file can set another value.
ENV PUID=1000 \
    PGID=1000 \
    TZ=Etc/UTC \
    JAVA_OPTS="-Xmx256m -XX:+UseSerialGC"

EXPOSE 8119
VOLUME /config

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl --fail --silent --show-error --output /dev/null http://localhost:8119/actuator/health || exit 1

ENTRYPOINT ["wardroom-entrypoint"]
