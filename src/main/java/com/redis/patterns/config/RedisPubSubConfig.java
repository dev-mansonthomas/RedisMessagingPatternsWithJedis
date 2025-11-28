package com.redis.patterns.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPool;

/**
 * Configuration for Redis Pub/Sub.
 * 
 * This configuration starts a background thread that subscribes to Redis channels
 * and listens for published messages.
 * 
 * @author Redis Patterns Team
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RedisPubSubConfig {

    private final JedisPool jedisPool;
    private final RedisPubSubListener redisPubSubListener;

    /**
     * Default channel to subscribe to
     */
    private static final String DEFAULT_CHANNEL = "fire-and-forget";

    /**
     * Starts the Redis Pub/Sub subscriber in a background thread.
     * 
     * This subscriber will listen to the default channel and forward
     * messages to WebSocket clients.
     * 
     * @return CommandLineRunner that starts the subscriber
     */
    @Bean
    public CommandLineRunner startPubSubSubscriber() {
        return args -> {
            Thread subscriberThread = new Thread(() -> {
                log.info("Starting Redis Pub/Sub subscriber for channel '{}'", DEFAULT_CHANNEL);

                try (var jedis = jedisPool.getResource()) {
                    // This is a blocking call that will listen for messages
                    jedis.subscribe(redisPubSubListener, DEFAULT_CHANNEL);

                } catch (Exception e) {
                    log.error("Error in Redis Pub/Sub subscriber", e);
                }
            });

            subscriberThread.setName("redis-pubsub-subscriber");
            subscriberThread.setDaemon(true);
            subscriberThread.start();

            log.info("Redis Pub/Sub subscriber thread started");
        };
    }
}

