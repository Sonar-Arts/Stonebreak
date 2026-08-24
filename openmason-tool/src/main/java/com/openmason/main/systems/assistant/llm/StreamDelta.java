package com.openmason.main.systems.assistant.llm;

/**
 * One decoded increment of an OpenAI-compatible streaming chat response.
 * Exactly one of the payload fields is meaningful per event.
 */
public sealed interface StreamDelta {

    /** Visible assistant text. */
    record Content(String text) implements StreamDelta {
    }

    /** Thinking-model reasoning text ({@code reasoning_content}). */
    record Reasoning(String text) implements StreamDelta {
    }

    /**
     * A fragment of a tool call. Fragments with the same {@code index}
     * accumulate: {@code id}/{@code name} arrive once, {@code argsFragment}
     * arrives in pieces.
     */
    record ToolCallFragment(int index, String id, String name, String argsFragment)
            implements StreamDelta {
    }

    /** Stream finished with the given reason (stop / tool_calls / length / ...). */
    record Finish(String reason) implements StreamDelta {
    }

    /** Usage totals (arrives near the end when the server reports them). */
    record Usage(long promptTokens, long completionTokens) implements StreamDelta {
    }
}
