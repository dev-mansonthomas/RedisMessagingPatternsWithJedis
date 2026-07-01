package com.redis.patterns.service.llm;

import java.util.List;
import java.util.function.Consumer;

/**
 * Abstraction over an LLM that streams a reply token by token.
 *
 * <p>Kept deliberately small so the demo can swap implementations by configuration
 * ({@code llm.client}). Slice 1 ships only {@link MockLlmClient} (deterministic, offline, no
 * secrets); a local Ollama-backed implementation is a later slice. See
 * {@code docs/adr/0009-llm-client-abstraction-and-mock-default.md}.
 */
public interface LlmClient {

    /**
     * Generate a reply for the given conversation context, streaming each chunk to {@code onToken}
     * as it is produced, then invoking {@code onComplete} exactly once on success.
     *
     * @param context conversation turns in chronological order (oldest first)
     * @param onToken called once per token/chunk; concatenating all chunks yields the full reply
     * @param onComplete called exactly once after the last token
     */
    void generate(List<Turn> context, Consumer<String> onToken, Runnable onComplete);

    /** Identifier recorded on assistant turns (e.g. {@code "mock"}, {@code "ollama:llama3"}). */
    String modelName();

    /** One conversation turn: who spoke and what they said. */
    record Turn(String role, String content) {}
}
