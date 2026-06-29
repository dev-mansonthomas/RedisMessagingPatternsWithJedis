package com.redis.patterns.config;

import com.redis.patterns.service.KeyspaceNotificationListener;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Configuration for Redis keyspace notifications.
 *
 * This configuration:
 * 1. Enables keyspace notifications in Redis (notify-keyspace-events Ex)
 * 2. Starts a background thread that subscribes to key expiration events
 * 3. Listens for timeout key expirations and triggers timeout handling
 *
 * The blocking PSUBSCRIBE uses a DEDICATED Jedis connection (not borrowed from the
 * shared pool) wrapped in a reconnect loop, so a dropped connection is retried
 * instead of permanently stopping delivery, and the shared pool is never shrunk.
 * The short-lived CONFIG SET to enable notifications still uses the pool.
 *
 * @author Redis Patterns Team
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class KeyspaceNotificationConfig {

    private final JedisPool jedisPool;
    private final RedisProperties redisProperties;
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
     * Backoff between reconnect attempts when the subscription drops (milliseconds).
     */
    private static final long RECONNECT_BACKOFF_MILLIS = 1000;

    /**
     * Lifecycle flag; set to false on shutdown to stop the reconnect loop.
     */
    private volatile boolean running = true;

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

            // Start subscriber thread as a daemon virtual thread
            Thread subscriberThread = Thread.ofVirtual()
                .name("keyspace-notification-listener")
                .unstarted(() -> {
                    log.info("[KEYSPACE] Starting Redis keyspace notification subscriber");
                    log.info("[KEYSPACE] Subscribing to pattern: {}", KEYEVENT_EXPIRED_PATTERN);

                    while (running) {
                        Jedis jedis = null;
                        try {
                            jedis = createDedicatedConnection();
                            // This is a blocking call that will listen for key expiration events.
                            // We use psubscribe to subscribe to a pattern.
                            jedis.psubscribe(keyspaceNotificationListener, KEYEVENT_EXPIRED_PATTERN);
                        } catch (Exception e) {
                            if (running) {
                                log.warn("[KEYSPACE] Subscriber connection lost, reconnecting in {}ms: {}",
                                        RECONNECT_BACKOFF_MILLIS, e.getMessage());
                                sleepBackoff();
                            }
                        } finally {
                            if (jedis != null) {
                                jedis.close();
                            }
                        }
                    }
                    log.info("[KEYSPACE] Keyspace notification subscriber stopped");
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
        log.info("[KEYSPACE] Shutting down keyspace notification subscriber...");
        running = false;
        try {
            if (keyspaceNotificationListener.isSubscribed()) {
                keyspaceNotificationListener.punsubscribe();
            }
        } catch (Exception e) {
            log.debug("[KEYSPACE] Error while unsubscribing during shutdown: {}", e.getMessage());
        }
    }
}
