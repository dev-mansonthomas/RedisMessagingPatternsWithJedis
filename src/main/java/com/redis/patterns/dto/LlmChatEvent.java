package com.redis.patterns.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Real-time event for the LLM Chat pattern (#12), broadcast over WebSocket.
 *
 * <p>The WebSocket layer broadcasts to <em>all</em> sessions (there is no per-session routing), so
 * every event carries {@code conversationId} for the client to filter on, and {@code msgId} so the
 * client can demultiplex the token stream of concurrent assistant replies.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmChatEvent {

    /** Type of event. */
    private EventType eventType;

    /** Conversation id ({@code cid}); clients filter on this. */
    private String conversationId;

    /** Message id of the turn/response; clients demux {@link EventType#TOKEN} events by this. */
    private String msgId;

    /** Token text (for {@link EventType#TOKEN}) or full content (for message events). */
    private String value;

    /** Redis stream entry id, when applicable. */
    private String streamId;

    /** Epoch milliseconds when the event occurred. */
    private long ts;

    public enum EventType {
        /** A single streamed token/chunk of an assistant reply. */
        TOKEN,
        /** A completed assistant turn was appended to the conversation stream. */
        ASSISTANT_MESSAGE,
        /** A user turn was appended to the conversation stream. */
        USER_MESSAGE,
        /** The conversation was reset (streams deleted); clients should clear their view. */
        CONVERSATION_RESET
    }
}
