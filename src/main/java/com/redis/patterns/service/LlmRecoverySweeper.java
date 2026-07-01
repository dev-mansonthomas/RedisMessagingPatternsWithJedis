package com.redis.patterns.service;

import com.redis.patterns.config.LlmChatProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.params.XAutoClaimParams;
import redis.clients.jedis.params.XPendingParams;
import redis.clients.jedis.resps.StreamEntry;
import redis.clients.jedis.resps.StreamPendingEntry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Per-conversation crash-recovery sweeper. Periodically {@code XAUTOCLAIM}s messages that a responder
 * took but never {@code XACK}ed (it died / was killed mid-generation) and idle longer than
 * {@code minIdle}, then:
 * <ul>
 *   <li>if the message has been delivered more than {@code maxDeliveries} times → route it to
 *       {@code chat:{cid}:dlq} and {@code XACK} (give up);</li>
 *   <li>otherwise → regenerate it via {@link LlmResponderWorker#generate} (which ACKs on success).</li>
 * </ul>
 *
 * <p>It only reclaims <em>stale pending</em> entries (idle {@literal >} minIdle), so it never steals a
 * message the live responder is still working on — it competes for nothing new.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmRecoverySweeper extends AbstractPerCidWorker {

    private static final String CONSUMER = "reclaimer";
    private static final long DLQ_MAXLEN = 200;

    private final JedisPool jedisPool;
    private final LlmResponderWorker responderWorker;
    private final LlmChatProperties properties;

    @Override
    protected String threadNamePrefix() {
        return "llm-sweeper-";
    }

    @Override
    protected void runLoop(String cid, BooleanSupplier active) {
        String chatKey = LlmChatService.chatKey(cid);
        while (active.getAsBoolean()) {
            sleep(properties.getResilience().getSweepIntervalMs());
            if (!active.getAsBoolean()) {
                break;
            }
            try {
                sweepOnce(cid, chatKey);
            } catch (Exception e) {
                log.warn("Sweeper error for {}: {}", chatKey, e.getMessage());
            }
        }
        log.info("Recovery sweeper stopped for {}", chatKey);
    }

    private void sweepOnce(String cid, String chatKey) {
        long minIdle = properties.getResilience().getMinIdleMs();
        int maxDeliveries = properties.getResilience().getMaxDeliveries();

        List<StreamEntry> claimed;
        try (Jedis jedis = jedisPool.getResource()) {
            var result = jedis.xautoclaim(chatKey, LlmChatService.RESPONDER_GROUP, CONSUMER,
                    minIdle, new StreamEntryID(0, 0), XAutoClaimParams.xAutoClaimParams().count(10));
            claimed = result.getValue();
        }
        if (claimed == null || claimed.isEmpty()) {
            return;
        }

        for (StreamEntry entry : claimed) {
            // A live worker may still be generating this (a long-but-healthy reply); don't double it.
            if (responderWorker.isInFlight(cid, entry.getID())) {
                continue;
            }
            long deliveries = deliveredTimes(chatKey, entry.getID());
            if (deliveries > maxDeliveries) {
                routeToDlq(cid, chatKey, entry, deliveries);
            } else if ("user".equals(entry.getFields().get("role"))) {
                log.info("Reclaimed pending {} in {} (delivery {}), regenerating",
                        entry.getID(), chatKey, deliveries);
                responderWorker.generate(cid, chatKey, entry.getID(), entry.getFields().get("content"));
            } else {
                ack(chatKey, entry.getID());
            }
        }
    }

    private void routeToDlq(String cid, String chatKey, StreamEntry entry, long deliveries) {
        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, String> fields = new HashMap<>(entry.getFields());
            fields.put("reason", "max deliveries (" + deliveries + ") reached");
            fields.put("originalId", entry.getID().toString());
            jedis.xadd(LlmChatService.dlqKey(cid),
                    XAddParams.xAddParams().maxLen(DLQ_MAXLEN).approximateTrimming(), fields);
            jedis.xack(chatKey, LlmChatService.RESPONDER_GROUP, entry.getID());
        }
        log.info("Routed {} in {} to DLQ after {} deliveries", entry.getID(), chatKey, deliveries);
    }

    private long deliveredTimes(String chatKey, StreamEntryID id) {
        try (Jedis jedis = jedisPool.getResource()) {
            List<StreamPendingEntry> pending = jedis.xpending(chatKey, LlmChatService.RESPONDER_GROUP,
                    XPendingParams.xPendingParams().start(id).end(id).count(1));
            return pending.isEmpty() ? 0 : pending.get(0).getDeliveredTimes();
        }
    }

    private void ack(String chatKey, StreamEntryID id) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.xack(chatKey, LlmChatService.RESPONDER_GROUP, id);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
