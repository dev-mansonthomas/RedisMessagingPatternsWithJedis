package com.redis.patterns.config;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.Jedis;

/**
 * Configuration for Redis Pub/Sub.
 *
 * This configuration starts a background thread that subscribes to Redis channels
 * and listens for published messages.
 *
 * The blocking SUBSCRIBE uses a DEDICATED Jedis connection (not borrowed from the
 * shared pool) wrapped in a reconnect loop, so a dropped connection is retried
 * instead of permanently stopping delivery, and the shared pool is never shrunk.
 *
 * @author Redis Patterns Team
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RedisPubSubConfig {

    private final RedisProperties redisProperties;
    private final RedisPubSubListener redisPubSubListener;

    /**
     * Default channel to subscribe to
     */
    private static final String DEFAULT_CHANNEL = "fire-and-forget";

    /**
     * Backoff between reconnect attempts when the subscription drops (milliseconds).
     */
    private static final long RECONNECT_BACKOFF_MILLIS = 1000;

    /**
     * Lifecycle flag; set to false on shutdown to stop the reconnect loop.
     */
    private volatile boolean running = true;

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

                while (running) {
                    Jedis jedis = null;
                    try {
                        jedis = createDedicatedConnection();
                        // This is a blocking call that will listen for messages
                        jedis.subscribe(redisPubSubListener, DEFAULT_CHANNEL);
                    } catch (Exception e) {
                        if (running) {
                            log.warn("Redis Pub/Sub subscriber connection lost, reconnecting in {}ms: {}",
                                    RECONNECT_BACKOFF_MILLIS, e.getMessage());
                            sleepBackoff();
                        }
                    } finally {
                        if (jedis != null) {
                            jedis.close();
                        }
                    }
                }
                log.info("Redis Pub/Sub subscriber stopped");
            });

            subscriberThread.setName("redis-pubsub-subscriber");
            subscriberThread.setDaemon(true);
            subscriberThread.start();

            log.info("Redis Pub/Sub subscriber thread started");
        };
    }

    /**
     * Builds a dedicated (non-pooled) Jedis connection from {@link RedisProperties}.
     * Authenticates only when a password is configured.
     */
    private Jedis createDedicatedConnection() {
        Jedis jedis = new Jedis(redisProperties.getHost(), redisProperties.getPort());
        String password = redisProperties.getPassword();
        if (password != null && !password.isEmpty()) {
            jedis.auth(password);
        }
        return jedis;
    }

    private void sleepBackoff() {
        try {
            Thread.sleep(RECONNECT_BACKOFF_MILLIS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    /**
     * Stops the reconnect loop and unsubscribes so the JVM can shut down cleanly.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Redis Pub/Sub subscriber...");
        running = false;
        try {
            if (redisPubSubListener.isSubscribed()) {
                redisPubSubListener.unsubscribe();
            }
        } catch (Exception e) {
            log.debug("Error while unsubscribing Pub/Sub listener during shutdown: {}", e.getMessage());
        }
    }
}
