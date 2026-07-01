package com.redis.patterns.service;

import com.redis.patterns.dto.LlmChatEvent;
import com.redis.patterns.support.AbstractRedisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import redis.clients.jedis.params.XAddParams;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Integration tests for the per-cid token listener and multi-conversation isolation. */
class LlmTokenListenerServiceTest extends AbstractRedisIntegrationTest {

    private WebSocketEventService ws;
    private LlmTokenListenerService listener;

    @BeforeEach
    void setUp() {
        ws = mock(WebSocketEventService.class);
        listener = new LlmTokenListenerService(jedisPool, ws);
    }

    @AfterEach
    void stop() {
        listener.stopAll();
    }

    private void addToken(String tokenKey, String token, String msgId) {
        try (var jedis = jedisPool.getResource()) {
            jedis.xadd(tokenKey, XAddParams.xAddParams(), Map.of("token", token, "msgId", msgId));
        }
    }

    private List<LlmChatEvent> capturedEvents() {
        ArgumentCaptor<LlmChatEvent> captor = ArgumentCaptor.forClass(LlmChatEvent.class);
        verify(ws, atLeastOnce()).broadcastEvent(captor.capture());
        return captor.getAllValues();
    }

    private void awaitBroadcasts(Duration timeout, int atLeast) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try {
                ArgumentCaptor<LlmChatEvent> captor = ArgumentCaptor.forClass(LlmChatEvent.class);
                verify(ws, atLeastOnce()).broadcastEvent(captor.capture());
                if (captor.getAllValues().size() >= atLeast) {
                    return;
                }
            } catch (AssertionError ignored) {
                // not yet
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Test
    void tokensBecomeTokenEventsWithConversationAndMsgId() {
        listener.startFor("conv1");
        addToken("chat:conv1:tok", "Hello", "r1");
        addToken("chat:conv1:tok", " world", "r1");

        awaitBroadcasts(Duration.ofSeconds(10), 2);

        List<LlmChatEvent> events = capturedEvents();
        assertThat(events).allSatisfy(e -> {
            assertThat(e.getEventType()).isEqualTo(LlmChatEvent.EventType.TOKEN);
            assertThat(e.getConversationId()).isEqualTo("conv1");
            assertThat(e.getMsgId()).isEqualTo("r1");
        });
        assertThat(events).extracting(LlmChatEvent::getValue).contains("Hello", " world");
    }

    @Test
    void concurrentConversationsDoNotCrossTokens() {
        listener.startFor("a");
        listener.startFor("b");
        addToken("chat:a:tok", "AAA", "ra");
        addToken("chat:b:tok", "BBB", "rb");

        awaitBroadcasts(Duration.ofSeconds(10), 2);

        List<LlmChatEvent> events = capturedEvents();
        // Each token is tagged with the conversation whose stream it came from.
        assertThat(events).filteredOn(e -> "a".equals(e.getConversationId()))
                .extracting(LlmChatEvent::getValue).containsOnly("AAA");
        assertThat(events).filteredOn(e -> "b".equals(e.getConversationId()))
                .extracting(LlmChatEvent::getValue).containsOnly("BBB");
    }
}
