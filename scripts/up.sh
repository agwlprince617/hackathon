#!/usr/bin/env bash
# Start the Java backend and its required infrastructure with Podman.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mkdir -p .bin data

COMPOSE="${COMPOSE:-podman compose}"
if ! podman info >/dev/null 2>&1; then
  echo "Podman is not running; start it with: podman machine start" >&2
  exit 1
fi
read -r -a COMPOSE_CMD <<< "$COMPOSE"
compose() { "${COMPOSE_CMD[@]}" "$@"; }

stop_pid() {
  local f="$1"
  if [[ -f "$f" ]] && kill -0 "$(cat "$f")" 2>/dev/null; then
    kill "$(cat "$f")" 2>/dev/null || true
  fi
}
stop_pid .bin/authority.pid
stop_pid .bin/student.pid
stop_pid .bin/reference.pid
stop_pid .bin/buggy.pid

mvn -q -f "$ROOT/backend/java/pom.xml" -DskipTests package
compose build reservation-api
compose stop reservation-api >/dev/null 2>&1 || true
compose up -d postgres authority

echo "waiting for PostgreSQL..."
postgres_ready=0
for i in $(seq 1 40); do
  if compose exec -T postgres pg_isready -U postgres -d student >/dev/null 2>&1; then
    postgres_ready=1
    break
  fi
  sleep 1
done
if [[ $postgres_ready -ne 1 ]]; then
  compose ps
  echo "PostgreSQL did not become ready within 40 seconds" >&2
  exit 1
fi

compose exec -T postgres psql -v ON_ERROR_STOP=1 -U postgres -d student \
  < "$ROOT/backend/java/schema.sql"
compose up -d reservation-api

echo "waiting for APIs..."
for i in $(seq 1 40); do
  if curl -sf http://127.0.0.1:9000/health >/dev/null \
    && curl -sf http://127.0.0.1:8080/health >/dev/null; then
    echo "authority     http://127.0.0.1:9000"
    echo "Java API     http://127.0.0.1:8080"
    exit 0
  fi
  sleep 1
done

compose ps
echo "services did not become healthy within 40 seconds" >&2
exit 1
