#!/bin/bash

# Reload Lua function into Redis

echo "Reloading Lua function into Redis..."

redis-cli FUNCTION LOAD REPLACE "$(cat lua/stream_utils.claim_or_dlq.lua)"

if [ $? -eq 0 ]; then
    echo "✅ Lua function reloaded successfully!"
else
    echo "❌ Failed to reload Lua function"
    exit 1
fi

