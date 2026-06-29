package com.redis.patterns.config;

import com.redis.patterns.websocket.DLQEventWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.Arrays;

/**
 * WebSocket configuration for real-time event streaming.
 * 
 * This configuration:
 * - Enables WebSocket support in the application
 * - Registers the DLQ event handler at /ws/dlq-events
 * - Configures CORS to allow connections from the Angular frontend
 * - Sets up message size limits and timeouts
 * 
 * The WebSocket endpoint provides bidirectional communication for:
 * - Real-time DLQ event notifications
 * - Progress updates during high-volume tests
 * - Error notifications
 * - System status updates
 * 
 * @author Redis Patterns Team
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    @NonNull
    private final DLQEventWebSocketHandler dlqEventWebSocketHandler;

    /**
     * Allowed origins for the WebSocket handshake — same explicit allow-list as the
     * HTTP CORS config (defaults to the local frontend/backend, overridable via
     * app.cors.allowed-origins / APP_CORS_ALLOWED_ORIGINS). Kept consistent with
     * {@code CorsConfig} so the socket isn't open to arbitrary cross-origin pages.
     */
    @Value("${app.cors.allowed-origins:http://localhost:4200,http://localhost:8080}")
    private String allowedOrigins;

    /**
     * Registers WebSocket handlers and configures endpoints.
     *
     * The endpoint /ws/dlq-events is configured with:
     * - An explicit origin allow-list (no wildcard), matching the HTTP CORS policy
     * - SockJS fallback for browsers that don't support WebSocket
     *
     * @param registry WebSocket handler registry
     */
    @Override
    public void registerWebSocketHandlers(@NonNull WebSocketHandlerRegistry registry) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(o -> !o.isEmpty())
                .toArray(String[]::new);
        registry.addHandler(dlqEventWebSocketHandler, "/ws/dlq-events")
                .setAllowedOrigins(origins) // explicit allow-list, consistent with CORS
                .withSockJS(); // Enable SockJS fallback
    }
}

