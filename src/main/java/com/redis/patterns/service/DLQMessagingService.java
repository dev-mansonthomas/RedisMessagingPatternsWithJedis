package com.redis.patterns.service;

import com.redis.patterns.config.DLQProperties;
import com.redis.patterns.dto.DLQConfigRequest;
import com.redis.patterns.dto.DLQEvent;
import com.redis.patterns.dto.DLQMessage;
import com.redis.patterns.dto.DLQParameters;
import com.redis.patterns.dto.DLQResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;

import java.util.*;

/**
 * Service implementing the Dead Letter Queue (DLQ) messaging pattern.
 * 
 * This service provides comprehensive DLQ functionality including:
 * - Message production to streams
 * - Claiming and processing pending messages
 * - Automatic routing to DLQ based on delivery thresholds
 * - Consumer group management
 * - Real-time event broadcasting via WebSocket
 * 
 * The service uses the claim_or_dlq Lua function for atomic operations,
 * ensuring consistency and performance.
 * 
 * Best Practices Implemented:
 * - Connection pooling for optimal performance
 * - Comprehensive error handling and logging
 * - Resource cleanup with try-with-resources
 * - Thread-safe operations
 * - Detailed event tracking for monitoring
 * 
 * @author Redis Patterns Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DLQMessagingService {

    private final JedisPool jedisPool;
    private final DLQProperties dlqProperties;
    private final WebSocketEventService webSocketEventService;
    private final DLQConfigService dlqConfigService;

    private static final String FUNCTION_NAME = "claim_or_dlq";
    private static final String LIBRARY_NAME = "stream_utils";

    /**
     * Initializes the consumer group for a stream if it doesn't exist.
     * 
     * This method ensures the consumer group is ready before any message consumption.
     * If the group already exists, the error is logged but not thrown.
     * 
     * @param streamName Name of the stream
     * @param groupName Name of the consumer group
     * @throws RuntimeException if group creation fails for reasons other than already existing
     */
    public void initializeConsumerGroup(String streamName, String groupName) {
        log.info("Initializing consumer group '{}' for stream '{}'", groupName, streamName);
        
        try (var jedis = jedisPool.getResource()) {
            try {
                // Create group starting from the end of the stream ($)
                // This means only new messages will be delivered
                jedis.xgroupCreate(streamName, groupName, new StreamEntryID(), true);
                log.info("Consumer group '{}' created successfully", groupName);
                
                // Broadcast event
                webSocketEventService.broadcastEvent(DLQEvent.builder()
                    .eventType(DLQEvent.EventType.INFO)
                    .streamName(streamName)
                    .details("Consumer group '" + groupName + "' initialized")
                    .build());
                    
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                    log.info("Consumer group '{}' already exists", groupName);
                } else {
                    log.error("Failed to create consumer group '{}'", groupName, e);
                    throw new RuntimeException("Failed to initialize consumer group", e);
                }
            }
        } catch (Exception e) {
            log.error("Error accessing Redis", e);
            throw new RuntimeException("Failed to connect to Redis", e);
        }
    }

    /**
     * Produces a message to the specified stream.
     * 
     * Messages are added with auto-generated IDs and can contain multiple field-value pairs.
     * 
     * @param streamName Name of the stream
     * @param payload Message payload as field-value pairs
     * @return The generated message ID
     * @throws RuntimeException if message production fails
     */
    public String produceMessage(String streamName, Map<String, String> payload) {
        log.debug("Producing message to stream '{}': {}", streamName, payload);
        
        try (var jedis = jedisPool.getResource()) {
            // Add message with auto-generated ID (*)
            StreamEntryID messageId = jedis.xadd(streamName, XAddParams.xAddParams(), payload);
            
            log.debug("Message produced with ID: {}", messageId);

            // Broadcast event
            webSocketEventService.broadcastEvent(DLQEvent.builder()
                .eventType(DLQEvent.EventType.MESSAGE_PRODUCED)
                .messageId(messageId.toString())
                .payload(payload)
                .streamName(streamName)
                .details("Message produced")
                .build());

            return messageId.toString();

        } catch (Exception e) {
            log.error("Failed to produce message to stream '{}'", streamName, e);
            throw new RuntimeException("Failed to produce message", e);
        }
    }

    /**
     * Executes the claim_or_dlq Lua function to process pending messages.
     *
     * This is the core DLQ operation that:
     * 1. Identifies pending messages that have been idle for minIdleMs
     * 2. Checks their delivery count
     * 3. Reclaims messages below the threshold for reprocessing
     * 4. Routes messages at/above the threshold to the DLQ
     *
     * The operation is atomic, ensuring consistency even under high concurrency.
     *
     * @param params DLQ parameters for the operation
     * @return Response containing operation results
     * @throws RuntimeException if the operation fails
     */
    public DLQResponse claimOrDLQ(DLQParameters params) {
        log.info("Executing claim_or_dlq with params: {}", params);

        try (var jedis = jedisPool.getResource()) {
            // Call the Lua function
            // KEYS: [stream, dlq_stream]
            // ARGS: [group, consumer, minIdle, count, maxDeliveries]
            Object result = jedis.fcall(
                FUNCTION_NAME,
                Arrays.asList(params.getStreamName(), params.getDlqStreamName()),
                Arrays.asList(
                    params.getConsumerGroup(),
                    params.getConsumerName(),
                    String.valueOf(params.getMinIdleMs()),
                    String.valueOf(params.getCount()),
                    String.valueOf(params.getMaxDeliveries())
                )
            );

            // Parse the result: { reclaimed: [...], dlq: [...] }
            List<String> reclaimedIds = new ArrayList<>();
            List<String> dlqIds = new ArrayList<>();
            int messagesReclaimed = 0;
            int messagesSentToDLQ = 0;

            if (result instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> resultList = (List<Object>) result;

                // First element: reclaimed messages
                if (resultList.size() > 0 && resultList.get(0) instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> reclaimedList = (List<Object>) resultList.get(0);

                    for (Object entry : reclaimedList) {
                        if (entry instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<Object> entryList = (List<Object>) entry;

                            if (!entryList.isEmpty()) {
                                String messageId = entryList.get(0).toString();
                                reclaimedIds.add(messageId);
                                messagesReclaimed++;

                                // Parse payload if available
                                Map<String, String> payload = new HashMap<>();
                                if (entryList.size() > 1 && entryList.get(1) instanceof List) {
                                    @SuppressWarnings("unchecked")
                                    List<Object> fields = (List<Object>) entryList.get(1);
                                    for (int i = 0; i < fields.size(); i += 2) {
                                        if (i + 1 < fields.size()) {
                                            payload.put(fields.get(i).toString(), fields.get(i + 1).toString());
                                        }
                                    }
                                }

                                // Don't broadcast reclaim events (they're not new messages)
                            }
                        }
                    }
                }

                // Second element: messages sent to DLQ
                if (resultList.size() > 1 && resultList.get(1) instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> dlqList = (List<Object>) resultList.get(1);

                    for (Object entry : dlqList) {
                        if (entry instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<Object> entryList = (List<Object>) entry;

                            if (entryList.size() >= 3) {
                                String originalId = entryList.get(0).toString();
                                String newDlqId = entryList.get(2).toString();
                                dlqIds.add(originalId);
                                messagesSentToDLQ++;

                                // Parse payload
                                Map<String, String> payload = new HashMap<>();
                                if (entryList.get(1) instanceof List) {
                                    @SuppressWarnings("unchecked")
                                    List<Object> fields = (List<Object>) entryList.get(1);
                                    for (int i = 0; i < fields.size(); i += 2) {
                                        if (i + 1 < fields.size()) {
                                            payload.put(fields.get(i).toString(), fields.get(i + 1).toString());
                                        }
                                    }
                                }

                                // Broadcast: message deleted from main stream
                                webSocketEventService.broadcastEvent(DLQEvent.builder()
                                    .eventType(DLQEvent.EventType.MESSAGE_DELETED)
                                    .messageId(originalId)
                                    .streamName(params.getStreamName())
                                    .details("Message routed to DLQ (max deliveries reached)")
                                    .build());

                                // Broadcast: message added to DLQ
                                webSocketEventService.broadcastEvent(DLQEvent.builder()
                                    .eventType(DLQEvent.EventType.MESSAGE_PRODUCED)
                                    .messageId(newDlqId)
                                    .payload(payload)
                                    .streamName(params.getDlqStreamName())
                                    .details("Message routed from main stream (max deliveries reached)")
                                    .build());
                            }
                        }
                    }
                }
            }

            log.info("claim_or_dlq completed: {} messages reclaimed, {} sent to DLQ",
                messagesReclaimed, messagesSentToDLQ);

            return DLQResponse.builder()
                .success(true)
                .messagesReclaimed(messagesReclaimed)
                .reclaimedMessageIds(reclaimedIds)
                .details("Processed " + messagesReclaimed + " messages")
                .build();

        } catch (Exception e) {
            log.error("Failed to execute claim_or_dlq", e);

            webSocketEventService.broadcastEvent(DLQEvent.builder()
                .eventType(DLQEvent.EventType.ERROR)
                .streamName(params.getStreamName())
                .details("Error executing claim_or_dlq: " + e.getMessage())
                .build());

            return DLQResponse.builder()
                .success(false)
                .errorMessage(e.getMessage())
                .build();
        }
    }

    /**
     * Consumes new messages from the stream using XREADGROUP.
     *
     * This method reads new messages (using '>') that haven't been delivered yet.
     * Messages are automatically added to the pending entries list (PEL).
     *
     * @param params DLQ parameters
     * @param count Number of messages to read
     * @return List of consumed message IDs
     */
    public List<String> consumeMessages(DLQParameters params, int count) {
        log.debug("Consuming {} messages from stream '{}'", count, params.getStreamName());

        List<String> messageIds = new ArrayList<>();

        try (var jedis = jedisPool.getResource()) {
            // Read new messages using '>' special ID
            Map<String, StreamEntryID> streams = new HashMap<>();
            streams.put(params.getStreamName(), StreamEntryID.XREADGROUP_UNDELIVERED_ENTRY);

            var entries = jedis.xreadGroup(
                params.getConsumerGroup(),
                params.getConsumerName(),
                XReadGroupParams.xReadGroupParams()
                    .count(count)
                    .block(0),
                streams
            );

            if (entries != null) {
                for (var streamEntries : entries) {
                    for (StreamEntry entry : streamEntries.getValue()) {
                        String messageId = entry.getID().toString();
                        messageIds.add(messageId);

                        // Broadcast consumption event
                        webSocketEventService.broadcastEvent(DLQEvent.builder()
                            .eventType(DLQEvent.EventType.INFO)
                            .messageId(messageId)
                            .payload(entry.getFields())
                            .consumer(params.getConsumerName())
                            .streamName(params.getStreamName())
                            .details("Message consumed (pending)")
                            .build());
                    }
                }
            }

            log.debug("Consumed {} messages", messageIds.size());
            return messageIds;

        } catch (Exception e) {
            log.error("Failed to consume messages", e);
            throw new RuntimeException("Failed to consume messages", e);
        }
    }

    /**
     * Acknowledges a message, removing it from the pending entries list.
     *
     * This should be called after successful message processing.
     *
     * @param streamName Name of the stream
     * @param groupName Consumer group name
     * @param messageId Message ID to acknowledge
     * @return true if acknowledged successfully
     */
    public boolean acknowledgeMessage(String streamName, String groupName, String messageId) {
        log.debug("Acknowledging message {} in stream '{}'", messageId, streamName);

        try (var jedis = jedisPool.getResource()) {
            long acked = jedis.xack(streamName, groupName, new StreamEntryID(messageId));

            if (acked > 0) {
                // Broadcast acknowledgment event (MESSAGE_DELETED so frontend removes it)
                webSocketEventService.broadcastEvent(DLQEvent.builder()
                    .eventType(DLQEvent.EventType.MESSAGE_DELETED)
                    .messageId(messageId)
                    .streamName(streamName)
                    .details("Message acknowledged and removed")
                    .build());

                log.debug("Message {} acknowledged successfully", messageId);
                return true;
            }

            return false;

        } catch (Exception e) {
            log.error("Failed to acknowledge message {}", messageId, e);
            return false;
        }
    }

    /**
     * Unified method to get the next batch of messages to process.
     *
     * This method simplifies message consumption by combining:
     * 1. Retry messages (from claim_or_dlq) - processed first
     * 2. New messages (from XREADGROUP) - if needed to reach count
     *
     * This provides a single, unified interface for developers, eliminating
     * the need to manage two separate loops for new messages and retries.
     *
     * Usage example:
     * <pre>
     * List&lt;DLQMessage&gt; messages = dlqService.getNextMessages(params, 10);
     *
     * for (DLQMessage msg : messages) {
     *     try {
     *         processMessage(msg);
     *         dlqService.acknowledgeMessage(msg.getStreamName(), msg.getConsumerGroup(), msg.getId());
     *     } catch (Exception e) {
     *         // Message will be automatically retried
     *         log.error("Failed to process message", e);
     *     }
     * }
     * </pre>
     *
     * @param params DLQ parameters
     * @param count Maximum number of messages to retrieve
     * @return List of messages ready for processing (retries + new messages)
     */
    public List<DLQMessage> getNextMessages(DLQParameters params, int count) {
        log.debug("Getting next {} messages from stream '{}'", count, params.getStreamName());

        List<DLQMessage> result = new ArrayList<>();

        try (var jedis = jedisPool.getResource()) {
            // Step 1: Try to get retry messages first (priority to failed messages)
            DLQResponse retryResponse = claimOrDLQ(params);

            if (retryResponse.isSuccess() && retryResponse.getMessagesReclaimed() > 0) {
                log.debug("Found {} retry messages", retryResponse.getMessagesReclaimed());

                // Convert reclaimed messages to DLQMessage objects
                for (String messageId : retryResponse.getReclaimedMessageIds()) {
                    // Fetch the message details
                    List<StreamEntry> entries = jedis.xrange(
                        params.getStreamName(),
                        new StreamEntryID(messageId),
                        new StreamEntryID(messageId),
                        1
                    );

                    if (!entries.isEmpty()) {
                        StreamEntry entry = entries.get(0);

                        // Get delivery count from XPENDING
                        int deliveryCount = getDeliveryCount(
                            jedis,
                            params.getStreamName(),
                            params.getConsumerGroup(),
                            messageId
                        );

                        DLQMessage dlqMessage = DLQMessage.builder()
                            .id(messageId)
                            .fields(entry.getFields())
                            .deliveryCount(deliveryCount)
                            .retry(true)
                            .streamName(params.getStreamName())
                            .consumerGroup(params.getConsumerGroup())
                            .consumerName(params.getConsumerName())
                            .build();

                        result.add(dlqMessage);

                        log.debug("Added retry message: {}", dlqMessage.getSummary());
                    }
                }
            }

            // Step 2: If we don't have enough messages, read new ones
            int remaining = count - result.size();
            if (remaining > 0) {
                log.debug("Need {} more messages, reading new messages", remaining);

                Map<String, StreamEntryID> streams = new HashMap<>();
                streams.put(params.getStreamName(), StreamEntryID.XREADGROUP_UNDELIVERED_ENTRY);

                var entries = jedis.xreadGroup(
                    params.getConsumerGroup(),
                    params.getConsumerName(),
                    XReadGroupParams.xReadGroupParams()
                        .count(remaining)
                        .block(100), // Block for 100ms
                    streams
                );

                if (entries != null) {
                    for (var streamEntries : entries) {
                        for (StreamEntry entry : streamEntries.getValue()) {
                            String messageId = entry.getID().toString();

                            DLQMessage dlqMessage = DLQMessage.builder()
                                .id(messageId)
                                .fields(entry.getFields())
                                .deliveryCount(1) // First delivery
                                .retry(false)
                                .streamName(params.getStreamName())
                                .consumerGroup(params.getConsumerGroup())
                                .consumerName(params.getConsumerName())
                                .build();

                            result.add(dlqMessage);

                            log.debug("Added new message: {}", dlqMessage.getSummary());

                            // Broadcast consumption event
                            webSocketEventService.broadcastEvent(DLQEvent.builder()
                                .eventType(DLQEvent.EventType.INFO)
                                .messageId(messageId)
                                .payload(entry.getFields())
                                .consumer(params.getConsumerName())
                                .streamName(params.getStreamName())
                                .details("Message consumed (pending)")
                                .build());
                        }
                    }
                }
            }

            log.info("Returning {} messages ({} retries, {} new)",
                result.size(),
                (int) result.stream().filter(DLQMessage::isRetry).count(),
                (int) result.stream().filter(m -> !m.isRetry()).count()
            );

            return result;

        } catch (Exception e) {
            log.error("Failed to get next messages", e);
            throw new RuntimeException("Failed to get next messages", e);
        }
    }

    /**
     * Helper method to get the delivery count for a specific message.
     *
     * @param jedis Jedis connection
     * @param streamName Stream name
     * @param groupName Consumer group name
     * @param messageId Message ID
     * @return Delivery count, or 2 if not found (since it was just reclaimed)
     */
    private int getDeliveryCount(redis.clients.jedis.Jedis jedis, String streamName, String groupName, String messageId) {
        try {
            // Use XPENDING with range to get details for this specific message
            var pending = jedis.xpending(
                streamName,
                groupName,
                redis.clients.jedis.params.XPendingParams.xPendingParams(
                    new StreamEntryID(messageId),
                    new StreamEntryID(messageId),
                    1
                )
            );

            if (pending != null && !pending.isEmpty()) {
                return (int) pending.get(0).getDeliveredTimes();
            }
        } catch (Exception e) {
            log.warn("Failed to get delivery count for message {}", messageId, e);
        }
        return 2; // Default to 2 for retry messages if we can't determine
    }

    /**
     * Gets the count of pending messages in the stream.
     *
     * @param streamName Stream name
     * @param groupName Consumer group name
     * @return Number of pending messages
     */
    public long getPendingCount(String streamName, String groupName) {
        try (var jedis = jedisPool.getResource()) {
            var pendingInfo = jedis.xpending(streamName, groupName);
            return pendingInfo != null ? pendingInfo.getTotal() : 0;
        } catch (Exception e) {
            log.error("Failed to get pending count", e);
            return 0;
        }
    }

    /**
     * Gets the length of a stream.
     *
     * @param streamName Stream name
     * @return Number of messages in the stream
     */
    public long getStreamLength(String streamName) {
        try (var jedis = jedisPool.getResource()) {
            return jedis.xlen(streamName);
        } catch (Exception e) {
            log.error("Failed to get stream length", e);
            return 0;
        }
    }

    /**
     * Reads the most recent messages from a stream.
     *
     * This method reads messages in reverse chronological order (newest first).
     * It uses XREVRANGE to get the last N messages from the stream.
     *
     * @param streamName Stream name to read from
     * @param count Maximum number of messages to read
     * @return List of messages with their IDs and fields
     */
    public List<Map<String, Object>> readMessages(String streamName, int count) {
        log.debug("Reading {} messages from stream '{}'", count, streamName);

        List<Map<String, Object>> messages = new ArrayList<>();

        try (var jedis = jedisPool.getResource()) {
            // Use XREVRANGE to get the last N messages (newest first)
            // XREVRANGE key + - COUNT count
            List<StreamEntry> entries = jedis.xrevrange(streamName, (StreamEntryID) null, (StreamEntryID) null, count);

            if (entries != null && !entries.isEmpty()) {
                for (StreamEntry entry : entries) {
                    Map<String, Object> message = new HashMap<>();
                    message.put("id", entry.getID().toString());
                    message.put("fields", entry.getFields());
                    messages.add(message);
                }
            }

            log.debug("Read {} messages from stream '{}'", messages.size(), streamName);
            return messages;

        } catch (Exception e) {
            log.error("Failed to read messages from stream '{}'", streamName, e);
            throw new RuntimeException("Failed to read messages", e);
        }
    }

    /**
     * Processes the next available message with success or failure simulation.
     *
     * This method:
     * 1. Ensures consumer groups are initialized
     * 2. Gets the next message using getNextMessages()
     * 3. If shouldSucceed is true: acknowledges the message (success)
     * 4. If shouldSucceed is false: does NOT acknowledge (will retry)
     *
     * @param shouldSucceed Whether to simulate success (true) or failure (false)
     * @return Map containing success status and message details
     */
    public Map<String, Object> processNextMessage(boolean shouldSucceed) {
        log.info("Processing next message with shouldSucceed={}", shouldSucceed);

        Map<String, Object> response = new HashMap<>();

        try {
            // Get default configuration
            DLQConfigRequest config = dlqConfigService.getConfiguration("test-stream");

            // Build parameters
            DLQParameters params = DLQParameters.builder()
                .streamName(config.getStreamName())
                .dlqStreamName(config.getDlqStreamName())
                .consumerGroup(config.getConsumerGroup())
                .consumerName(config.getConsumerName())
                .minIdleMs(config.getMinIdleMs())
                .count(config.getCount())
                .maxDeliveries(config.getMaxDeliveries())
                .build();

            // Initialize consumer groups if they don't exist
            log.debug("Ensuring consumer groups are initialized");
            initializeConsumerGroup(params.getStreamName(), params.getConsumerGroup());
            initializeConsumerGroup(params.getDlqStreamName(), params.getConsumerGroup() + "-dlq");

            // Get next message (retries first, then new messages)
            List<DLQMessage> messages = getNextMessages(params, 1);

            if (messages.isEmpty()) {
                response.put("success", false);
                response.put("message", "No messages available to process");
                return response;
            }

            DLQMessage message = messages.get(0);
            log.info("Processing message: {}", message.getSummary());

            if (shouldSucceed) {
                // Broadcast MESSAGE_PROCESSED event for visual feedback (flash effect) BEFORE deleting
                webSocketEventService.broadcastEvent(DLQEvent.builder()
                    .eventType(DLQEvent.EventType.MESSAGE_PROCESSED)
                    .messageId(message.getId())
                    .payload(message.getFields())
                    .deliveryCount(message.getDeliveryCount())
                    .consumer(params.getConsumerName())
                    .streamName(params.getStreamName())
                    .details("Processing succeeded")
                    .build());

                // Small delay to allow flash animation to be visible before message disappears
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // Simulate successful processing - acknowledge the message
                boolean acked = acknowledgeMessage(
                    message.getStreamName(),
                    message.getConsumerGroup(),
                    message.getId()
                );

                if (acked) {
                    response.put("success", true);
                    response.put("message", String.format("✓ Message %s processed successfully (deliveryCount: %d)",
                        message.getId(), message.getDeliveryCount()));
                    response.put("messageId", message.getId());
                    response.put("deliveryCount", message.getDeliveryCount());
                    response.put("wasRetry", message.isRetry());
                } else {
                    response.put("success", false);
                    response.put("message", "Failed to acknowledge message");
                }
            } else {
                // Simulate failed processing - do NOT acknowledge (message will retry)
                response.put("success", true);
                response.put("message", String.format("✗ Message %s processing failed (will retry, deliveryCount: %d)",
                    message.getId(), message.getDeliveryCount()));
                response.put("messageId", message.getId());
                response.put("deliveryCount", message.getDeliveryCount());
                response.put("wasRetry", message.isRetry());

                // Broadcast MESSAGE_RECLAIMED event for visual feedback (flash effect)
                webSocketEventService.broadcastEvent(DLQEvent.builder()
                    .eventType(DLQEvent.EventType.MESSAGE_RECLAIMED)
                    .messageId(message.getId())
                    .payload(message.getFields())
                    .deliveryCount(message.getDeliveryCount())
                    .consumer(params.getConsumerName())
                    .streamName(params.getStreamName())
                    .details("Processing failed - will retry")
                    .build());

                log.info("Message {} not acknowledged - will be retried", message.getId());
            }

            return response;

        } catch (Exception e) {
            log.error("Error processing next message", e);
            response.put("success", false);
            response.put("message", "Error: " + e.getMessage());
            return response;
        }
    }

    /**
     * Cleans up streams and consumer groups for testing.
     *
     * WARNING: This deletes all data in the specified streams.
     *
     * @param streamName Main stream name
     * @param dlqStreamName DLQ stream name
     */
    public void cleanup(String streamName, String dlqStreamName) {
        log.info("Cleaning up streams: {}, {}", streamName, dlqStreamName);

        try (var jedis = jedisPool.getResource()) {
            jedis.del(streamName, dlqStreamName);

            webSocketEventService.broadcastEvent(DLQEvent.builder()
                .eventType(DLQEvent.EventType.INFO)
                .streamName(streamName)
                .details("Streams cleaned up")
                .build());

            log.info("Cleanup completed");
        } catch (Exception e) {
            log.error("Failed to cleanup streams", e);
        }
    }



    /**
     * Deletes a stream if it exists.
     *
     * @param streamName Name of the stream to delete
     * @return true if the stream was deleted, false if it didn't exist
     */
    public boolean deleteStream(String streamName) {
        log.info("Deleting stream: {}", streamName);

        try (var jedis = jedisPool.getResource()) {
            // Check if stream exists
            if (!jedis.exists(streamName)) {
                log.info("Stream '{}' does not exist", streamName);
                return false;
            }

            // Delete the stream
            jedis.del(streamName);
            log.info("Stream '{}' deleted successfully", streamName);

            // Broadcast event
            webSocketEventService.broadcastEvent(DLQEvent.builder()
                .eventType(DLQEvent.EventType.INFO)
                .streamName(streamName)
                .details("Stream deleted")
                .build());

            return true;

        } catch (Exception e) {
            log.error("Failed to delete stream '{}'", streamName, e);
            throw new RuntimeException("Failed to delete stream", e);
        }
    }
}

