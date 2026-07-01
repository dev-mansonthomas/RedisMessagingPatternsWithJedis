package com.redis.patterns.service.llm;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.Consumer;

/**
 * Deterministic, offline {@link LlmClient} for the demo.
 *
 * <p>Produces a canned reply derived solely from the last user turn, streamed token by token with a
 * configurable inter-token delay so the UI renders a credible "typing" effect. Because the reply is
 * a pure function of the context, the same input always yields the same token sequence — the demo is
 * reproducible with no API key, no cost and no outbound network.
 */
@Slf4j
public class MockLlmClient implements LlmClient {

    private final long tokenDelayMs;

    public MockLlmClient(long tokenDelayMs) {
        this.tokenDelayMs = tokenDelayMs;
    }

    @Override
    public void generate(List<Turn> context, Consumer<String> onToken, Runnable onComplete) {
        String reply = buildReply(context);
        String[] words = reply.split(" ");
        for (int i = 0; i < words.length; i++) {
            // Re-append the delimiter to every token but the last so the concatenation of all
            // emitted chunks reconstructs the reply exactly.
            String token = (i < words.length - 1) ? words[i] + " " : words[i];
            sleepBetweenTokens();
            onToken.accept(token);
        }
        onComplete.run();
    }

    @Override
    public String modelName() {
        return "mock";
    }

    /** Deterministic reply: echoes the most recent user turn. */
    private String buildReply(List<Turn> context) {
        String lastUser = null;
        if (context != null) {
            for (Turn turn : context) {
                if ("user".equals(turn.role())) {
                    lastUser = turn.content();
                }
            }
        }
        if (lastUser == null || lastUser.isBlank()) {
            return "Mocked-up response — no user input yet.";
        }
        // Deterministic canned reply; a production LlmClient would generate a real answer here.
        return "Mocked-up response — user input: \"" + lastUser + "\"";
    }

    private void sleepBetweenTokens() {
        if (tokenDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(tokenDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
