package com.redis.patterns.config;

import com.redis.patterns.dto.PubSubEvent;
import com.redis.patterns.service.PubSubService;
import com.redis.patterns.service.WebSocketEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPubSub;

import java.util.Map;

/**
 * Redis Pub/Sub listener that receives messages and broadcasts them via WebSocket.
 * 
 * This listener subscribes to Redis channels and forwards received messages
 * to WebSocket clients for real-time UI updates.
 * 
 * @author Redis Patterns Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisPubSubListener extends JedisPubSub {

    private final WebSocketEventService webSocketEventService;
    private final PubSubService pubSubService;

    /**
     * Called when a message is received on a subscribed channel.
     * 
     * @param channel Channel name
     * @param message Message content
     */
    @Override
    public void onMessage(String channel, String message) {
        log.info("Received message on channel '{}': {}", channel, message);

        try {
            // Deserialize the message payload
            Map<String, String> payload = pubSubService.deserializePayload(message);

            // Broadcast to WebSocket clients
            webSocketEventService.broadcastEvent(PubSubEvent.builder()
                .eventType(PubSubEvent.EventType.MESSAGE_RECEIVED)
                .channel(channel)
                .payload(payload)
                .details("Message received by subscriber")
                .build());

            log.debug("Message broadcasted to WebSocket clients");

        } catch (Exception e) {
            log.error("Error processing received message", e);

            webSocketEventService.broadcastEvent(PubSubEvent.builder()
                .eventType(PubSubEvent.EventType.ERROR)
                .channel(channel)
                .details("Error processing message: " + e.getMessage())
                .build());
        }
    }

    /**
     * Called when successfully subscribed to a channel.
     * 
     * @param channel Channel name
     * @param subscribedChannels Number of channels subscribed
     */
    @Override
    public void onSubscribe(String channel, int subscribedChannels) {
        log.info("Subscribed to channel '{}' (total: {})", channel, subscribedChannels);

        webSocketEventService.broadcastEvent(PubSubEvent.builder()
            .eventType(PubSubEvent.EventType.INFO)
            .channel(channel)
            .details(String.format("Subscribed to channel (total: %d)", subscribedChannels))
            .build());
    }

    /**
     * Called when unsubscribed from a channel.
     * 
     * @param channel Channel name
     * @param subscribedChannels Number of channels still subscribed
     */
    @Override
    public void onUnsubscribe(String channel, int subscribedChannels) {
        log.info("Unsubscribed from channel '{}' (remaining: {})", channel, subscribedChannels);

        webSocketEventService.broadcastEvent(PubSubEvent.builder()
            .eventType(PubSubEvent.EventType.INFO)
            .channel(channel)
            .details(String.format("Unsubscribed from channel (remaining: %d)", subscribedChannels))
            .build());
    }
}

