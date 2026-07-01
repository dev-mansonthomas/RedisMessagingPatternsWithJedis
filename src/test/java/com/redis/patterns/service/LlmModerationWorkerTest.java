package com.redis.patterns.service;

import com.redis.patterns.config.LlmChatProperties;
import com.redis.patterns.support.AbstractRedisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.resps.StreamGroupInfo;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Integration tests for the moderation fan-out consumer. */
class LlmModerationWorkerTest extends AbstractRedisIntegrationTest {

    private LlmModerationWorker worker;

    @BeforeEach
    void setUp() {
        LlmChatProperties props = new LlmChatProperties();
        props.getModeration().setKeywords(java.util.List.of("password", "secret"));
        worker = new LlmModerationWorker(jedisPool, props);
    }

    @AfterEach
    void tearDown() {
        worker.stopAll();
    }

    private void createGroup(String chatKey) {
        try (var jedis = jedisPool.getResource()) {
            jedis.xgroupCreate(chatKey, LlmChatService.MODERATION_GROUP,
                    StreamEntryID.XGROUP_LAST_ENTRY, true);
        }
    }

    private void addUser(String chatKey, String content, String msgId) {
        try (var jedis = jedisPool.getResource()) {
            jedis.xadd(chatKey, XAddParams.xAddParams(),
                    Map.of("role", "user", "content", content, "ts", "1", "msgId", msgId));
        }
    }

    private long flagsLen(String cid) {
        try (var jedis = jedisPool.getResource()) {
            return jedis.exists(LlmChatService.flagsKey(cid)) ? jedis.xlen(LlmChatService.flagsKey(cid)) : 0;
        }
    }

    private void awaitUntil(BooleanSupplier cond) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            if (cond.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** True once the moderation group has actually consumed the message (delivered + acked). */
    private boolean consumed(String chatKey) {
        try (var jedis = jedisPool.getResource()) {
            for (StreamGroupInfo g : jedis.xinfoGroups(chatKey)) {
                if (LlmChatService.MODERATION_GROUP.equals(g.getName())) {
                    StreamEntryID last = g.getLastDeliveredId();
                    return last != null && !"0-0".equals(last.toString()) && g.getPending() == 0;
                }
            }
        }
        return false;
    }

    @Test
    void flaggedKeywordProducesAFlagEntry() {
        String cid = "conv1";
        String chatKey = LlmChatService.chatKey(cid);
        createGroup(chatKey);
        worker.startFor(cid);
        addUser(chatKey, "my password is hunter2", "u1");

        awaitUntil(() -> flagsLen(cid) == 1);

        assertThat(flagsLen(cid)).isEqualTo(1);
        try (var jedis = jedisPool.getResource()) {
            var flag = jedis.xrange(LlmChatService.flagsKey(cid),
                    StreamEntryID.MINIMUM_ID, StreamEntryID.MAXIMUM_ID).get(0);
            assertThat(flag.getFields()).containsEntry("msgId", "u1").containsEntry("term", "password");
        }
    }

    @Test
    void cleanMessageIsNotFlagged() {
        String cid = "conv2";
        String chatKey = LlmChatService.chatKey(cid);
        createGroup(chatKey);
        worker.startFor(cid);
        addUser(chatKey, "hello, how are you?", "u1");

        awaitUntil(() -> consumed(chatKey));

        assertThat(flagsLen(cid)).isZero();
    }
}
