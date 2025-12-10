package com.redis.patterns.service;

import com.redis.patterns.dto.PubSubEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class PubSubTopicRoutingService {

    private final JedisPool jedisPool;
    private final WebSocketEventService webSocketEventService;
    private final Map<String, PatternSubscriber> activeSubscriptions = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public static final String EU_COMPLIANCE_PATTERN = "order.eu.*";
    public static final String ORDER_AUDIT_PATTERN = "order.*.created";
    public static final String US_ORDERS_PATTERN = "order.us.*";

    public PubSubTopicRoutingService(JedisPool jedisPool, WebSocketEventService webSocketEventService) {
        this.jedisPool = jedisPool;
        this.webSocketEventService = webSocketEventService;
    }

    @PostConstruct
    public void initialize() {
        log.info("Initializing PubSubTopicRoutingService with pattern subscriptions...");
        subscribeToPattern("EU Compliance", EU_COMPLIANCE_PATTERN);
        subscribeToPattern("Order Audit", ORDER_AUDIT_PATTERN);
        subscribeToPattern("US Orders", US_ORDERS_PATTERN);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down PubSubTopicRoutingService...");
        activeSubscriptions.values().forEach(PatternSubscriber::stop);
        executorService.shutdownNow();
    }

    public long publishMessage(String routingKey, Map<String, String> payload) {
        log.info("Publishing message to routing key '{}': {}", routingKey, payload);
        try (var jedis = jedisPool.getResource()) {
            String message = serializePayload(payload);
            long subscriberCount = jedis.publish(routingKey, message);
            log.info("Message published to {} subscribers on '{}'", subscriberCount, routingKey);
            webSocketEventService.broadcastEvent(PubSubEvent.builder()
                .eventType(PubSubEvent.EventType.MESSAGE_PUBLISHED)
                .channel(routingKey)
                .payload(payload)
                .details(String.format("Published to %d pattern subscribers", subscriberCount))
                .build());
            return subscriberCount;
        } catch (Exception e) {
            log.error("Failed to publish message to '{}'", routingKey, e);
            throw new RuntimeException("Failed to publish message", e);
        }
    }

    public void subscribeToPattern(String subscriberName, String pattern) {
        if (activeSubscriptions.containsKey(subscriberName)) {
            log.warn("Subscriber '{}' already active", subscriberName);
            return;
        }
        PatternSubscriber subscriber = new PatternSubscriber(subscriberName, pattern);
        activeSubscriptions.put(subscriberName, subscriber);
        executorService.submit(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                log.info("Starting pattern subscription '{}' -> '{}'", subscriberName, pattern);
                jedis.psubscribe(subscriber, pattern);
            } catch (Exception e) {
                if (!subscriber.isStopped()) {
                    log.error("Pattern subscription error for '{}'", subscriberName, e);
                }
            } finally {
                activeSubscriptions.remove(subscriberName);
            }
        });
    }

    public Map<String, String> getActiveSubscriptions() {
        Map<String, String> result = new ConcurrentHashMap<>();
        activeSubscriptions.forEach((name, sub) -> result.put(name, sub.getPattern()));
        return result;
    }

    private String serializePayload(Map<String, String> payload) {
        if (payload == null || payload.isEmpty()) return "";
        return payload.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .reduce((a, b) -> a + "," + b)
            .orElse("");
    }

    private Map<String, String> deserializePayload(String message) {
        Map<String, String> payload = new ConcurrentHashMap<>();
        if (message == null || message.isEmpty()) return payload;
        for (String pair : message.split(",")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) payload.put(kv[0], kv[1]);
        }
        return payload;
    }

    private class PatternSubscriber extends JedisPubSub {
        private final String subscriberName;
        private final String pattern;
        private volatile boolean stopped = false;

        public PatternSubscriber(String subscriberName, String pattern) {
            this.subscriberName = subscriberName;
            this.pattern = pattern;
        }

        public String getPattern() { return pattern; }
        public boolean isStopped() { return stopped; }

        public void stop() {
            this.stopped = true;
            this.punsubscribe();
        }

        @Override
        public void onPMessage(String pattern, String channel, String message) {
            log.info("[{}] Received on '{}' (pattern: '{}')", subscriberName, channel, pattern);
            Map<String, String> payload = deserializePayload(message);
            payload.put("_subscriber", subscriberName);
            payload.put("_pattern", pattern);
            payload.put("_channel", channel);
            webSocketEventService.broadcastEvent(PubSubEvent.builder()
                .eventType(PubSubEvent.EventType.MESSAGE_RECEIVED)
                .channel(channel)
                .payload(payload)
                .details(String.format("Received by '%s' (pattern: %s)", subscriberName, pattern))
                .build());
        }

        @Override
        public void onPSubscribe(String pattern, int subscribedChannels) {
            log.info("[{}] Subscribed to pattern '{}' (total: {})", subscriberName, pattern, subscribedChannels);
        }

        @Override
        public void onPUnsubscribe(String pattern, int subscribedChannels) {
            log.info("[{}] Unsubscribed from '{}' (remaining: {})", subscriberName, pattern, subscribedChannels);
        }
    }
}

