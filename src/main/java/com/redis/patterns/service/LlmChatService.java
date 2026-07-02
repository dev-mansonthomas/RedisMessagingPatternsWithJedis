package com.redis.patterns.service;

import com.redis.patterns.config.LlmChatProperties;
import com.redis.patterns.dto.LlmChatEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.commands.ProtocolCommand;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.params.XPendingParams;
import redis.clients.jedis.resps.StreamEntry;
import redis.clients.jedis.resps.StreamGroupInfo;
import redis.clients.jedis.resps.StreamPendingEntry;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Orchestrates the LLM Chat pattern (#12): the conversation lives in a Redis Stream
 * {@code chat:{cid}} (source of truth), tokens stream through {@code chat:{cid}:tok}, and a
 * {@code cg:responder} consumer group drives generation.
 *
 * <p>Only read/produce/admin operations live here; generation runs in {@link LlmResponderWorker} and
 * token fan-out to WebSocket in {@link LlmTokenListenerService}. Display reads use {@code XRANGE} /
 * {@code XINFO} — never {@code XREADGROUP} — to avoid creating phantom pending entries (ADR-0006).
 *
 * <p>Per-cid workers are lazily started and tracked here so their number stays bounded: an LRU cap
 * evicts the least-recently-used conversation and a periodic reaper stops idle ones, so a client
 * spraying distinct cids cannot grow threads/streams without limit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmChatService {

    /**
     * {@code cid} is embedded verbatim in Redis key names, so it is strictly validated. Colons are
     * allowed so the conversation can be keyed as {@code companyId:userId} (colon-separated naming),
     * giving stream keys like {@code chat:acme-corp:u-3f9a1c22}.
     */
    private static final Pattern CID_PATTERN = Pattern.compile("^[A-Za-z0-9:_-]{1,64}$");
    private static final int MAX_CONTENT_LENGTH = 4000;

    public static final String RESPONDER_GROUP = "cg:responder";
    public static final String MODERATION_GROUP = "cg:moderation";
    public static final String ANALYTICS_GROUP = "cg:analytics";
    private static final long CHAT_MAXLEN = 200;
    private static final ProtocolCommand TS_RANGE = () -> "TS.RANGE".getBytes(StandardCharsets.UTF_8);

    private final JedisPool jedisPool;
    private final WebSocketEventService webSocketEventService;
    private final LlmResponderWorker responderWorker;
    private final LlmTokenListenerService tokenListener;
    private final LlmModerationWorker moderationWorker;
    private final LlmAnalyticsWorker analyticsWorker;
    private final LlmRecoverySweeper recoverySweeper;
    private final LlmChatProperties properties;

    /** cid -> last-activity epoch ms; bounds the number of live per-cid workers. */
    private final Map<String, Long> activeConversations = new ConcurrentHashMap<>();
    private final AtomicBoolean reaperActive = new AtomicBoolean(false);
    private volatile Thread reaperThread;

    public static String chatKey(String cid) {
        return "chat:" + cid;
    }

    public static String tokenKey(String cid) {
        return "chat:" + cid + ":tok";
    }

    /** Moderation flags (fan-out group cg:moderation). */
    public static String flagsKey(String cid) {
        return "chat:" + cid + ":flags";
    }

    /** Analytics counters hash (fan-out group cg:analytics). */
    public static String statsKey(String cid) {
        return "chat:" + cid + ":stats";
    }

    /** Analytics RedisTimeSeries of user tokens over time. */
    public static String tokensSeriesKey(String cid) {
        return "ts:" + cid + ":userTokens";
    }

    /** Dead-letter stream for messages that repeatedly fail generation. */
    public static String dlqKey(String cid) {
        return "chat:" + cid + ":dlq";
    }

    /** Per-message reply-timeout key; its expiry fires a keyspace notification (see ADR-0007). */
    public static String timeoutKey(String msgId) {
        return "llm:timeout:" + msgId;
    }

    /** Shadow hash mapping a timeout key back to its conversation (survives the timeout key's expiry). */
    public static String timeoutShadowKey(String msgId) {
        return "llm:timeout:shadow:" + msgId;
    }

    @PostConstruct
    void startReaper() {
        reaperActive.set(true);
        reaperThread = Thread.ofVirtual().name("llm-conversation-reaper").start(this::reaperLoop);
        log.info("LLM conversation reaper started (idle TTL {}ms, cap {})",
                properties.getConversationIdleTtlMs(), properties.getMaxConversations());
    }

    @PreDestroy
    void stopReaper() {
        reaperActive.set(false);
        if (reaperThread != null) {
            reaperThread.interrupt();
        }
    }

    /**
     * Append a user turn to {@code chat:{cid}} and kick off generation.
     *
     * <p>The consumer group is created (and workers started) <em>before</em> the {@code XADD} so the
     * responder's {@code XREADGROUP >} is guaranteed to see this message — no missed-message race.
     */
    public MessagePosted postMessage(String cid, String content) {
        validateCid(cid);
        validateContent(content);
        ensureConversation(cid);

        String msgId = UUID.randomUUID().toString();
        String streamId;
        try (var jedis = jedisPool.getResource()) {
            Map<String, String> fields = Map.of(
                    "role", "user",
                    "content", content,
                    "ts", String.valueOf(System.currentTimeMillis()),
                    "msgId", msgId);
            StreamEntryID id = jedis.xadd(chatKey(cid),
                    XAddParams.xAddParams().maxLen(CHAT_MAXLEN).approximateTrimming(), fields);
            streamId = id.toString();

            // Reply-timeout: a key that must be deleted when the reply completes. If it expires first,
            // a Redis keyspace notification (ADR-0007) tells the user the message failed. A shadow hash
            // maps it back to this conversation (the expiry event carries only the key name).
            long timeout = properties.getTimeoutSeconds();
            jedis.setex(timeoutKey(msgId), timeout, "1");
            jedis.hset(timeoutShadowKey(msgId), Map.of("cid", cid, "content", content));
            jedis.expire(timeoutShadowKey(msgId), timeout + 60);
        }

        webSocketEventService.broadcastEvent(LlmChatEvent.builder()
                .eventType(LlmChatEvent.EventType.USER_MESSAGE)
                .conversationId(cid)
                .msgId(msgId)
                .value(content)
                .streamId(streamId)
                .ts(System.currentTimeMillis())
                .build());

        log.debug("Posted user message to {} (msgId={}, streamId={})", chatKey(cid), msgId, streamId);
        return new MessagePosted(cid, msgId, streamId);
    }

    /** Idempotently create the responder group and start the per-cid worker + token listener. */
    public void ensureConversation(String cid) {
        validateCid(cid);
        activeConversations.put(cid, System.currentTimeMillis());
        try (var jedis = jedisPool.getResource()) {
            // Create at "$" (last id) on a fresh stream (MKSTREAM). Because this runs before the
            // first XADD, each group's baseline precedes every user message, so none are missed.
            // Fan-out: three groups read the SAME stream independently, no copy.
            RedisStreamSupport.ensureGroup(jedis, chatKey(cid), RESPONDER_GROUP,
                    StreamEntryID.XGROUP_LAST_ENTRY);
            RedisStreamSupport.ensureGroup(jedis, chatKey(cid), MODERATION_GROUP,
                    StreamEntryID.XGROUP_LAST_ENTRY);
            RedisStreamSupport.ensureGroup(jedis, chatKey(cid), ANALYTICS_GROUP,
                    StreamEntryID.XGROUP_LAST_ENTRY);
        }
        tokenListener.startFor(cid);
        responderWorker.startFor(cid);
        moderationWorker.startFor(cid);
        analyticsWorker.startFor(cid);
        recoverySweeper.startFor(cid);
        enforceConversationCap();
    }

    /** Demo crash: arm a one-shot kill so the next generation for {@code cid} dies before XACK. */
    public void killWorker(String cid) {
        validateCid(cid);
        ensureConversation(cid);
        responderWorker.armKill(cid);
        log.info("Armed kill for next generation of {}", chatKey(cid));
    }

    /** Full conversation in chronological order (read-only {@code XRANGE}). */
    public List<ChatTurn> history(String cid) {
        validateCid(cid);
        List<ChatTurn> turns = new ArrayList<>();
        try (var jedis = jedisPool.getResource()) {
            if (!jedis.exists(chatKey(cid))) {
                return turns;
            }
            List<StreamEntry> entries =
                    jedis.xrange(chatKey(cid), StreamEntryID.MINIMUM_ID, StreamEntryID.MAXIMUM_ID);
            for (StreamEntry entry : entries) {
                Map<String, String> f = entry.getFields();
                turns.add(new ChatTurn(
                        entry.getID().toString(),
                        f.get("role"),
                        f.get("content"),
                        parseLongOrNull(f.get("ts")),
                        f.get("msgId"),
                        f.get("model")));
            }
        }
        return turns;
    }

    /** Consumer-group internals for the "Redis internals" panel ({@code XINFO GROUPS} + {@code XLEN}). */
    public GroupsInfo groups(String cid) {
        validateCid(cid);
        List<GroupInfo> groups = new ArrayList<>();
        List<Flag> flags = new ArrayList<>();
        List<DlqEntry> dlq = new ArrayList<>();
        Map<String, String> stats = Map.of();
        long length = 0;
        long tokenStreamLength = 0;
        try (var jedis = jedisPool.getResource()) {
            if (!jedis.exists(chatKey(cid))) {
                return new GroupsInfo(chatKey(cid), 0, tokenKey(cid), 0, groups, flags, stats,
                        dlqKey(cid), dlq);
            }
            length = jedis.xlen(chatKey(cid));
            tokenStreamLength = jedis.exists(tokenKey(cid)) ? jedis.xlen(tokenKey(cid)) : 0;
            for (StreamGroupInfo g : jedis.xinfoGroups(chatKey(cid))) {
                Object lag = g.getGroupInfo().get("lag");
                groups.add(new GroupInfo(
                        g.getName(),
                        g.getConsumers(),
                        g.getPending(),
                        lag instanceof Number n ? n.longValue() : null,
                        g.getLastDeliveredId() == null ? null : g.getLastDeliveredId().toString(),
                        pendingState(jedis, cid, g)));
            }
            // Fan-out outputs: moderation flags + analytics counters.
            if (jedis.exists(flagsKey(cid))) {
                for (StreamEntry e : jedis.xrange(flagsKey(cid),
                        StreamEntryID.MINIMUM_ID, StreamEntryID.MAXIMUM_ID)) {
                    Map<String, String> f = e.getFields();
                    flags.add(new Flag(e.getID().toString(), f.get("msgId"), f.get("term"),
                            f.get("reason"), parseLongOrNull(f.get("ts"))));
                }
            }
            if (jedis.exists(statsKey(cid))) {
                stats = jedis.hgetAll(statsKey(cid));
            }
            if (jedis.exists(dlqKey(cid))) {
                for (StreamEntry e : jedis.xrange(dlqKey(cid),
                        StreamEntryID.MINIMUM_ID, StreamEntryID.MAXIMUM_ID)) {
                    Map<String, String> f = e.getFields();
                    dlq.add(new DlqEntry(e.getID().toString(), f.get("msgId"), f.get("content"),
                            f.get("reason")));
                }
            }
        }
        return new GroupsInfo(chatKey(cid), length, tokenKey(cid), tokenStreamLength, groups, flags,
                stats, dlqKey(cid), dlq);
    }

    /**
     * Delete every Redis key for this conversation, stop its workers, and tell clients to clear.
     * This is the ONLY operation that deletes LLM-chat data — surgical per-cid `DEL`, never a flush.
     * (Per-message {@code llm:timeout:*} keys aren't cid-addressable but are short-lived / TTL'd.)
     */
    public void reset(String cid) {
        validateCid(cid);
        stopConversation(cid);
        try (var jedis = jedisPool.getResource()) {
            jedis.del(chatKey(cid), tokenKey(cid), flagsKey(cid), statsKey(cid),
                    dlqKey(cid), tokensSeriesKey(cid));
        }
        webSocketEventService.broadcastEvent(LlmChatEvent.builder()
                .eventType(LlmChatEvent.EventType.CONVERSATION_RESET)
                .conversationId(cid)
                .ts(System.currentTimeMillis())
                .build());
        log.info("Reset conversation {}", cid);
    }

    /**
     * Classify a group's pending: {@code "processing"} (a live worker is actively generating — a
     * normal wait) vs {@code "failing"} (pending but nobody is working on it — killed/crashed/poison,
     * awaiting sweeper reclaim). Only the responder group can "fail"; the fan-out groups ACK inline.
     */
    private String pendingState(redis.clients.jedis.Jedis jedis, String cid, StreamGroupInfo g) {
        if (g.getPending() == 0) {
            return null;
        }
        if (!RESPONDER_GROUP.equals(g.getName())) {
            return "processing";
        }
        for (StreamPendingEntry pe : jedis.xpending(chatKey(cid), RESPONDER_GROUP,
                XPendingParams.xPendingParams()
                        .start(StreamEntryID.MINIMUM_ID).end(StreamEntryID.MAXIMUM_ID).count(50))) {
            if (!responderWorker.isInFlight(cid, pe.getID())) {
                return "failing"; // delivered but no live worker is generating it
            }
        }
        return "processing";
    }

    /** Analytics time series (user tokens per message over time) for the chart, via {@code TS.RANGE}. */
    public List<SeriesPoint> tokenSeries(String cid) {
        validateCid(cid);
        List<SeriesPoint> points = new ArrayList<>();
        try (var jedis = jedisPool.getResource()) {
            if (!jedis.exists(tokensSeriesKey(cid))) {
                return points;
            }
            // Aggregate tokens into fixed time buckets so bursts read as taller bars and the x-axis
            // reflects send rate (rather than one bar per message).
            String bucket = String.valueOf(properties.getTokenChartBucketMs());
            Object raw = jedis.sendCommand(TS_RANGE, tokensSeriesKey(cid), "-", "+",
                    "AGGREGATION", "sum", bucket);
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof List<?> pair && pair.size() >= 2) {
                        points.add(new SeriesPoint(toLong(pair.get(0)), toDouble(pair.get(1))));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("token series unavailable for {}: {}", cid, e.getMessage());
        }
        return points;
    }

    private static long toLong(Object o) {
        if (o instanceof Long l) {
            return l;
        }
        return Long.parseLong(new String((byte[]) o, StandardCharsets.UTF_8));
    }

    private static double toDouble(Object o) {
        if (o instanceof byte[] b) {
            return Double.parseDouble(new String(b, StandardCharsets.UTF_8));
        }
        return Double.parseDouble(String.valueOf(o));
    }

    /** Stop the per-cid workers and drop the conversation from the active registry (keys untouched). */
    private void stopConversation(String cid) {
        responderWorker.stopFor(cid);
        tokenListener.stopFor(cid);
        moderationWorker.stopFor(cid);
        analyticsWorker.stopFor(cid);
        recoverySweeper.stopFor(cid);
        activeConversations.remove(cid);
    }

    /** Evict least-recently-used conversations until we're back within the configured cap. */
    private void enforceConversationCap() {
        int max = properties.getMaxConversations();
        while (activeConversations.size() > max) {
            String eldest = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<String, Long> e : activeConversations.entrySet()) {
                if (e.getValue() < oldest) {
                    oldest = e.getValue();
                    eldest = e.getKey();
                }
            }
            if (eldest == null) {
                break;
            }
            log.info("Conversation cap {} reached; evicting LRU conversation {}", max, eldest);
            stopConversation(eldest);
        }
    }

    private void reaperLoop() {
        while (reaperActive.get()) {
            try {
                Thread.sleep(properties.getReaperIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                long cutoff = System.currentTimeMillis() - properties.getConversationIdleTtlMs();
                for (Map.Entry<String, Long> e : activeConversations.entrySet()) {
                    if (e.getValue() < cutoff) {
                        log.info("Reaping idle conversation {}", e.getKey());
                        stopConversation(e.getKey());
                    }
                }
            } catch (Exception e) {
                log.warn("Reaper sweep error: {}", e.getMessage());
            }
        }
    }

    private void validateCid(String cid) {
        if (cid == null || !CID_PATTERN.matcher(cid).matches()) {
            throw new IllegalArgumentException(
                    "Invalid conversation id; must match " + CID_PATTERN.pattern());
        }
    }

    private void validateContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("content exceeds " + MAX_CONTENT_LENGTH + " chars");
        }
    }

    private static Long parseLongOrNull(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record MessagePosted(String cid, String msgId, String streamId) {}

    public record ChatTurn(String streamId, String role, String content, Long ts, String msgId, String model) {}

    public record GroupsInfo(String stream, long length, String tokenStream, long tokenStreamLength,
                             List<GroupInfo> groups, List<Flag> flags, Map<String, String> stats,
                             String dlqStream, List<DlqEntry> dlq) {}

    public record GroupInfo(String name, long consumers, long pending, Long lag, String lastDeliveredId,
                            String pendingState) {}

    public record Flag(String streamId, String msgId, String term, String reason, Long ts) {}

    public record DlqEntry(String streamId, String msgId, String content, String reason) {}

    public record SeriesPoint(long ts, double value) {}
}
