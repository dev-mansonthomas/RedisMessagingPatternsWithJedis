package com.redis.patterns.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Unified message wrapper that abstracts Redis Stream messages.
 * 
 * This class provides a developer-friendly interface for working with messages,
 * hiding the complexity of Redis Streams (XREADGROUP vs claim_or_dlq).
 * 
 * Key features:
 * - Contains message data and metadata (ID, fields, delivery count)
 * - Provides context about whether this is a retry attempt
 * - Offers helper methods for acknowledgment
 * - Simplifies error handling and retry logic
 * 
 * Usage example:
 * <pre>
 * List&lt;DLQMessage&gt; messages = dlqService.getNextMessages(params, 10);
 * 
 * for (DLQMessage msg : messages) {
 *     try {
 *         processMessage(msg);
 *         msg.acknowledge(dlqService);
 *     } catch (Exception e) {
 *         // Message will be automatically retried
 *         log.error("Failed to process message", e);
 *     }
 * }
 * </pre>
 * 
 * @author Redis Patterns Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DLQMessage {

    /**
     * Unique message ID from Redis Stream (e.g., "1234567890-0")
     */
    private String id;

    /**
     * Message payload as key-value pairs
     */
    private Map<String, String> fields;

    /**
     * Number of times this message has been delivered
     * - 1 = first delivery (new message)
     * - 2+ = retry attempts
     */
    private int deliveryCount;

    /**
     * Indicates whether this is a retry attempt
     * - false = new message from XREADGROUP
     * - true = reclaimed message from claim_or_dlq
     */
    private boolean retry;

    /**
     * Stream name where this message originated
     */
    private String streamName;

    /**
     * Consumer group name
     */
    private String consumerGroup;

    /**
     * Consumer name that claimed this message
     */
    private String consumerName;

    /**
     * Gets a field value from the message payload.
     * 
     * @param key Field name
     * @return Field value, or null if not present
     */
    public String getField(String key) {
        return fields != null ? fields.get(key) : null;
    }

    /**
     * Checks if this is the first delivery attempt.
     * 
     * @return true if deliveryCount == 1
     */
    public boolean isFirstAttempt() {
        return deliveryCount == 1;
    }

    /**
     * Checks if this message has been retried at least once.
     * 
     * @return true if deliveryCount > 1
     */
    public boolean hasBeenRetried() {
        return deliveryCount > 1;
    }

    /**
     * Gets a human-readable description of the message state.
     * 
     * @return Description string
     */
    public String getStateDescription() {
        if (isFirstAttempt()) {
            return "New message (first attempt)";
        } else {
            return String.format("Retry attempt #%d", deliveryCount - 1);
        }
    }

    /**
     * Returns a summary of the message for logging.
     * 
     * @return Summary string
     */
    public String getSummary() {
        return String.format("Message[id=%s, deliveryCount=%d, retry=%s, fields=%s]",
            id, deliveryCount, retry, fields);
    }
}

