package com.redis.patterns.config;

import com.redis.patterns.service.llm.LlmClient;
import com.redis.patterns.service.llm.MockLlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@link LlmClient} implementation selected by {@code llm.client}.
 *
 * <p>Slice 1 ships only the deterministic {@link MockLlmClient}. Other values (e.g. {@code ollama})
 * are reserved for later slices and rejected here so misconfiguration fails fast rather than
 * silently falling back.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class LlmChatConfig {

    private final LlmChatProperties properties;

    @Bean
    public LlmClient llmClient() {
        String client = properties.getClient() == null ? "" : properties.getClient().trim().toLowerCase();
        if ("mock".equals(client)) {
            log.info("LLM client = mock (deterministic, offline; token delay {}ms)",
                    properties.getMock().getTokenDelayMs());
            return new MockLlmClient(properties.getMock().getTokenDelayMs());
        }
        throw new IllegalArgumentException(
                "Unsupported llm.client='" + properties.getClient()
                        + "'. Slice 1 supports only 'mock' (Ollama is a later slice).");
    }
}
