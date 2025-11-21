package com.redis.patterns.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object for DLQ operation parameters.
 * 
 * This class encapsulates all configurable parameters for the claim_or_dlq function,
 * allowing users to customize the behavior through the web interface.
 * 
 * All parameters are validated to ensure they meet minimum requirements.
 * 
 * @author Redis Patterns Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DLQParameters {

    /**
     * Source stream name where messages are consumed from
     */
    @NotBlank(message = "Stream name cannot be blank")
    @Builder.Default
    private String streamName = "mystream";

    /**
     * Dead Letter Queue stream name where failed messages are routed
     */
    @NotBlank(message = "DLQ stream name cannot be blank")
    @Builder.Default
    private String dlqStreamName = "mystream:dlq";

    /**
     * Consumer group name for coordinated message consumption
     */
    @NotBlank(message = "Consumer group cannot be blank")
    @Builder.Default
    private String consumerGroup = "mygroup";

    /**
     * Consumer name identifying this specific consumer instance
     */
    @NotBlank(message = "Consumer name cannot be blank")
    @Builder.Default
    private String consumerName = "worker";

    /**
     * Minimum idle time (in milliseconds) before a message can be reclaimed
     * Messages idle for less than this duration won't be processed
     */
    @Min(value = 0, message = "Min idle time must be non-negative")
    @Builder.Default
    private long minIdleMs = 5000L;

    /**
     * Maximum number of pending messages to process in one call
     */
    @Min(value = 1, message = "Count must be at least 1")
    @Builder.Default
    private int count = 100;

    /**
     * Maximum delivery attempts before routing to DLQ
     * Messages with delivery count >= this value will be sent to DLQ
     */
    @Min(value = 1, message = "Max deliveries must be at least 1")
    @Builder.Default
    private int maxDeliveries = 2;
}

