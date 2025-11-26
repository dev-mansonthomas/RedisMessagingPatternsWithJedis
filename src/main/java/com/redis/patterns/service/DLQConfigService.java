package com.redis.patterns.service;

import com.redis.patterns.dto.DLQConfigRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Service for managing DLQ configuration at runtime.
 * Stores configuration in memory and allows dynamic updates without restarting the application.
 */
@Slf4j
@Service
public class DLQConfigService {

    // Store configurations per stream
    private final Map<String, DLQConfigRequest> configurations = new ConcurrentHashMap<>();

    // Default configuration
    private static final DLQConfigRequest DEFAULT_CONFIG = DLQConfigRequest.builder()
        .streamName("test-stream")
        .dlqStreamName("test-stream:dlq")
        .consumerGroup("test-group")
        .consumerName("consumer-1")
        .minIdleMs(100L)  // 100ms for demo purposes (fast retries)
        .count(100)
        .maxDeliveries(2)
        .build();

    /**
     * Save or update configuration for a stream
     */
    public void saveConfiguration(DLQConfigRequest config) {
        log.info("Saving DLQ configuration for stream '{}': maxDeliveries={}", 
            config.getStreamName(), config.getMaxDeliveries());
        configurations.put(config.getStreamName(), config);
    }

    /**
     * Get configuration for a specific stream
     */
    public DLQConfigRequest getConfiguration(String streamName) {
        return configurations.getOrDefault(streamName, DEFAULT_CONFIG);
    }

    /**
     * Get all configurations
     */
    public Map<String, DLQConfigRequest> getAllConfigurations() {
        return new ConcurrentHashMap<>(configurations);
    }

    /**
     * Delete configuration for a stream (revert to default)
     */
    public void deleteConfiguration(String streamName) {
        log.info("Deleting DLQ configuration for stream '{}'", streamName);
        configurations.remove(streamName);
    }

    /**
     * Get default configuration
     */
    public DLQConfigRequest getDefaultConfiguration() {
        return DEFAULT_CONFIG;
    }
}

