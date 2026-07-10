#!/usr/bin/env bash
# One-shot setup for the DLQ blog-post walkthrough (see ../index.md) and samples.
#
# Ensures a Redis 8.8+ is reachable, loads the stream_utils Redis Functions library,
# and creates the consumer group. Prints ONLY the port to stdout (all messages go to
# stderr) so callers can capture it:
#
#     export REDIS_URL="redis://localhost:$(./blog/dlq-redis-streams/samples/setup.sh)"
#
# Redis selection:
#   * If something answers PING on localhost:${BLOG_DLQ_PORT:-6379}, that is used.
#   * Otherwise a throwaway redis:8.8-alpine is started in Docker on a free port
#     (container name: blog-dlq-redis) and that port is used.
#
# Run from the repository root. Idempotent: safe to re-run.
set -euo pipefail

PORT="${BLOG_DLQ_PORT:-6379}"
CONTAINER="blog-dlq-redis"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

log() { echo "$@" >&2; }
redis_up() { [ "$(redis-cli -p "$1" PING 2>/dev/null)" = "PONG" ]; }

# ---------------------------------------------------------------------------
# 1. Make sure a Redis is reachable — discover the running one, or start Docker.
# ---------------------------------------------------------------------------
if redis_up "$PORT"; then
  log "Using the Redis already listening on localhost:$PORT."
else
  if ! command -v docker >/dev/null 2>&1; then
    log "No Redis on localhost:$PORT and Docker is not installed — cannot continue."
    exit 1
  fi
  if [ -n "$(docker ps -q -f "name=^${CONTAINER}$")" ]; then
    log "Reusing the running '$CONTAINER' container."
  else
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
    log "No Redis on localhost:$PORT — starting a throwaway redis:8.8-alpine in Docker..."
    docker run -d --name "$CONTAINER" -p 127.0.0.1::6379 redis:8.8-alpine >/dev/null
  fi
  # Docker picked a free host port for us; read it back (e.g. "127.0.0.1:49153").
  mapping="$(docker port "$CONTAINER" 6379/tcp)"
  PORT="${mapping##*:}"
  for _ in $(seq 1 50); do
    if redis_up "$PORT"; then break; fi
    sleep 0.2
  done
  if ! redis_up "$PORT"; then
    log "Redis container did not become ready."
    exit 1
  fi
  log "Redis 8.8 ready in Docker on localhost:$PORT (stop it with: docker rm -f $CONTAINER)."
fi

# ---------------------------------------------------------------------------
# 2. Load the Redis Functions library (idempotent via REPLACE).
# ---------------------------------------------------------------------------
# redis-cli exits 0 even on error replies, so match the reply text instead.
loaded="$(redis-cli -p "$PORT" -x FUNCTION LOAD REPLACE < "$REPO_ROOT/lua/stream_utils.lua")"
if [ "$loaded" != "stream_utils" ]; then
  log "FUNCTION LOAD failed: $loaded"
  exit 1
fi

# ---------------------------------------------------------------------------
# 3. Create the consumer group (tolerate "already exists").
# ---------------------------------------------------------------------------
created="$(redis-cli -p "$PORT" XGROUP CREATE test-stream test-group '$' MKSTREAM 2>&1 || true)"
case "$created" in
  OK | *BUSYGROUP*) ;; # created, or already there — both fine
  *)
    log "XGROUP CREATE failed: $created"
    exit 1
    ;;
esac

log "setup complete: library 'stream_utils' loaded, group 'test-group' ready on test-stream (port $PORT)."

# The ONLY thing on stdout: the port, so `$(setup.sh)` yields it.
echo "$PORT"
