package com.redis.patterns.service;

import com.redis.patterns.dto.PubSubEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPool;

import java.util.Map;

/**
 * Service implementing the Publish/Subscribe (Pub/Sub) messaging pattern.
 * 
 * This service provides Fire & Forget messaging using Redis Pub/Sub:
 * - Messages are ephemeral (not persisted)
 * - Instant delivery to all active subscribers
 * - No delivery guarantees (fire and forget)
 * - Lightweight and fast
 * 
 * Key differences from DLQ pattern:
 * - No message persistence
 * - No retry mechanism
 * - No delivery tracking
 * - Multiple subscribers receive the same message simultaneously
 * 
 * @author Redis Patterns Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PubSubService {

    private final JedisPool jedisPool;
    private final WebSocketEventService webSocketEventService;

    /**
     * Publishes a message to a Redis Pub/Sub channel.
     * 
     * This is a "fire and forget" operation:
     * - Message is sent to all active subscribers
     * - No acknowledgment or delivery confirmation
     * - If no subscribers are listening, message is lost
     * 
     * @param channel Channel name to publish to
     * @param payload Message payload (key-value pairs)
     * @return Number of subscribers that received the message
     */
    public long publishMessage(String channel, Map<String, String> payload) {
        log.info("Publishing message to channel '{}': {}", channel, payload);

        try (var jedis = jedisPool.getResource()) {
            // Convert payload to a simple string format for Redis Pub/Sub
            String message = serializePayload(payload);

            // Publish to Redis channel
            long subscriberCount = jedis.publish(channel, message);

            log.info("Message published to {} subscribers on channel '{}'", subscriberCount, channel);

            // Broadcast event via WebSocket for UI updates
            webSocketEventService.broadcastEvent(PubSubEvent.builder()
                .eventType(PubSubEvent.EventType.MESSAGE_PUBLISHED)
                .channel(channel)
                .payload(payload)
                .details(String.format("Published to %d subscribers", subscriberCount))
                .build());

            return subscriberCount;

        } catch (Exception e) {
            log.error("Failed to publish message to channel '{}'", channel, e);

            webSocketEventService.broadcastEvent(PubSubEvent.builder()
                .eventType(PubSubEvent.EventType.ERROR)
                .channel(channel)
                .details("Failed to publish message: " + e.getMessage())
                .build());

            throw new RuntimeException("Failed to publish message", e);
        }
    }

    /**
     * Serializes a payload map to a string format.
     * Format: key1=value1,key2=value2,...
     * 
     * @param payload Payload to serialize
     * @return Serialized string
     */
    private String serializePayload(Map<String, String> payload) {
        if (payload == null || payload.isEmpty()) {
            return "";
        }

        return payload.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .reduce((a, b) -> a + "," + b)
            .orElse("");
    }

    /**
     * Deserializes a string to a payload map.
     * Format: key1=value1,key2=value2,...
     * 
     * @param message Serialized message
     * @return Payload map
     */
    public Map<String, String> deserializePayload(String message) {
        if (message == null || message.isEmpty()) {
            return Map.of();
        }

        Map<String, String> payload = new java.util.HashMap<>();
        String[] pairs = message.split(",");

        for (String pair : pairs) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2) {
                payload.put(keyValue[0], keyValue[1]);
            }
        }

        return payload;
    }
}

