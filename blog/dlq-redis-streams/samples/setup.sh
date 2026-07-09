#!/usr/bin/env bash
# One-shot setup for the DLQ blog-post walkthrough (see ../index.md).
# Loads the stream_utils Redis Functions library and creates the consumer group.
#
# Requires: Redis 8.8+ reachable on localhost:${BLOG_DLQ_PORT:-6379}, redis-cli.
# Run from the repository root. Idempotent: safe to re-run.
set -euo pipefail

PORT="${BLOG_DLQ_PORT:-6379}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

# redis-cli exits 0 even on error replies, so match the reply text instead.
loaded=$(redis-cli -p "$PORT" -x FUNCTION LOAD REPLACE < "$REPO_ROOT/lua/stream_utils.lua")
if [ "$loaded" != "stream_utils" ]; then
  echo "FUNCTION LOAD failed: $loaded" >&2
  exit 1
fi

created=$(redis-cli -p "$PORT" XGROUP CREATE test-stream test-group '$' MKSTREAM 2>&1 || true)
case "$created" in
  OK | *BUSYGROUP*) ;; # created, or already there — both fine
  *)
    echo "XGROUP CREATE failed: $created" >&2
    exit 1
    ;;
esac

echo "setup complete: library 'stream_utils' loaded, group 'test-group' ready on test-stream"
