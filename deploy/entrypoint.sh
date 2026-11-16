#!/usr/bin/env bash
# UTF-8 without a BOM; LF line endings (see .gitattributes).
# In demo mode, prepare synthetic history and a trained model once, then run the command.
set -euo pipefail

if [ "${NGANYAFLOW_DEMO_MODE:-0}" = "1" ] || [ "${NGANYAFLOW_DEMO_MODE:-}" = "true" ]; then
  if [ ! -f "${NGANYAFLOW_RUNTIME_DIR:-/app/runtime}/active.joblib" ]; then
    echo "Demo mode: seeding synthetic history and training the first model ..."
    args=()
    [ -n "${NGANYAFLOW_DEMO_ENUMERATOR_CODE:-}" ] && args+=(--enumerator-code "$NGANYAFLOW_DEMO_ENUMERATOR_CODE")
    [ -n "${NGANYAFLOW_DEMO_CREW_CODE:-}" ] && args+=(--crew-code "$NGANYAFLOW_DEMO_CREW_CODE")
    nganyaflow seed-demo "${args[@]}"
  else
    echo "Demo mode: trained model already present; skipping seed."
  fi
fi
exec "$@"
