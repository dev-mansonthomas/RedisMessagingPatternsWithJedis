package com.redis.patterns.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.commands.ProtocolCommand;
import redis.clients.jedis.resps.StreamEntry;

import java.nio.charset.StandardCharsets;

/**
 * Fan-out consumer {@code cg:analytics}: reads the same {@code chat:{cid}} stream and records simple
 * per-conversation metrics for user turns — counters in a hash ({@code chat:{cid}:stats}) plus a
 * RedisTimeSeries of user tokens over time ({@code ts:{cid}:userTokens}). Best-effort: if the
 * TimeSeries module is absent, the counters still work.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmAnalyticsWorker extends AbstractUserTurnConsumer {

    // TimeSeries commands aren't on the pooled Jedis client; issue TS.ADD as a raw command.
    private static final ProtocolCommand TS_ADD = () -> "TS.ADD".getBytes(StandardCharsets.UTF_8);

    private final JedisPool jedisPool;

    @Override
    protected JedisPool jedisPool() {
        return jedisPool;
    }

    @Override
    protected String group() {
        return LlmChatService.ANALYTICS_GROUP;
    }

    @Override
    protected String threadNamePrefix() {
        return "llm-analytics-";
    }

    @Override
    protected void onUserTurn(String cid, String chatKey, StreamEntry entry, Jedis jedis) {
        String content = entry.getFields().getOrDefault("content", "");
        long tokens = tokenCount(content);

        String statsKey = LlmChatService.statsKey(cid);
        jedis.hincrBy(statsKey, "userMessages", 1);
        jedis.hincrBy(statsKey, "userTokens", tokens);

        // Time series demonstrates RedisTimeSeries integration; auto-creates on first add.
        try {
            jedis.sendCommand(TS_ADD, LlmChatService.tokensSeriesKey(cid), "*", String.valueOf(tokens));
        } catch (Exception e) {
            log.debug("TS.ADD skipped for {} ({})", chatKey, e.getMessage());
        }
    }

    private static long tokenCount(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return content.trim().split("\\s+").length;
    }
}
