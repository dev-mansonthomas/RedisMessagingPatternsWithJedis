package com.redis.patterns.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request object for updating DLQ configuration.
 * Allows dynamic configuration of DLQ parameters including maxDeliveries (maxRetry).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DLQConfigRequest {

    @NotBlank(message = "Stream name cannot be blank")
    private String streamName;

    @NotBlank(message = "DLQ stream name cannot be blank")
    private String dlqStreamName;

    @NotBlank(message = "Consumer group cannot be blank")
    private String consumerGroup;

    @NotBlank(message = "Consumer name cannot be blank")
    private String consumerName;

    @Min(value = 0, message = "Min idle time must be non-negative")
    private long minIdleMs;

    @Min(value = 1, message = "Count must be at least 1")
    private int count;

    @Min(value = 1, message = "Max deliveries must be at least 1")
    private int maxDeliveries;
}

