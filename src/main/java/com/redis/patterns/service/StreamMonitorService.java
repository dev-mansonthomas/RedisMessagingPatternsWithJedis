package com.redis.patterns.service;

import com.redis.patterns.config.DLQProperties;
import com.redis.patterns.dto.DLQEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.resps.StreamEntry;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XReadGroupParams;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Background service that monitors Redis Streams and broadcasts new messages via WebSocket.
 *
 * DISABLED: This service is no longer needed because:
 * - Visualization uses XREVRANGE (read-only, no consumer group)
 * - WebSocket events from user actions (Process & Success/Fail) provide real-time updates
 * - Using a consumer group for visualization creates unnecessary PENDING entries
 *
 * To re-enable: Remove @Profile("disabled") annotation
 *
 * @deprecated Use XREVRANGE for visualization and WebSocket events from actions
 */
@Slf4j
@Service
@org.springframework.context.annotation.Profile("disabled")
@RequiredArgsConstructor
@Deprecated
public class StreamMonitorService {

    private final JedisPool jedisPool;
    private final WebSocketEventService webSocketEventService;
    private final DLQProperties dlqProperties;

    // Track the last message ID read from each stream
    private final Map<String, StreamEntryID> lastReadIds = new ConcurrentHashMap<>();

    // Track known message IDs for each stream to detect deletions
    private final Map<String, Set<String>> knownMessageIds = new ConcurrentHashMap<>();

    // Streams to monitor
    private static final String MAIN_STREAM = "test-stream";
    private static final String DLQ_STREAM = "test-stream:dlq";
    private static final String MONITOR_GROUP = "monitor-group";
    private static final String MONITOR_CONSUMER = "monitor-consumer";

    /**
     * Initialize consumer groups for monitoring on startup
     */
    @PostConstruct
    public void initialize() {
        log.info("Initializing StreamMonitorService...");
        
        try (var jedis = jedisPool.getResource()) {
            // Initialize consumer group for main stream
            initializeGroup(jedis, MAIN_STREAM);
            
            // Initialize consumer group for DLQ stream
            initializeGroup(jedis, DLQ_STREAM);
            
            log.info("StreamMonitorService initialized successfully");
        } catch (Exception e) {
            log.error("Failed to initialize StreamMonitorService", e);
        }
    }

