package com.redis.patterns.service;

import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.StreamEntryID;

/**
 * Small shared helpers for Redis Streams, so the idioms aren't copy-pasted across services.
 */
@Slf4j
public final class RedisStreamSupport {

    private RedisStreamSupport() {
    }

    /**
     * Idempotently create a consumer group (with {@code MKSTREAM}). Swallows {@code BUSYGROUP} — the
     * one place the fragile {@code String.contains("BUSYGROUP")} check lives.
     *
     * @param jedis   an active connection
     * @param stream  stream key
     * @param group   consumer-group name
     * @param startId baseline id (e.g. {@link StreamEntryID#XGROUP_LAST_ENTRY} for "$")
     */
    public static void ensureGroup(Jedis jedis, String stream, String group, StreamEntryID startId) {
        try {
            jedis.xgroupCreate(stream, group, startId, true);
            log.debug("Created consumer group {} on {}", group, stream);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debug("Consumer group {} already exists on {}", group, stream);
            } else {
                throw e;
            }
        }
    }
}
