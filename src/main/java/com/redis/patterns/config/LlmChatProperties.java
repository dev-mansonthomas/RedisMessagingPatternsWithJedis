package com.redis.patterns.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configuration for the LLM Chat pattern (#12), loaded from {@code application.yml} under {@code llm}.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "llm")
public class LlmChatProperties {

    /** Which {@code LlmClient} implementation to use. Slice 1 supports only {@code mock}. */
    private String client = "mock";

    /** Number of most-recent turns fed back to the LLM as context (via {@code XREVRANGE COUNT N}). */
    private int contextSize = 20;

    /** Bucket (ms) used to aggregate the user-tokens time series for the chart (TS.RANGE AGGREGATION). */
    private long tokenChartBucketMs = 10000;

    /**
     * Per-message reply timeout (seconds). A key is set on send and deleted when the reply completes;
     * if it expires first, a Redis keyspace notification tells the user their message failed.
     * Must exceed a normal/long generation and the crash-recovery window so it fires only on real failures.
     * Tuned so the notice lands just after a poison message reaches the DLQ (DLQ ~7s, timeout ~8s).
     */
    private long timeoutSeconds = 8;

    /** Max concurrent conversations kept alive; least-recently-used ones are evicted beyond this. */
    private int maxConversations = 100;

    /** A conversation with no activity for this long (ms) is reaped (workers stopped). */
    private long conversationIdleTtlMs = 1_800_000;

    /** How often (ms) the reaper sweeps for idle conversations. */
    private long reaperIntervalMs = 60_000;

    /** Mock-client tuning. */
    private Mock mock = new Mock();

    /** Content-moderation (fan-out group cg:moderation) tuning. */
    private Moderation moderation = new Moderation();

    /** Crash-recovery (XAUTOCLAIM sweeper) tuning. */
    private Resilience resilience = new Resilience();

    @Data
    public static class Mock {
        /** Artificial delay between streamed tokens, in ms, for a credible "typing" effect. */
        private long tokenDelayMs = 40;
    }

    @Data
    public static class Moderation {
        /** Case-insensitive keywords; a user message containing any is flagged (illustrative). */
        private List<String> keywords = List.of("password", "secret", "ssn", "credit card", "api key");
    }

    @Data
    public static class Resilience {
        /** A message reclaimed more than this many times is routed to the DLQ. */
        private int maxDeliveries = 2;
        /**
         * Only pending messages idle longer than this (ms) are reclaimed by the sweeper. Also paces
         * the poison→DLQ demo: with a 500ms sweep the two reclaim cycles land at ~3.5s and ~7.0s, so
         * a poison message reaches the DLQ at ~7s — just before the reply timeout (~8s).
         */
        private long minIdleMs = 3250;
        /** How often (ms) the per-cid sweeper runs XAUTOCLAIM (500ms → DLQ timing clears sweep-boundary jitter). */
        private long sweepIntervalMs = 500;
        /** A user message whose content starts with this prefix always fails (poison → DLQ demo). */
        private String poisonPrefix = "/fail";
    }
}
