package com.redis.patterns.config;

import com.redis.patterns.service.KeyspaceNotificationListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPool;

/**
 * Configuration for Redis keyspace notifications.
 * 
 * This configuration:
 * 1. Enables keyspace notifications in Redis (notify-keyspace-events Ex)
 * 2. Starts a background thread that subscribes to key expiration events
 * 3. Listens for timeout key expirations and triggers timeout handling
 * 
 * @author Redis Patterns Team
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class KeyspaceNotificationConfig {

    private final JedisPool jedisPool;
    private final KeyspaceNotificationListener keyspaceNotificationListener;

    /**
     * Pattern to subscribe to key expiration events.
     * __keyevent@0__:expired - listens to all key expiration events in database 0
     */
    private static final String KEYEVENT_EXPIRED_PATTERN = "__keyevent@0__:expired";

    /**
     * Alternative pattern using keyspace notifications.
     * __keyspace@0__:order.holdInventory.request.timeout.v1:* - listens to specific key pattern
     */
    private static final String KEYSPACE_TIMEOUT_PATTERN = "__keyspace@0__:order.holdInventory.request.timeout.v1:*";

    /**
     * Starts the Redis keyspace notification subscriber in a background thread.
     * 
     * This subscriber will listen to key expiration events and forward them
     * to the KeyspaceNotificationListener for processing.
     * 
     * @return CommandLineRunner that starts the subscriber
     */
    @Bean
    public CommandLineRunner startKeyspaceNotificationSubscriber() {
        return args -> {
            // Enable keyspace notifications in Redis
            enableKeyspaceNotifications();

            // Start subscriber thread as a daemon thread
            Thread subscriberThread = Thread.ofVirtual()
                .name("keyspace-notification-listener")
                .unstarted(() -> {
                    log.info("[KEYSPACE] Starting Redis keyspace notification subscriber");
                    log.info("[KEYSPACE] Subscribing to pattern: {}", KEYEVENT_EXPIRED_PATTERN);

                    try (var jedis = jedisPool.getResource()) {
                        // This is a blocking call that will listen for key expiration events
                        // We use psubscribe to subscribe to a pattern
                        jedis.psubscribe(keyspaceNotificationListener, KEYEVENT_EXPIRED_PATTERN);

                    } catch (Exception e) {
                        log.error("[KEYSPACE] Error in keyspace notification subscriber", e);
                    }
                });

            // Set as daemon before starting
            subscriberThread.setDaemon(true);
            subscriberThread.start();

            log.info("[KEYSPACE] Keyspace notification subscriber thread started");
        };
    }

    /**
     * Enable keyspace notifications in Redis.
     * 
     * Sets notify-keyspace-events to "Ex" which enables:
     * - E: Keyevent events (published with __keyevent@<db>__ prefix)
     * - x: Expired events (generated when a key expires)
     */
    private void enableKeyspaceNotifications() {
        try (var jedis = jedisPool.getResource()) {
            String currentConfig = jedis.configGet("notify-keyspace-events").get("notify-keyspace-events");
            log.info("[KEYSPACE] Current notify-keyspace-events: '{}'", currentConfig);

            // Set to "Ex" to enable keyevent expired notifications
            jedis.configSet("notify-keyspace-events", "Ex");
            
            String newConfig = jedis.configGet("notify-keyspace-events").get("notify-keyspace-events");
            log.info("[KEYSPACE] Updated notify-keyspace-events to: '{}'", newConfig);
            log.info("[KEYSPACE] ✅ Keyspace notifications enabled successfully");

        } catch (Exception e) {
            log.error("[KEYSPACE] Failed to enable keyspace notifications", e);
            throw new RuntimeException("Failed to enable keyspace notifications", e);
        }
    }
}

