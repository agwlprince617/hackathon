#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
for f in .bin/authority.pid .bin/student.pid .bin/reference.pid .bin/buggy.pid; do
  if [[ -f "$f" ]] && kill -0 "$(cat "$f")" 2>/dev/null; then
    kill "$(cat "$f")" 2>/dev/null || true
  fi
done
COMPOSE="${COMPOSE:-podman compose}"
read -r -a COMPOSE_CMD <<< "$COMPOSE"
"${COMPOSE_CMD[@]}" down >/dev/null 2>&1 || true
echo "stopped"