    private void initializeGroup(redis.clients.jedis.Jedis jedis, String streamName) {
        try {
            // Try to create the group starting from the end ($)
            // This means we'll only see NEW messages added after startup
            jedis.xgroupCreate(streamName, MONITOR_GROUP, new StreamEntryID(), true);
            log.info("Created consumer group '{}' for stream '{}'", MONITOR_GROUP, streamName);
        } catch (Exception e) {
            // Group might already exist, which is fine
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debug("Consumer group '{}' already exists for stream '{}'", MONITOR_GROUP, streamName);
            } else {
                log.warn("Could not create consumer group for stream '{}': {}", streamName, e.getMessage());
            }
        }
    }

    /**
     * Poll streams for new messages every 500ms
     */
    @Scheduled(fixedDelay = 500)
    public void pollStreams() {
        try {
            // Monitor main stream
            pollStream(MAIN_STREAM);

            // Monitor DLQ stream
            pollStream(DLQ_STREAM);

        } catch (Exception e) {
            log.error("Error polling streams", e);
        }
    }

    /**
     * Check for deleted messages every 2 seconds
     */
    @Scheduled(fixedDelay = 2000)
    public void checkForDeletedMessages() {
        try {
            checkStreamForDeletions(MAIN_STREAM);
            checkStreamForDeletions(DLQ_STREAM);
        } catch (Exception e) {
            log.error("Error checking for deleted messages", e);
        }
    }

    private void pollStream(String streamName) {
        try (var jedis = jedisPool.getResource()) {
            // Read new messages using XREADGROUP with '>' (undelivered messages)
            Map<String, StreamEntryID> streams = new HashMap<>();
            streams.put(streamName, StreamEntryID.XREADGROUP_UNDELIVERED_ENTRY);

            var entries = jedis.xreadGroup(
                MONITOR_GROUP,
                MONITOR_CONSUMER,
                XReadGroupParams.xReadGroupParams()
                    .count(10)  // Read up to 10 messages at a time
                    .block(100), // Block for 100ms if no messages
                streams
            );

            if (entries != null && !entries.isEmpty()) {
                for (var streamEntries : entries) {
                    String stream = streamEntries.getKey();
                    List<StreamEntry> messages = streamEntries.getValue();

                    for (StreamEntry entry : messages) {
                        // Broadcast the new message via WebSocket
                        broadcastMessage(stream, entry);

                        // Acknowledge the message so it doesn't get re-delivered
                        jedis.xack(stream, MONITOR_GROUP, entry.getID());
                    }

                    log.debug("Processed {} new messages from stream '{}'", messages.size(), stream);
                }
            }
        } catch (Exception e) {
            // Log at debug level to avoid spam if stream doesn't exist yet
            log.debug("Error polling stream '{}': {}", streamName, e.getMessage());
        }
    }

    private void broadcastMessage(String streamName, StreamEntry entry) {
        try {
            String messageId = entry.getID().toString();

            // Track this message ID
            knownMessageIds.computeIfAbsent(streamName, k -> ConcurrentHashMap.newKeySet()).add(messageId);

            // Create event with message details
            DLQEvent event = DLQEvent.builder()
                .eventType(DLQEvent.EventType.MESSAGE_PRODUCED)
                .streamName(streamName)
                .messageId(messageId)
                .payload(entry.getFields())
                .timestamp(LocalDateTime.now())
                .details("New message in stream")
                .build();

            // Broadcast to all connected WebSocket clients
            webSocketEventService.broadcastEvent(event);

            log.debug("Broadcasted message {} from stream '{}'", entry.getID(), streamName);
        } catch (Exception e) {
            log.error("Failed to broadcast message from stream '{}'", streamName, e);
        }
    }

    /**
     * Check if any messages have been deleted from the stream
     */
    private void checkStreamForDeletions(String streamName) {
        try (var jedis = jedisPool.getResource()) {
            // Get all current message IDs in the stream using XRANGE
            List<StreamEntry> allEntries = jedis.xrange(streamName, (StreamEntryID) null, (StreamEntryID) null);

            if (allEntries == null) {
                return;
            }

            // Build set of current message IDs
            Set<String> currentIds = new HashSet<>();
            for (StreamEntry entry : allEntries) {
                currentIds.add(entry.getID().toString());
            }

            // Get known message IDs for this stream
            Set<String> knownIds = knownMessageIds.get(streamName);
            if (knownIds == null || knownIds.isEmpty()) {
                // First time checking this stream, just store current IDs
                knownMessageIds.put(streamName, ConcurrentHashMap.newKeySet());
                knownMessageIds.get(streamName).addAll(currentIds);
                return;
            }

            // Find deleted messages (known but not in current)
            Set<String> deletedIds = new HashSet<>(knownIds);
            deletedIds.removeAll(currentIds);

            // Broadcast deletion events
            for (String deletedId : deletedIds) {
                broadcastDeletion(streamName, deletedId);
                knownIds.remove(deletedId);
            }

            // Add any new IDs we didn't know about (in case we missed them)
            knownIds.addAll(currentIds);

        } catch (Exception e) {
            log.debug("Error checking stream '{}' for deletions: {}", streamName, e.getMessage());
        }
    }

    private void broadcastDeletion(String streamName, String messageId) {
        try {
            DLQEvent event = DLQEvent.builder()
                .eventType(DLQEvent.EventType.MESSAGE_DELETED)
                .streamName(streamName)
                .messageId(messageId)
                .timestamp(LocalDateTime.now())
                .details("Message deleted from stream")
                .build();

            webSocketEventService.broadcastEvent(event);
            log.debug("Broadcasted deletion of message {} from stream '{}'", messageId, streamName);
        } catch (Exception e) {
            log.error("Failed to broadcast deletion from stream '{}'", streamName, e);
        }
    }
}

