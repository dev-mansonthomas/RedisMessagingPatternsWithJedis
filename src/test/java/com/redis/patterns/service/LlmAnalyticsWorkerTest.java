package com.redis.patterns.service;

import com.redis.patterns.support.AbstractRedisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XAddParams;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Integration tests for the analytics fan-out consumer (counters + RedisTimeSeries). */
class LlmAnalyticsWorkerTest extends AbstractRedisIntegrationTest {

    private LlmAnalyticsWorker worker;

    @BeforeEach
    void setUp() {
        worker = new LlmAnalyticsWorker(jedisPool);
    }

    @AfterEach
    void tearDown() {
        worker.stopAll();
    }

    /** Wait for the LAST write of the handler (TS.ADD after both HINCRBYs), so all fields are set. */
    private void awaitProcessed(String cid) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            try (var jedis = jedisPool.getResource()) {
                if (jedis.exists(LlmChatService.tokensSeriesKey(cid))
                        && jedis.hget(LlmChatService.statsKey(cid), "userTokens") != null) {
                    return;
                }
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
    void userTurnUpdatesCountersAndTimeSeries() {
        String cid = "conv1";
        String chatKey = LlmChatService.chatKey(cid);
        try (var jedis = jedisPool.getResource()) {
            jedis.xgroupCreate(chatKey, LlmChatService.ANALYTICS_GROUP,
                    StreamEntryID.XGROUP_LAST_ENTRY, true);
        }
        worker.startFor(cid);

        try (var jedis = jedisPool.getResource()) {
            jedis.xadd(chatKey, XAddParams.xAddParams(),
                    Map.of("role", "user", "content", "one two three", "ts", "1", "msgId", "u1"));
        }

        awaitProcessed(cid);

        try (var jedis = jedisPool.getResource()) {
            Map<String, String> stats = jedis.hgetAll(LlmChatService.statsKey(cid));
            assertThat(stats).containsEntry("userMessages", "1").containsEntry("userTokens", "3");
            // RedisTimeSeries received a sample (module is present in redis:8.8).
            assertThat(jedis.exists(LlmChatService.tokensSeriesKey(cid))).isTrue();
        }
    }
}
