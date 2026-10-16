#!/usr/bin/env bash
set -euo pipefail

EXEC_TMPDIR="${EXEC_TMPDIR:-/opt/labs/exec}"

if ! docker info >/dev/null 2>&1; then
    echo "[entrypoint] FATAL: cannot reach the Docker daemon." >&2
    echo "[entrypoint] Mount the host socket: -v /var/run/docker.sock:/var/run/docker.sock" >&2
    exit 1
fi

# Sandboxes run on the host daemon, so the images must exist there. Building
# here rather than shipping them keeps this to a single published image.
for lang in python node java; do
    if ! docker image inspect "sandbox-${lang}" >/dev/null 2>&1; then
        echo "[entrypoint] building sandbox-${lang} (first run only)"
        docker build -q -t "sandbox-${lang}" "/app/docker/sandbox-${lang}"
    fi
done

# The daemon resolves the sandbox bind-mount against the HOST filesystem, so
# this path must be identical inside and out or every submission mounts empty.
mkdir -p "${EXEC_TMPDIR}"
if ! grep -q " ${EXEC_TMPDIR} " /proc/mounts; then
    echo "[entrypoint] WARNING: ${EXEC_TMPDIR} is not a bind mount from the host." >&2
    echo "[entrypoint] Code execution will fail. Add: -v ${EXEC_TMPDIR}:${EXEC_TMPDIR}" >&2
fi

exec java ${JAVA_OPTS:-} -Djava.io.tmpdir="${EXEC_TMPDIR}" -jar /app/app.jar
