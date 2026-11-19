#!/bin/sh
# Starts Wardroom as the user and group given by PUID and PGID (ADR 0009):
# start as root, give that user the data directory, then drop privileges.
set -eu

PUID="${PUID:-1000}"
PGID="${PGID:-1000}"

case "$PUID$PGID" in
    *[!0-9]*)
        echo "PUID and PGID must be numbers, but are '$PUID' and '$PGID'." >&2
        exit 1
        ;;
esac

# Started with a fixed user, for example with "docker run --user": the IDs cannot be changed then.
if [ "$(id -u)" -ne 0 ]; then
    exec java -jar /app/wardroom.jar
fi

groupmod --non-unique --gid "$PGID" wardroom
usermod --non-unique --uid "$PUID" --gid "$PGID" wardroom >/dev/null

chown -R "$PUID:$PGID" /config

exec setpriv --reuid "$PUID" --regid "$PGID" --clear-groups java -jar /app/wardroom.jar
