package com.redis.patterns.service;

import com.redis.patterns.dto.LlmChatEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XReadParams;
import redis.clients.jedis.resps.StreamEntry;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Per-conversation token pump. One Virtual Thread per {@code cid} does {@code XREAD BLOCK} on
 * {@code chat:{cid}:tok} and broadcasts each token as an {@link LlmChatEvent.EventType#TOKEN} event
 * carrying {@code conversationId} + {@code msgId}, so clients can demultiplex concurrent replies.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmTokenListenerService extends AbstractPerCidWorker {

    private static final int BLOCK_MS = 1000;

    private final JedisPool jedisPool;
    private final WebSocketEventService webSocketEventService;

    /** Per-cid tail boundary, captured synchronously at startFor (before any new tokens arrive). */
    private final Map<String, StreamEntryID> startIds = new ConcurrentHashMap<>();

    @Override
    public void startFor(String cid) {
        // Capture the current last id BEFORE the loop starts, so we neither replay tokens already in
        // the stream (a reused cid / post-restart) nor race-miss tokens produced right after start.
        startIds.computeIfAbsent(cid, this::resolveLastId);
        super.startFor(cid);
    }

    @Override
    public void stopFor(String cid) {
        startIds.remove(cid);
        super.stopFor(cid);
    }

    @Override
    public void stopAll() {
        startIds.clear();
        super.stopAll();
    }

    @Override
    protected String threadNamePrefix() {
        return "llm-token-listener-";
    }

    @Override
    protected void runLoop(String cid, BooleanSupplier active) {
        String tokenKey = LlmChatService.tokenKey(cid);
        StreamEntryID lastId = startIds.getOrDefault(cid, new StreamEntryID(0, 0));
        while (active.getAsBoolean()) {
            try {
                List<Map.Entry<String, List<StreamEntry>>> result;
                try (var jedis = jedisPool.getResource()) {
                    result = jedis.xread(
                            XReadParams.xReadParams().block(BLOCK_MS).count(100),
                            Collections.singletonMap(tokenKey, lastId));
                }
                if (result == null) {
                    continue;
                }
                for (Map.Entry<String, List<StreamEntry>> stream : result) {
                    for (StreamEntry entry : stream.getValue()) {
                        lastId = entry.getID();
                        Map<String, String> f = entry.getFields();
                        webSocketEventService.broadcastEvent(LlmChatEvent.builder()
                                .eventType(LlmChatEvent.EventType.TOKEN)
                                .conversationId(cid)
                                .msgId(f.get("msgId"))
                                .value(f.get("token"))
                                .streamId(entry.getID().toString())
                                .ts(System.currentTimeMillis())
                                .build());
                    }
                }
            } catch (Exception e) {
                if (active.getAsBoolean()) {
                    log.warn("Token listener error for {}: {}", tokenKey, e.getMessage());
                    sleep(1000);
                }
            }
        }
        log.info("Token listener stopped for {}", tokenKey);
    }

    /** Last id currently in the token stream, or 0-0 if it doesn't exist yet. */
    private StreamEntryID resolveLastId(String cid) {
        String tokenKey = LlmChatService.tokenKey(cid);
        try (var jedis = jedisPool.getResource()) {
            if (!jedis.exists(tokenKey)) {
                return new StreamEntryID(0, 0);
            }
            List<StreamEntry> last = jedis.xrevrange(tokenKey,
                    StreamEntryID.MAXIMUM_ID, StreamEntryID.MINIMUM_ID, 1);
            return last.isEmpty() ? new StreamEntryID(0, 0) : last.get(0).getID();
        } catch (Exception e) {
            log.warn("Could not resolve token start id for {}: {}", tokenKey, e.getMessage());
            return new StreamEntryID(0, 0);
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
