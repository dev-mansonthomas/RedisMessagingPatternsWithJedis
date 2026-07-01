package com.redis.patterns.service.llm;

import com.redis.patterns.service.llm.LlmClient.Turn;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests for the deterministic mock LLM (no Redis, no network). */
class MockLlmClientTest {

    private final MockLlmClient mock = new MockLlmClient(0);

    private List<String> collectTokens(List<Turn> context, AtomicInteger completeCount) {
        List<String> tokens = new ArrayList<>();
        mock.generate(context, tokens::add, completeCount::incrementAndGet);
        return tokens;
    }

    @Test
    void concatenatedTokensReconstructTheFullReply() {
        AtomicInteger complete = new AtomicInteger();
        List<Turn> context = List.of(new Turn("user", "hello there"));

        List<String> tokens = collectTokens(context, complete);

        assertThat(tokens).isNotEmpty();
        assertThat(String.join("", tokens)).contains("hello there");
        assertThat(complete.get()).isEqualTo(1);
    }

    @Test
    void onCompleteIsCalledExactlyOnce() {
        AtomicInteger complete = new AtomicInteger();
        collectTokens(List.of(new Turn("user", "anything")), complete);
        assertThat(complete.get()).isEqualTo(1);
    }

    @Test
    void sameContextProducesIdenticalTokenSequence() {
        List<Turn> context = List.of(new Turn("user", "reproducible?"));

        List<String> first = collectTokens(context, new AtomicInteger());
        List<String> second = collectTokens(context, new AtomicInteger());

        assertThat(first).isEqualTo(second);
    }

    @Test
    void repliesEvenWithNoUserTurn() {
        AtomicInteger complete = new AtomicInteger();
        List<String> tokens = collectTokens(List.of(new Turn("system", "boot")), complete);

        assertThat(String.join("", tokens)).isNotBlank();
        assertThat(complete.get()).isEqualTo(1);
    }

    @Test
    void modelNameIsMock() {
        assertThat(mock.modelName()).isEqualTo("mock");
    }
}
