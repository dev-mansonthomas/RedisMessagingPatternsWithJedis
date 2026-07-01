package com.redis.patterns.service;

import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Base for fan-out consumers that read the <b>same</b> {@code chat:{cid}} stream through their own
 * consumer group and process only user turns. Implements the shared {@code XREADGROUP} loop, the
 * {@code role != user → skip} guard, and the {@code XACK} — subclasses only implement the side effect
 * ({@link #onUserTurn}). Demonstrates Streams fan-out: several groups consume every message
 * independently, no copy.
 *
 * <p>Each processed entry is always ACKed (moderation/analytics don't retry), unlike the responder
 * which ACKs only after generation.
 */
@Slf4j
public abstract class AbstractUserTurnConsumer extends AbstractPerCidWorker {

    private static final int BLOCK_MS = 5000;

    protected abstract JedisPool jedisPool();

    /** The consumer group this worker reads from (e.g. {@code cg:moderation}). */
    protected abstract String group();

    protected String consumer() {
        return "worker-1";
    }

    /** Handle one user turn; {@code jedis} is an open connection to use for any writes. */
    protected abstract void onUserTurn(String cid, String chatKey, StreamEntry entry, Jedis jedis);

    @Override
    protected void runLoop(String cid, BooleanSupplier active) {
        String chatKey = LlmChatService.chatKey(cid);
        while (active.getAsBoolean()) {
            try {
                List<Map.Entry<String, List<StreamEntry>>> result;
                try (Jedis jedis = jedisPool().getResource()) {
                    result = jedis.xreadGroup(group(), consumer(),
                            XReadGroupParams.xReadGroupParams().block(BLOCK_MS).count(10),
                            Collections.singletonMap(chatKey, StreamEntryID.XREADGROUP_UNDELIVERED_ENTRY));
                }
                if (result == null) {
                    continue;
                }
                for (Map.Entry<String, List<StreamEntry>> stream : result) {
                    for (StreamEntry entry : stream.getValue()) {
                        try (Jedis jedis = jedisPool().getResource()) {
                            if ("user".equals(entry.getFields().get("role"))) {
                                onUserTurn(cid, chatKey, entry, jedis);
                            }
                            jedis.xack(chatKey, group(), entry.getID());
                        }
                    }
                }
            } catch (Exception e) {
                if (active.getAsBoolean()) {
                    log.warn("{} loop error for {}: {}", group(), chatKey, e.getMessage());
                    sleep(1000);
                }
            }
        }
        log.info("{} worker stopped for {}", group(), chatKey);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
