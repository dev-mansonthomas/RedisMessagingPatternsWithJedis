package com.redis.patterns;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main application class for Redis Messaging Patterns demonstration.
 * 
 * This application showcases various enterprise messaging patterns using Redis Streams,
 * starting with the Dead Letter Queue (DLQ) pattern.
 * 
 * Features:
 * - Real-time WebSocket communication for event streaming
 * - Interactive web UI for pattern demonstration
 * - Production-ready error handling and logging
 * - Scalable architecture for multiple messaging patterns
 * 
 * @author Redis Patterns Team
 * @version 1.0.0
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class RedisMessagingPatternsApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedisMessagingPatternsApplication.class, args);
    }
}

