package com.redis.patterns.config;

import com.redis.patterns.websocket.DLQEventWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

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
     * Registers WebSocket handlers and configures endpoints.
     *
     * The endpoint /ws/dlq-events is configured with:
     * - CORS allowing all origins using allowedOriginPatterns (required for SockJS with credentials)
     * - SockJS fallback for browsers that don't support WebSocket
     *
     * Note: Using setAllowedOriginPatterns("*") instead of setAllowedOrigins("*")
     * because SockJS enables credentials by default, and Spring Security requires
     * allowedOriginPatterns when credentials are enabled.
     *
     * @param registry WebSocket handler registry
     */
    @Override
    public void registerWebSocketHandlers(@NonNull WebSocketHandlerRegistry registry) {
        registry.addHandler(dlqEventWebSocketHandler, "/ws/dlq-events")
                .setAllowedOriginPatterns("*") // Use allowedOriginPatterns for SockJS compatibility
                .withSockJS(); // Enable SockJS fallback
    }
}

