package com.redis.patterns.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for DLQ pattern.
 * 
 * These properties provide default values for DLQ operations
 * and can be overridden via application.yml or environment variables.
 * 
 * @author Redis Patterns Team
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "dlq")
public class DLQProperties {

    /**
     * Default stream name for message processing
     */
    private String streamName = "mystream";

    /**
     * Default DLQ stream name for failed messages
     */
    private String dlqStreamName = "mystream:dlq";

    /**
     * Default consumer group name
     */
    private String consumerGroup = "mygroup";

    /**
     * Default consumer name
     */
    private String defaultConsumerName = "worker";

    /**
     * Default minimum idle time in milliseconds
     */
    private long defaultMinIdleMs = 5000L;

    /**
     * Default count of messages to process
     */
    private int defaultCount = 100;

    /**
     * Default maximum deliveries before DLQ
     */
    private int defaultMaxDeliveries = 2;

    /**
     * Batch size for high-volume tests
     */
    private int highVolumeBatchSize = 100;

    /**
     * Delay between batches in high-volume tests (milliseconds)
     */
    private long highVolumeDelayMs = 10L;
}

