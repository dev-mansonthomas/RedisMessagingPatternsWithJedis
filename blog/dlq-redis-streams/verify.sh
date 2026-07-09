#!/usr/bin/env bash
# Acceptance harness for the DLQ blog post (docs/specs/blog-dlq-post.md).
#
# Spins a throwaway redis:8.8-alpine on port ${BLOG_DLQ_PORT:-6399}, replays every
# verify-marked redis-cli block of index.md verbatim, runs the six language samples,
# and checks the editorial constraints (word count, pinned links, forbidden tech, image).
# Exit code: 0 only if every check passes.
set -u

PORT="${BLOG_DLQ_PORT:-6399}"
CONTAINER="blog-dlq-verify"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
INDEX="$SCRIPT_DIR/index.md"
PASS=0
FAIL=0

ok() { echo "PASS  $1"; PASS=$((PASS + 1)); }
verdict() { if [ "$1" -eq 0 ]; then ok "$2"; else ko "$2"; fi; }
ko() { echo "FAIL  $1${2:+ — $2}"; FAIL=$((FAIL + 1)); }

rcli() { redis-cli -p "$PORT" "$@"; }

start_redis() {
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  docker run -d --rm --name "$CONTAINER" -p "127.0.0.1:${PORT}:6379" redis:8.8-alpine >/dev/null
  for _ in $(seq 1 50); do
    [ "$(rcli PING 2>/dev/null)" = "PONG" ] && return 0
    sleep 0.2
  done
  echo "FATAL: Redis container did not become ready" >&2
  exit 1
}

cleanup() { docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; }
trap cleanup EXIT

# ---------------------------------------------------------------------------
# checks
# ---------------------------------------------------------------------------

chk_shellcheck() {
  local rc=0
  shellcheck "$SCRIPT_DIR/samples/setup.sh" >/dev/null 2>&1 || rc=1
  shellcheck "${BASH_SOURCE[0]}" >/dev/null 2>&1 || rc=1
  verdict "$rc" chk_shellcheck
}

chk_setup() {
  local rc=0
  (cd "$REPO_ROOT" && BLOG_DLQ_PORT="$PORT" "$SCRIPT_DIR/samples/setup.sh" >/dev/null 2>&1) || rc=1
  (cd "$REPO_ROOT" && BLOG_DLQ_PORT="$PORT" "$SCRIPT_DIR/samples/setup.sh" >/dev/null 2>&1) || rc=1
  [ -n "$(rcli FUNCTION LIST LIBRARYNAME stream_utils)" ] || rc=1
  rcli XINFO GROUPS test-stream 2>/dev/null | grep -q "test-group" || rc=1
  verdict "$rc" chk_setup
}

# Replays every ```bash block found between <!-- verify:begin --> / <!-- verify:end -->
# markers, in document order, in ONE shell (variables persist across blocks), with
# redis-cli rewritten to target the harness port. Then asserts the post's final state.
chk_walkthrough() {
  [ -f "$INDEX" ] || { ko chk_walkthrough "index.md missing"; return; }
  local blocks
  blocks=$(awk '/<!-- verify:begin -->/{b=1; next} /<!-- verify:end -->/{b=0} b' "$INDEX" |
    awk '/^```bash/{c=1; next} /^```/{c=0} c')
  if [ -z "$blocks" ]; then
    ko chk_walkthrough "no verify-marked bash blocks"
    return
  fi
  blocks="${blocks//redis-cli /redis-cli -p $PORT }"
  local rc=0
  (cd "$REPO_ROOT" && BLOG_DLQ_PORT="$PORT" eval "$blocks") >/dev/null 2>&1 || rc=1
  [ "$(rcli XLEN test-stream:dlq)" = "2" ] || rc=1
  [ "$(rcli XPENDING test-stream test-group | head -1)" = "0" ] || rc=1
  verdict "$rc" chk_walkthrough
}

# Seeds a poisoned (XNACK FATAL) pending entry: the sample's single FCALL must sweep it.
seed_fatal() {
  local id
  id=$(rcli XADD test-stream '*' type order.created order_id 9999 amount 10.00)
  rcli XREADGROUP GROUP test-group consumer-1 COUNT 10 STREAMS test-stream '>' >/dev/null
  rcli XNACK test-stream test-group FATAL IDS 1 "$id" >/dev/null
}

