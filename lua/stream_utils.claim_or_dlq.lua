#!lua name=stream_utils
-- Library: stream_utils
-- Registers: claim_or_dlq(stream, dlq, group, consumer, minIdleMs, count, maxDeliveries)
-- Returns: { {entryId, {field1, value1, ...}}, ... } for entries re-claimed for processing

--[[local _unpack = unpack or function(t, i, j)
  -- mini fallback si jamais 'unpack' n'existait pas
  i = i or 1; j = j or #t
  if i > j then return end
  return t[i], unpack(t, i + 1, j)
end]]

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

  local result = {}

  -- 2) XCLAIM and return payload for those under the threshold
  if #to_process_ids > 0 then
    local claimed = redis.call('XCLAIM', stream, group, consumer, minIdle, unpack(to_process_ids))
    -- claimed = { {id, {field, value, ...}} | nil, ... }
    for i = 1, #claimed do
      local item = claimed[i]
      if item and item[1] and item[2] then
        local eid    = item[1]
        local fields = item[2]
        result[#result + 1] = { eid, fields }
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
        xadd_copy_fields(dlq, fields)             -- full copy into DLQ
        redis.call('XACK', stream, group, eid)    -- prevent further deliveries
      end
    end
  end

  return result
end)