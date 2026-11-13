#!/usr/bin/env bash
# UTF-8 without BOM; LF line endings. Run in Git Bash, WSL, macOS, or Linux.
set -euo pipefail
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export PYTHONUTF8=1
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
IMAGE_NAME="${NGANYAFLOW_IMAGE:-nganyaflow:local}"
if ! command -v docker >/dev/null 2>&1; then
  printf '%s\n' 'Docker was not found. Install/start Docker Desktop, then retry.' >&2
  exit 1
fi
docker build --load --file "$SCRIPT_DIR/Dockerfile" --tag "$IMAGE_NAME" "$@" "$SCRIPT_DIR"
printf '\nBuilt %s\n' "$IMAGE_NAME"
printf '%s\n' 'See docs/development.md for run commands and admin password retrieval.'
