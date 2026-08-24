package com.openmason.main.systems.assistant;

import com.openmason.main.systems.mcp.McpImageContent;

import java.util.ArrayList;
import java.util.List;

/**
 * One chat entry. Mutable on purpose: the worker thread appends streamed
 * text/reasoning while the UI renders snapshots per frame (all mutation and
 * reads synchronize on the owning {@link ChatSession}).
 */
public final class ChatMessage {

    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    /** Lifecycle of one tool call attached to an assistant message. */
    public enum CallStatus { PENDING, AWAITING_APPROVAL, RUNNING, OK, ERROR, DENIED }

    /** One tool call row. */
    public static final class ToolCallRecord {
        public final String id;
        public final String name;
        public final String argumentsJson;
        public volatile CallStatus status = CallStatus.PENDING;
        public volatile String resultText;
        public volatile McpImageContent image;

        public ToolCallRecord(String id, String name, String argumentsJson) {
            this.id = id;
            this.name = name;
            this.argumentsJson = argumentsJson;
        }
    }

    public final Role role;
    public final long timestampMillis;
    /** For TOOL messages: the call id this result answers. */
    public final String toolCallId;
    public final StringBuilder text = new StringBuilder();
    public final StringBuilder reasoning = new StringBuilder();
    public final List<ToolCallRecord> toolCalls = new ArrayList<>();
    public volatile String notice; // "[cancelled]", "[stream interrupted]", ...

    public ChatMessage(Role role, long timestampMillis) {
        this(role, timestampMillis, null);
    }

    public ChatMessage(Role role, long timestampMillis, String toolCallId) {
        this.role = role;
        this.timestampMillis = timestampMillis;
        this.toolCallId = toolCallId;
    }

    public static ChatMessage of(Role role, String content, long now) {
        ChatMessage m = new ChatMessage(role, now);
        m.text.append(content);
        return m;
    }
}
