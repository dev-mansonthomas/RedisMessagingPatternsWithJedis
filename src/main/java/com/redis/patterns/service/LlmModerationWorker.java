package com.redis.patterns.service;

import com.redis.patterns.config.LlmChatProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.resps.StreamEntry;

import java.util.Locale;
import java.util.Map;

/**
 * Fan-out consumer {@code cg:moderation}: reads the same {@code chat:{cid}} stream as the responder
 * and flags user messages that contain a configured keyword. Non-blocking — it annotates
 * {@code chat:{cid}:flags} and does not affect generation. Illustrative (substring match).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmModerationWorker extends AbstractUserTurnConsumer {

    private static final long FLAGS_MAXLEN = 200;

    private final JedisPool jedisPool;
    private final LlmChatProperties properties;

    @Override
    protected JedisPool jedisPool() {
        return jedisPool;
    }

    @Override
    protected String group() {
        return LlmChatService.MODERATION_GROUP;
    }

    @Override
    protected String threadNamePrefix() {
        return "llm-moderation-";
    }

    @Override
    protected void onUserTurn(String cid, String chatKey, StreamEntry entry, Jedis jedis) {
        String content = entry.getFields().getOrDefault("content", "");
        String lower = content.toLowerCase(Locale.ROOT);
        String hit = properties.getModeration().getKeywords().stream()
                .filter(k -> k != null && !k.isBlank())
                .filter(k -> lower.contains(k.toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElse(null);
        if (hit == null) {
            return;
        }
        String msgId = entry.getFields().getOrDefault("msgId", entry.getID().toString());
        jedis.xadd(LlmChatService.flagsKey(cid),
                XAddParams.xAddParams().maxLen(FLAGS_MAXLEN).approximateTrimming(),
                Map.of(
                        "msgId", msgId,
                        "term", hit,
                        "reason", "matched moderation keyword",
                        "ts", String.valueOf(System.currentTimeMillis())));
        log.info("Moderation flagged msg {} in {} (term='{}')", msgId, chatKey, hit);
    }
}
