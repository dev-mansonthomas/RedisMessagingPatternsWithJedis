#!/bin/bash

# Test script to verify that messages are deleted from test-stream after reaching maxDeliveries

echo "🧪 Testing DLQ with message deletion"
echo "======================================"

# Cleanup
echo "1. Cleaning up..."
redis-cli DEL test-stream test-stream:dlq
redis-cli FUNCTION DELETE stream_utils 2>/dev/null

# Create consumer group
echo "2. Creating consumer group..."
redis-cli XGROUP CREATE test-stream test-group 0 MKSTREAM

# Add a test message
echo "3. Adding test message..."
MSG_ID=$(redis-cli XADD test-stream "*" type order.created order_id 9999 amount 100.00)
echo "   Message ID: $MSG_ID"

# Check stream length
echo "4. Initial stream length:"
redis-cli XLEN test-stream

# Read without ACK (delivery 1)
echo "5. Reading message (delivery 1)..."
redis-cli XREADGROUP GROUP test-group consumer-1 COUNT 1 STREAMS test-stream ">" > /dev/null

# Check PENDING
echo "6. Checking PENDING (should show delivery=1):"
redis-cli XPENDING test-stream test-group - + 10

# Wait a bit for idle time
sleep 2

# Start Spring Boot and load Lua function
echo "7. Starting Spring Boot to load Lua function..."
echo "   (This will load the read_claim_or_dlq function)"
echo "   Press Ctrl+C after you see 'Started RedisMessagingPatternsApplication'"
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"

