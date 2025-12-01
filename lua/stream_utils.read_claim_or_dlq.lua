#!lua name=stream_utils
-- Library: stream_utils
-- Function: read_claim_or_dlq
-- Leverages Redis 8.4.0+ XREADGROUP CLAIM option
-- Combines: claim pending messages, route to DLQ, read new messages

-- Helper function to copy fields from one stream entry to another
local function xadd_copy_fields(stream, fields)
  local args = {}
  for i = 1, #fields, 2 do
    args[#args + 1] = fields[i]
    args[#args + 1] = fields[i + 1]
  end
  return redis.call('XADD', stream, '*', unpack(args))
end

redis.register_function('read_claim_or_dlq', function(keys, args)
  local stream      = keys[1]
  local dlq         = keys[2]
  local group       = args[1]
  local consumer    = args[2]
  local minIdle     = tonumber(args[3])
  local count       = tonumber(args[4])
  local maxDeliver  = tonumber(args[5])

  local messages_to_process = {}
  local dlq_ids = {}

  -- Step 1: Check pending messages and route to DLQ if needed
  local pending = redis.call('XPENDING', stream, group, 'IDLE', minIdle, '-', '+', count)
  
  local to_dlq_ids = {}
  for i = 1, #pending do
    local p = pending[i]  -- [id, consumer, idle, deliveries]
    local id = p[1]
    local deliveries = tonumber(p[4])
    
    if deliveries >= maxDeliver then
      to_dlq_ids[#to_dlq_ids + 1] = id
    end
  end

  -- Step 2: Route messages to DLQ (claim, copy to DLQ, ACK)
  if #to_dlq_ids > 0 then
    local claimed_dlq = redis.call('XCLAIM', stream, group, consumer, minIdle, unpack(to_dlq_ids))
    for i = 1, #claimed_dlq do
      local item = claimed_dlq[i]
      if item and item[1] and item[2] then
        local eid    = item[1]
        local fields = item[2]
        local new_dlq_id = xadd_copy_fields(dlq, fields)
        redis.call('XACK', stream, group, eid)
        dlq_ids[#dlq_ids + 1] = { eid, new_dlq_id }
      end
    end
  end

  -- Step 3: Use XREADGROUP with CLAIM to get pending + new messages
  -- XREADGROUP GROUP <group> <consumer> COUNT <count> CLAIM <minIdle> STREAMS <stream> >
  local result = redis.call('XREADGROUP', 'GROUP', group, consumer, 
                            'COUNT', count, 
                            'CLAIM', minIdle,
                            'STREAMS', stream, '>')

  -- Step 4: Parse results
  if result and #result > 0 then
    local stream_data = result[1]  -- [[stream_name, [[id, fields], ...]]]
    if stream_data and #stream_data > 1 then
      local entries = stream_data[2]
      for i = 1, #entries do
        local entry = entries[i]
        if entry and entry[1] and entry[2] then
          messages_to_process[#messages_to_process + 1] = { entry[1], entry[2] }
        end
      end
    end
  end

  return { messages_to_process, dlq_ids }
end)
