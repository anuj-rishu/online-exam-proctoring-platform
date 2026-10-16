#!/usr/bin/env bash


set -euo pipefail
cd "$(dirname "$0")"

IMAGE="${IMAGE:-ghcr.io/srm-insider/srm-insider-lab}"
MODE="${1:-local}"

case "$MODE" in
  local)
    echo "==> local build  (native $(uname -m))  ${IMAGE}:dev"
    docker build -t "${IMAGE}:dev" .
    echo
    echo "Built ${IMAGE}:dev"
    echo "Run it with:  APP_VERSION=dev docker compose up -d"
    ;;

  prod)
    VERSION="${2:-}"
    if [ -z "$VERSION" ]; then
      echo "usage: ./build.sh prod <version>     e.g. ./build.sh prod 4.0.2" >&2
      exit 1
    fi

    if ! docker buildx inspect labs-builder >/dev/null 2>&1; then
      echo "==> creating buildx builder 'labs-builder'"
      docker buildx create --name labs-builder --driver docker-container >/dev/null
    fi

    echo "==> production build  (linux/amd64)  ${IMAGE}:${VERSION}"
    echo "    (if the push is denied: echo \$GH_TOKEN | docker login ghcr.io -u <user> --password-stdin)"
    docker buildx build \
      --builder labs-builder \
      --platform linux/amd64 \
      -t "${IMAGE}:${VERSION}" \
      -t "${IMAGE}:latest" \
      --push \
      .
    echo
    echo "Pushed ${IMAGE}:${VERSION} and :latest  (linux/amd64)"
    echo "On the server:  APP_VERSION=${VERSION} docker compose up -d"
    ;;

  *)
    echo "usage: ./build.sh [local|prod <version>]" >&2
    exit 1
    ;;
esac
