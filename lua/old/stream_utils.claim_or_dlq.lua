#!lua name=stream_utils
-- Library: stream_utils
-- Registers: claim_or_dlq(stream, dlq, group, consumer, minIdleMs, count, maxDeliveries)
-- Returns: { {entryId, {field1, value1, ...}}, ... } for entries re-claimed for processing

--[[
================================================================================
FUNCTION: claim_or_dlq
================================================================================

DESCRIPTION:
  Processes PENDING (unacknowledged) messages from a Redis Stream and routes
  them based on their delivery count:
  - deliveryCount < maxDeliveries: Reclaim for reprocessing
  - deliveryCount >= maxDeliveries: Route to DLQ and ACK

IMPORTANT:
  This function does NOT read new messages. It only processes messages that
  have already been read but not acknowledged (PENDING).

--------------------------------------------------------------------------------
DEVELOPER FLOW (Recommended)
--------------------------------------------------------------------------------

Use the unified Java API instead of calling this function directly:

  List<DLQMessage> messages = dlqService.getNextMessages(params, 10);

  for (DLQMessage msg : messages) {
      try {
          processMessage(msg);
          dlqService.acknowledgeMessage(msg.getStreamName(),
                                       msg.getConsumerGroup(),
                                       msg.getId());
      } catch (Exception e) {
          // No ACK = automatic retry
      }
  }

Benefits:
  - Single loop (no duplication)
  - Full context (deliveryCount, isRetry)
  - Automatic retry handling

--------------------------------------------------------------------------------
PARAMETERS
--------------------------------------------------------------------------------

KEYS:
  keys[1] = source stream (e.g., "orders")
  keys[2] = DLQ stream (e.g., "orders:dlq")

ARGS:
  args[1] = group (e.g., "processors")
  args[2] = consumer (e.g., "worker-1")
  args[3] = minIdle in ms (e.g., 5000 = 5 seconds)
  args[4] = count (max messages to process, e.g., 10)
  args[5] = maxDeliveries (retry threshold, e.g., 3)

RETURN:
  Table with two arrays:
    reclaimed: Messages reclaimed for reprocessing (Redis Stream format)
    dlq: Message IDs that were routed to DLQ

--------------------------------------------------------------------------------
EXAMPLE
--------------------------------------------------------------------------------

FCALL claim_or_dlq 2 orders orders:dlq processors worker-1 5000 10 3

This processes up to 10 pending messages idle for at least 5 seconds:
  - Messages with deliveryCount < 3: Reclaimed for retry
  - Messages with deliveryCount >= 3: Sent to DLQ

For complete documentation, see: lua/CLAIM_OR_DLQ_GUIDE.md

================================================================================
]]--

local function xadd_copy_fields(dst_key, fields)
  local args = { dst_key, '*' }
  for j = 1, #fields, 2 do
    args[#args + 1] = fields[j]
    args[#args + 1] = fields[j + 1]
  end
  return redis.call('XADD', unpack(args))
end

redis.register_function('claim_or_dlq', function(keys, args)
  -- keys[1] = source stream
  -- keys[2] = DLQ stream
  -- args[1] = group
  -- args[2] = consumer
  -- args[3] = minIdle (ms)
  -- args[4] = count
  -- args[5] = maxDeliveries

  local stream      = keys[1]
  local dlq         = keys[2]
  local group       = args[1]
  local consumer    = args[2]
  local minIdle     = tonumber(args[3])
  local count       = tonumber(args[4])
  local maxDeliver  = tonumber(args[5])

  -- 1) Find pending entries with idle >= minIdle (up to count)
  local pending = redis.call('XPENDING', stream, group, 'IDLE', minIdle, '-', '+', count)

  local to_process_ids = {}
  local to_dlq_ids     = {}

  for i = 1, #pending do
    local p = pending[i]           -- [id, consumer, idle, deliveries]
    local id = p[1]
    local deliveries = tonumber(p[4])
    if deliveries >= maxDeliver then
      to_dlq_ids[#to_dlq_ids + 1] = id
    else
      to_process_ids[#to_process_ids + 1] = id
    end
  end

  local reclaimed = {}
  local dlq_ids = {}

  -- 2) XCLAIM and return payload for those under the threshold
  if #to_process_ids > 0 then
    local claimed = redis.call('XCLAIM', stream, group, consumer, minIdle, unpack(to_process_ids))
    -- claimed = { {id, {field, value, ...}} | nil, ... }
    for i = 1, #claimed do
      local item = claimed[i]
      if item and item[1] and item[2] then
        local eid    = item[1]
        local fields = item[2]
        reclaimed[#reclaimed + 1] = { eid, fields }
      end
    end
  end

  -- 3) XCLAIM, XADD to DLQ, and XACK those at/over the threshold
  if #to_dlq_ids > 0 then
    local claimed_dlq = redis.call('XCLAIM', stream, group, consumer, minIdle, unpack(to_dlq_ids))
    for i = 1, #claimed_dlq do
      local item = claimed_dlq[i]
      if item and item[1] and item[2] then
        local eid    = item[1]
        local fields = item[2]
        local new_dlq_id = xadd_copy_fields(dlq, fields)  -- full copy into DLQ
        redis.call('XACK', stream, group, eid)            -- prevent further deliveries
        dlq_ids[#dlq_ids + 1] = { eid, fields, new_dlq_id }
      end
    end
  end

  return { reclaimed, dlq_ids }
end)