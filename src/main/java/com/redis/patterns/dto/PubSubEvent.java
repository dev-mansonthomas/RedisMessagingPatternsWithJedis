package com.redis.patterns.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Data Transfer Object for Pub/Sub events.
 * 
 * This class represents messages published to Redis Pub/Sub channels
 * and broadcasted to WebSocket subscribers.
 * 
 * @author Redis Patterns Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PubSubEvent {

    /**
     * Type of event
     */
    private EventType eventType;

    /**
     * Channel name where the message was published
     */
    private String channel;

    /**
     * Message payload (key-value pairs)
     */
    private Map<String, String> payload;

    /**
     * Timestamp when the message was published
     */
    @Builder.Default
    private String timestamp = Instant.now().toString();

    /**
     * Additional details or metadata
     */
    private String details;

    /**
     * Event types for Pub/Sub pattern
     */
    public enum EventType {
        /**
         * Message published to a channel
         */
        MESSAGE_PUBLISHED,

        /**
         * Message received by a subscriber
         */
        MESSAGE_RECEIVED,

        /**
         * Informational message
         */
        INFO,

        /**
         * Error occurred
         */
        ERROR
    }
}

