package com.redis.patterns.config;

import com.redis.patterns.service.llm.LlmClient;
import com.redis.patterns.service.llm.MockLlmClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for LLM client selection (no Spring context needed). */
class LlmChatConfigTest {

    private LlmChatConfig configWithClient(String client) {
        LlmChatProperties props = new LlmChatProperties();
        props.setClient(client);
        return new LlmChatConfig(props);
    }

    @Test
    void mockClientIsSelectedByDefault() {
        LlmClient client = configWithClient("mock").llmClient();
        assertThat(client).isInstanceOf(MockLlmClient.class);
        assertThat(client.modelName()).isEqualTo("mock");
    }

    @Test
    void unsupportedClientFailsFast() {
        assertThatThrownBy(() -> configWithClient("ollama").llmClient())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mock");
    }
}