# $1 = lang, $2 = required command, $3... = run command (executed in samples/<lang>)
chk_sample() {
  local lang="$1" tool="$2"
  shift 2
  if ! command -v "$tool" >/dev/null 2>&1; then
    ko "chk_sample_$lang" "toolchain '$tool' missing (restart session after VM provisioning)"
    return
  fi
  local out rc=0
  seed_fatal
  out=$(cd "$SCRIPT_DIR/samples/$lang" && REDIS_URL="redis://localhost:$PORT" "$@" 2>&1) || rc=1
  echo "$out" | grep -q "DLQ .* -> " || rc=1
  # second run on drained state must stay graceful
  out=$(cd "$SCRIPT_DIR/samples/$lang" && REDIS_URL="redis://localhost:$PORT" "$@" 2>&1) || rc=1
  echo "$out" | grep -qi "no messages" || rc=1
  verdict "$rc" "chk_sample_$lang"
}

chk_wordcount() {
  [ -f "$INDEX" ] || { ko chk_wordcount "index.md missing"; return; }
  local words
  words=$(awk '/^```/{c=!c; next} !c' "$INDEX" | sed -E 's/\(https?:[^)]*\)//g' | wc -w)
  if [ "$words" -ge 1500 ] && [ "$words" -le 1800 ]; then
    ok "chk_wordcount ($words)"
  else
    ko chk_wordcount "$words words (want 1500–1800)"
  fi
}

chk_links() {
  [ -f "$INDEX" ] || { ko chk_links "index.md missing"; return; }
  local rc=0 url path
  while IFS= read -r url; do
    # deep links (blob/tree) must be pinned on the publication tag and point at real paths;
    # the bare repo / clone URL is exempt
    case "$url" in
      */blob/* | */tree/*)
        case "$url" in
          *"/blog-dlq-v1/"*) ;;
          *) rc=1 ;;
        esac
        path=$(echo "$url" | sed -E 's#.*/(blob|tree)/blog-dlq-v1/##; s/#.*$//')
        [ -n "$path" ] && [ ! -e "$REPO_ROOT/$path" ] && rc=1
        ;;
    esac
  done < <(grep -oE 'https://github\.com/dev-mansonthomas/RedisMessagingPatternsWithJedis[^) ]*' "$INDEX")
  grep -q "github.com/dev-mansonthomas/RedisMessagingPatternsWithJedis" "$INDEX" || rc=1
  verdict "$rc" chk_links
}

chk_forbidden() {
  [ -f "$INDEX" ] || { ko chk_forbidden "index.md missing"; return; }
  # prose only (fenced blocks stripped), minus the explicitly exempted closing section
  if awk '/<!-- forbidden-exempt:begin -->/{e=1} /<!-- forbidden-exempt:end -->/{e=0; next} !e' "$INDEX" |
    awk '/^```/{c=!c; next} !c' | grep -qiE 'websocket|sockjs|angular|spring'; then
    ko chk_forbidden
  else
    ok chk_forbidden
  fi
}

chk_img() {
  local rc=0
  [ -f "$SCRIPT_DIR/img/dlq-flow.png" ] || rc=1
  grep -qE '!\[[^]]+\]\(img/dlq-flow\.png\)' "$INDEX" 2>/dev/null || rc=1
  verdict "$rc" chk_img
}

# ---------------------------------------------------------------------------
# run
# ---------------------------------------------------------------------------

start_redis
chk_shellcheck
chk_setup
chk_walkthrough
chk_sample java mvn mvn -q -DskipTests compile exec:java
chk_sample python uv uv run dlq_example.py
chk_sample node npm sh -c 'npm install --silent >/dev/null 2>&1 && node dlq-example.mjs'
chk_sample go go go run .
chk_sample csharp dotnet dotnet run --nologo -v q
chk_sample rust cargo cargo run -q
chk_wordcount
chk_links
chk_forbidden
chk_img

echo "----------------------------------------"
echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
