#!/usr/bin/env bash
set -euo pipefail

###############################################################################
# Redis DLQ demo (Streams + Redis Function "claim_or_dlq")
# Assumptions:
# - Redis is already running and reachable
# - The function is already loaded:
#     #!lua name=stream_utils
#     redis.register_function('claim_or_dlq', function(keys, args) ... end)
#
# What this script does:
# 1) Nominal: message is reclaimed and ACKed (no DLQ)
# 2) DLQ: same maxDeliveries=2, message exceeds the threshold and is moved to DLQ
#
# Customize connection with these environment variables:
#   REDIS_HOST (default 127.0.0.1)
#   REDIS_PORT (default 6379)
#   REDIS_USER (optional)
#   REDIS_PASS (optional)
#   REDIS_TLS  (set to 1 to enable --tls)
###############################################################################

REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-6379}"
REDIS_USER="${REDIS_USER:-}"
REDIS_PASS="${REDIS_PASS:-}"
REDIS_TLS="${REDIS_TLS:-0}"

STREAM="mystream"
GROUP="mygroup"
DLQ_STREAM="mystream:dlq"

say() {
  echo
  echo "### $*"
}

run() {
  echo
  echo "+ $*"
  echo
  read -rp "Press ENTER to execute this command..."
  eval "$@"
}

cli() {
  local args=(--raw -h "$REDIS_HOST" -p "$REDIS_PORT")
  [[ "$REDIS_TLS" == "1" ]] && args+=(--tls)
  [[ -n "$REDIS_USER" ]] && args+=(--user "$REDIS_USER")
  [[ -n "$REDIS_PASS" ]] && args+=(--pass "$REDIS_PASS")
  redis-cli "${args[@]}" "$@"
}

first_pending_id() {
  # Returns the first pending entry ID for the group, or empty if none
  # XPENDING <stream> <group> - + <count>
  cli XPENDING "$STREAM" "$GROUP" - + 10 2>/dev/null | awk 'NR==1{print $1}'
}

wait_for_redis() {
  say "Checking Redis availability at $REDIS_HOST:$REDIS_PORT..."
  for _ in {1..30}; do
    if cli PING >/dev/null 2>&1; then
      echo "Redis is reachable."
      return 0
    fi
    sleep 1
  done
  echo "Timeout: Redis did not respond."
  exit 1
}

###############################################################################
# 0) Sanity check and initialization
###############################################################################
wait_for_redis

say "Cleanup demo keys (stream and DLQ)"
run "cli DEL $STREAM $DLQ_STREAM >/dev/null || true"

say "Create a consumer group on the business stream"
# '$' means only new messages will be delivered.
run "cli XGROUP CREATE $STREAM $GROUP '$' MKSTREAM || true"

###############################################################################
# 1) SCENARIO: Nominal path (reclaim then ACK, message stays out of DLQ)
###############################################################################
say "SCENARIO 1: Nominal path (reclaim, process, ACK, no DLQ)"

say "Producer sends one message to the business stream"
run "cli XADD $STREAM '*' type order id 1001"

say "Worker c1 reads the message without ACK (message becomes pending, deliveries=1)"
run "cli XREADGROUP GROUP $GROUP c1 COUNT 1 STREAMS $STREAM '>'"

say "Another worker c2 reclaims the pending message for processing"
say "We call the function with: minIdle=0, count=100, maxDeliveries=2"
run "cli FCALL claim_or_dlq 2 $STREAM $DLQ_STREAM $GROUP c2 0 100 2"

PENDING_ID="$(first_pending_id || true)"
echo "Pending ID after reclaim: ${PENDING_ID:-<none>}"

if [[ -n "${PENDING_ID:-}" ]]; then
  say "Application processes the message and ACKs it"
  run "cli XACK $STREAM $GROUP $PENDING_ID"
fi

say "Verification: no more pending entries; DLQ must be empty"
run "cli XPENDING $STREAM $GROUP - + 10 || true"
run "cli XLEN $DLQ_STREAM || true"

###############################################################################
# 2) SCENARIO: DLQ path (exceed delivery threshold with maxDeliveries=2)
###############################################################################
say "SCENARIO 2: DLQ path (delivery threshold reached, message is moved to DLQ)"

say "Producer sends a new message"
run "cli XADD $STREAM '*' type order id 2001"

say "Worker c1 reads it without ACK (first delivery, deliveries=1)"
run "cli XREADGROUP GROUP $GROUP c1 COUNT 1 STREAMS $STREAM '>'"

say "We reclaim once to increase deliveries to 2 (still not DLQ yet, because the threshold check uses XPENDING before this reclaim)"
run "cli FCALL claim_or_dlq 2 $STREAM $DLQ_STREAM $GROUP c2 0 100 2"

say "Now deliveries are already 2. Calling the function again will see deliveries>=2 in XPENDING and route the entry to the DLQ."
run "cli FCALL claim_or_dlq 2 $STREAM $DLQ_STREAM $GROUP c3 0 100 2"

say "Verification: list entries in the DLQ stream"
run "cli XRANGE $DLQ_STREAM - +"

say "Verification: message should no longer be pending in the main stream"
run "cli XPENDING $STREAM $GROUP - + 10 || true"

echo
echo "Demo completed."
echo "Recap:"
echo "- Scenario 1: message reclaimed and ACKed, DLQ length should be 0."
echo "- Scenario 2: with maxDeliveries=2, after one extra reclaim the next call sends it to the DLQ and ACKs it in the main stream."