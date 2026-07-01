package com.redis.patterns.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

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

    /** Max concurrent conversations kept alive; least-recently-used ones are evicted beyond this. */
    private int maxConversations = 100;

    /** A conversation with no activity for this long (ms) is reaped (workers stopped). */
    private long conversationIdleTtlMs = 1_800_000;

    /** How often (ms) the reaper sweeps for idle conversations. */
    private long reaperIntervalMs = 60_000;

    /** Mock-client tuning. */
    private Mock mock = new Mock();

    @Data
    public static class Mock {
        /** Artificial delay between streamed tokens, in ms, for a credible "typing" effect. */
        private long tokenDelayMs = 40;
    }
}
