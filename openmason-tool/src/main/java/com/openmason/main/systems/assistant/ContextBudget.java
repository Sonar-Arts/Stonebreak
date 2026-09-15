package com.openmason.main.systems.assistant;

import java.util.ArrayList;
import java.util.List;

/**
 * Chars/4 token estimation + history truncation. Budget = context − max
 * output − margin; truncation first elides oldest tool-result texts, then
 * drops oldest non-system turns, always keeping the system prompt and the
 * most recent turns intact.
 */
public final class ContextBudget {

    private static final long MARGIN_TOKENS = 2_048;
    private static final int KEEP_RECENT = 8; // messages always kept verbatim

    /** A history entry prepared for the wire (already role/text flattened). */
    public record Entry(String role, String content, String toolCallId,
                        List<ChatMessage.ToolCallRecord> toolCalls) {
    }

    public record Result(List<Entry> entries, boolean truncated) {
    }

    public static long estimateTokens(String text) {
        return text == null ? 0 : (text.length() + 3) / 4;
    }

    /**
     * Fit entries into {@code contextTokens − maxOutput − margin}.
     */
    public static Result fit(List<Entry> entries, long contextTokens, long maxOutputTokens) {
        long budget = Math.max(4_096, contextTokens - maxOutputTokens - MARGIN_TOKENS);
        List<Entry> work = new ArrayList<>(entries);
        boolean truncated = false;

        // Pass 1: elide old tool results.
        for (int i = 0; i < work.size() - KEEP_RECENT && total(work) > budget; i++) {
            Entry e = work.get(i);
            if ("tool".equals(e.role()) && e.content() != null && e.content().length() > 200) {
                work.set(i, new Entry(e.role(), "[result elided to fit context]",
                        e.toolCallId(), e.toolCalls()));
                truncated = true;
            }
        }
        // Pass 2: drop oldest non-system messages.
        while (total(work) > budget) {
            int drop = -1;
            for (int i = 0; i < work.size() - KEEP_RECENT; i++) {
                if (!"system".equals(work.get(i).role())) {
                    drop = i;
                    break;
                }
            }
            if (drop < 0) {
                break; // nothing left we are willing to drop
            }
            work.remove(drop);
            truncated = true;
        }
        return new Result(work, truncated);
    }

    private static long total(List<Entry> entries) {
        long sum = 0;
        for (Entry e : entries) {
            sum += estimateTokens(e.content()) + 8;
            if (e.toolCalls() != null) {
                for (ChatMessage.ToolCallRecord call : e.toolCalls()) {
                    sum += estimateTokens(call.argumentsJson) + 16;
                }
            }
        }
        return sum;
    }
}
