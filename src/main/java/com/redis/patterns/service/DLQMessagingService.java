package com.redis.patterns.service;

import com.redis.patterns.config.DLQProperties;
import com.redis.patterns.dto.DLQEvent;
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
import java.util.concurrent.atomic.AtomicBoolean;

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

    // Flag to control high-volume test execution
    private final AtomicBoolean highVolumeTestRunning = new AtomicBoolean(false);

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
                .eventType(DLQEvent.EventType.INFO)
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

            // Parse the result
            List<String> reclaimedIds = new ArrayList<>();
            int messagesReclaimed = 0;

            if (result instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> resultList = (List<Object>) result;

                for (Object entry : resultList) {
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

                            // Broadcast reclaim event
                            webSocketEventService.broadcastEvent(DLQEvent.builder()
                                .eventType(DLQEvent.EventType.MESSAGE_RECLAIMED)
                                .messageId(messageId)
                                .payload(payload)
                                .consumer(params.getConsumerName())
                                .streamName(params.getStreamName())
                                .details("Message reclaimed for processing")
                                .build());
                        }
                    }
                }
            }

            log.info("claim_or_dlq completed: {} messages reclaimed", messagesReclaimed);

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
                // Broadcast acknowledgment event
                webSocketEventService.broadcastEvent(DLQEvent.builder()
                    .eventType(DLQEvent.EventType.MESSAGE_PROCESSED)
                    .messageId(messageId)
                    .streamName(streamName)
                    .details("Message acknowledged")
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
     * Checks if a high-volume test is currently running.
     *
     * @return true if a test is running
     */
    public boolean isHighVolumeTestRunning() {
        return highVolumeTestRunning.get();
    }

    /**
     * Stops the currently running high-volume test.
     */
    public void stopHighVolumeTest() {
        if (highVolumeTestRunning.compareAndSet(true, false)) {
            log.info("High-volume test stop requested");

            webSocketEventService.broadcastEvent(DLQEvent.builder()
                .eventType(DLQEvent.EventType.INFO)
                .details("High-volume test stopped by user")
                .build());
        }
    }
}

