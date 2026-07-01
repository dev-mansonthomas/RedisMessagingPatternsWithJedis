package com.redis.patterns.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.patterns.config.LlmChatProperties;
import com.redis.patterns.service.LlmChatService.ChatTurn;
import com.redis.patterns.service.LlmChatService.MessagePosted;
import com.redis.patterns.support.AbstractRedisIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Integration tests for {@link LlmChatService} against a real Redis. The responder worker and token
 * listener are mocked so these tests focus on produce/read/admin behavior and the group-ordering
 * guarantee (no real generation runs).
 */
class LlmChatServiceTest extends AbstractRedisIntegrationTest {

    private LlmChatService service;
    private LlmResponderWorker responder;
    private LlmTokenListenerService listener;

    @BeforeEach
    void setUpService() {
        responder = mock(LlmResponderWorker.class);
        listener = mock(LlmTokenListenerService.class);
        service = new LlmChatService(jedisPool, new WebSocketEventService(new ObjectMapper()),
                responder, listener, new LlmChatProperties());
    }

    @Test
    void postMessageAppendsUserTurnAndStartsWorkers() {
        MessagePosted posted = service.postMessage("conv1", "hello");

        assertThat(posted.cid()).isEqualTo("conv1");
        assertThat(posted.msgId()).isNotBlank();
        assertThat(posted.streamId()).isNotBlank();

        try (var jedis = jedisPool.getResource()) {
            assertThat(jedis.xlen("chat:conv1")).isEqualTo(1);
            StreamEntry entry = jedis.xrange("chat:conv1",
                    StreamEntryID.MINIMUM_ID, StreamEntryID.MAXIMUM_ID).get(0);
            assertThat(entry.getFields())
                    .containsEntry("role", "user")
                    .containsEntry("content", "hello")
                    .containsEntry("msgId", posted.msgId());
            assertThat(entry.getFields()).containsKey("ts");
        }

        verify(responder).startFor("conv1");
        verify(listener).startFor("conv1");
    }

    @Test
    void userMessageIsDeliverableToResponderGroup() {
        // Pins the design decision: group is created before the XADD, so a fresh probe consumer
        // reading '>' receives the just-posted user message (no missed-message race).
        service.postMessage("conv1", "hi");

        try (var jedis = jedisPool.getResource()) {
            List<Map.Entry<String, List<StreamEntry>>> delivered = jedis.xreadGroup(
                    LlmChatService.RESPONDER_GROUP, "probe",
                    XReadGroupParams.xReadGroupParams().count(10),
                    Collections.singletonMap("chat:conv1", StreamEntryID.XREADGROUP_UNDELIVERED_ENTRY));

            assertThat(delivered).hasSize(1);
            List<StreamEntry> entries = delivered.get(0).getValue();
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).getFields()).containsEntry("role", "user");
        }
    }

    @Test
    void companyUserCidIsAcceptedAndKeyed() {
        MessagePosted posted = service.postMessage("acme-corp:u-3f9a1c22", "hi");

        assertThat(posted.cid()).isEqualTo("acme-corp:u-3f9a1c22");
        try (var jedis = jedisPool.getResource()) {
            assertThat(jedis.xlen("chat:acme-corp:u-3f9a1c22")).isEqualTo(1);
        }
    }

    @Test
    void invalidCidIsRejected() {
        assertThatThrownBy(() -> service.postMessage("bad id!", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.postMessage("", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankAndOversizeContentRejected() {
        assertThatThrownBy(() -> service.postMessage("conv1", "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.postMessage("conv1", "a".repeat(4001)))
                .isInstanceOf(IllegalArgumentException.class);
        try (var jedis = jedisPool.getResource()) {
            assertThat(jedis.exists("chat:conv1")).isFalse();
        }
    }

    @Test
    void historyReturnsChronologicalTurns() {
        try (var jedis = jedisPool.getResource()) {
            jedis.xadd("chat:conv1", XAddParams.xAddParams(),
                    Map.of("role", "user", "content", "q", "ts", "1", "msgId", "m1"));
            jedis.xadd("chat:conv1", XAddParams.xAddParams(),
                    Map.of("role", "assistant", "content", "a", "ts", "2", "msgId", "m2", "model", "mock"));
        }

        List<ChatTurn> history = service.history("conv1");

        assertThat(history).hasSize(2);
        assertThat(history.get(0).role()).isEqualTo("user");
        assertThat(history.get(1).role()).isEqualTo("assistant");
        assertThat(history.get(1).content()).isEqualTo("a");
        assertThat(history.get(1).model()).isEqualTo("mock");
        assertThat(history.get(0).ts()).isEqualTo(1L);
    }

    @Test
    void historyOnUnknownConversationIsEmpty() {
        assertThat(service.history("ghost")).isEmpty();
    }

    @Test
    void groupsReportsResponderGroupAfterEnsure() {
        service.ensureConversation("conv1");
        service.postMessage("conv1", "hi");

        LlmChatService.GroupsInfo info = service.groups("conv1");

        assertThat(info.stream()).isEqualTo("chat:conv1");
        assertThat(info.length()).isEqualTo(1);
        assertThat(info.groups()).extracting(LlmChatService.GroupInfo::name)
                .contains(LlmChatService.RESPONDER_GROUP);
    }

    @Test
    void resetDeletesConversationAndTokenStreams() {
        try (var jedis = jedisPool.getResource()) {
            jedis.xadd("chat:conv1", XAddParams.xAddParams(), Map.of("role", "user", "content", "x"));
            jedis.xadd("chat:conv1:tok", XAddParams.xAddParams(), Map.of("token", "x", "msgId", "r1"));
        }

        service.reset("conv1");

        try (var jedis = jedisPool.getResource()) {
            assertThat(jedis.exists("chat:conv1")).isFalse();
            assertThat(jedis.exists("chat:conv1:tok")).isFalse();
        }
    }

    @Test
    void resetStopsThePerCidWorkers() {
        service.ensureConversation("conv1");

        service.reset("conv1");

        // Without this, the responder would keep looping XREADGROUP against a now-deleted group.
        verify(responder).stopFor("conv1");
        verify(listener).stopFor("conv1");
    }
}
