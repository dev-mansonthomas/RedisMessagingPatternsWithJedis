package com.redis.patterns.service;

import com.redis.patterns.config.LlmChatProperties;
import com.redis.patterns.dto.LlmChatEvent;
import com.redis.patterns.service.llm.LlmClient;
import com.redis.patterns.service.llm.LlmClient.Turn;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Per-conversation LLM responder. One Virtual Thread per {@code cid} blocks on
 * {@code XREADGROUP cg:responder}, reconstructs context via {@code XREVRANGE}, streams tokens to
 * {@code chat:{cid}:tok}, appends the finished assistant turn to {@code chat:{cid}}, then
 * {@code XACK}s the user message.
 *
 * <p><b>Loop-termination guard:</b> the group also delivers the assistant turns this worker itself
 * appends; those are ACKed and skipped (no regeneration), otherwise the worker would loop forever.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmResponderWorker extends AbstractPerCidWorker {

    private static final String CONSUMER = "responder-1";
    private static final long TOKEN_MAXLEN = 500;
    /** Token stream is transient once the reply is complete; expire it shortly after. */
    private static final long TOKEN_TTL_SECONDS = 60;
    private static final int BLOCK_MS = 5000;

    private final JedisPool jedisPool;
    private final WebSocketEventService webSocketEventService;
    private final LlmClient llmClient;
    private final LlmChatProperties properties;

    /** Per-cid one-shot "kill" flag: aborts the next generation before XACK (demo crash). */
    private final Map<String, AtomicBoolean> killArmed = new ConcurrentHashMap<>();

    /** Entries ("cid|id") currently being generated, so the sweeper won't reclaim a live (slow) one. */
    private final java.util.Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /** True while a live worker is actively generating this entry (not a dead/killed one). */
    public boolean isInFlight(String cid, StreamEntryID id) {
        return inFlight.contains(cid + "|" + id);
    }

    @Override
    protected String threadNamePrefix() {
        return "llm-responder-";
    }

    /** Arm a one-shot crash: the next generation for {@code cid} aborts before XACK. */
    public void armKill(String cid) {
        killArmed.computeIfAbsent(cid, k -> new AtomicBoolean()).set(true);
    }

    @Override
    public void stopFor(String cid) {
        killArmed.remove(cid);
        super.stopFor(cid);
    }

    @Override
    protected void runLoop(String cid, BooleanSupplier active) {
        String chatKey = LlmChatService.chatKey(cid);
        while (active.getAsBoolean()) {
            try {
                List<Map.Entry<String, List<StreamEntry>>> result;
                try (var jedis = jedisPool.getResource()) {
                    result = jedis.xreadGroup(
                            LlmChatService.RESPONDER_GROUP, CONSUMER,
                            XReadGroupParams.xReadGroupParams().block(BLOCK_MS).count(1),
                            Collections.singletonMap(chatKey, StreamEntryID.XREADGROUP_UNDELIVERED_ENTRY));
                }
                if (result == null) {
                    continue;
                }
                for (Map.Entry<String, List<StreamEntry>> stream : result) {
                    for (StreamEntry entry : stream.getValue()) {
                        handleEntry(cid, chatKey, entry);
                    }
                }
            } catch (Exception e) {
                if (active.getAsBoolean()) {
                    log.warn("Responder loop error for {}: {}", chatKey, e.getMessage());
                    sleep(1000);
                }
            }
        }
        log.info("Responder worker stopped for {}", chatKey);
    }

    private void handleEntry(String cid, String chatKey, StreamEntry entry) {
        String role = entry.getFields().get("role");
        // Guard: only user turns trigger generation. ACK everything else (e.g. our own assistant
        // turns the group re-delivers) to prevent an infinite generate loop.
        if (!"user".equals(role)) {
            ack(chatKey, entry.getID());
            return;
        }
        generate(cid, chatKey, entry.getID(), entry.getFields().get("content"));
    }

    /**
     * Generate a reply for a user turn. Reusable by the recovery sweeper for reclaimed messages.
     * On any failure the message is deliberately left un-ACKed (still PENDING) so it can be recovered.
     */
    void generate(String cid, String chatKey, StreamEntryID userEntryId, String content) {
        String respId = UUID.randomUUID().toString();
        String tokenKey = LlmChatService.tokenKey(cid);
        StringBuilder buffer = new StringBuilder();

        // Mark this message as actively being generated so the recovery sweeper doesn't reclaim a
        // long-but-healthy generation (which would double-produce). Removed in finally.
        String flightKey = cid + "|" + userEntryId;
        inFlight.add(flightKey);
        try {
            // Demo crash: a one-shot kill aborts before XACK, leaving the message pending for the sweeper.
            if (killArmed.computeIfAbsent(cid, k -> new AtomicBoolean()).getAndSet(false)) {
                throw new IllegalStateException("worker killed (demo) before XACK");
            }
            // Poison message: always fails -> reclaimed up to maxDeliveries -> routed to the DLQ.
            if (content != null && content.startsWith(properties.getResilience().getPoisonPrefix())) {
                throw new IllegalStateException("poison message: generation intentionally failed");
            }
            List<Turn> context = readContextUpTo(chatKey, userEntryId);

            llmClient.generate(context,
                    // Per-token: acquire a connection only for the XADD, so we never hold a pooled
                    // connection across the client's inter-token delays.
                    token -> {
                        buffer.append(token);
                        try (var jedis = jedisPool.getResource()) {
                            jedis.xadd(tokenKey,
                                    XAddParams.xAddParams().maxLen(TOKEN_MAXLEN).approximateTrimming(),
                                    Map.of("token", token, "msgId", respId));
                        }
                    },
                    () -> {
                        String reply = buffer.toString();
                        String assistantId;
                        try (var jedis = jedisPool.getResource()) {
                            assistantId = jedis.xadd(chatKey, XAddParams.xAddParams(),
                                    Map.of(
                                            "role", "assistant",
                                            "content", reply,
                                            "ts", String.valueOf(System.currentTimeMillis()),
                                            "msgId", respId,
                                            "model", llmClient.modelName())).toString();
                            jedis.expire(tokenKey, TOKEN_TTL_SECONDS);
                            jedis.xack(chatKey, LlmChatService.RESPONDER_GROUP, userEntryId);
                        }
                        broadcastAssistant(cid, respId, reply, assistantId);
                    });
        } catch (Exception e) {
            // Do NOT XACK — the user entry stays PENDING so the recovery sweeper (XAUTOCLAIM) can
            // retry it or route it to the DLQ. Only surface a terminal bubble if we actually streamed
            // something; a clean abort (kill/poison) leaves nothing so the recovered reply is pristine.
            log.warn("Generation failed for {} (msgId={}): {} — left pending for recovery",
                    chatKey, respId, e.getMessage());
            if (buffer.length() > 0) {
                broadcastAssistant(cid, respId, buffer.toString(), null);
            }
        } finally {
            inFlight.remove(flightKey);
        }
    }

    private void broadcastAssistant(String cid, String respId, String content, String streamId) {
        webSocketEventService.broadcastEvent(LlmChatEvent.builder()
                .eventType(LlmChatEvent.EventType.ASSISTANT_MESSAGE)
                .conversationId(cid)
                .msgId(respId)
                .value(content)
                .streamId(streamId)
                .ts(System.currentTimeMillis())
                .build());
    }

    /**
     * Most-recent {@code contextSize} turns at or before {@code upTo}, oldest first. Bounding by the
     * triggering entry id ensures a rapid second user message can't leak into (and be echoed by) the
     * reply to the first.
     */
    private List<Turn> readContextUpTo(String chatKey, StreamEntryID upTo) {
        List<StreamEntry> reversed;
        try (var jedis = jedisPool.getResource()) {
            reversed = jedis.xrevrange(chatKey, upTo, StreamEntryID.MINIMUM_ID, properties.getContextSize());
        }
        List<Turn> context = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            Map<String, String> f = reversed.get(i).getFields();
            context.add(new Turn(f.get("role"), f.get("content")));
        }
        return context;
    }

    private void ack(String chatKey, StreamEntryID id) {
        try (var jedis = jedisPool.getResource()) {
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
