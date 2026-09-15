package com.openmason.main.systems.assistant;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextBudgetTest {

    private static ContextBudget.Entry entry(String role, int chars) {
        return new ContextBudget.Entry(role, "x".repeat(chars), null, null);
    }

    @Test
    void underBudgetIsUntouched() {
        List<ContextBudget.Entry> entries = List.of(
                entry("system", 400), entry("user", 400), entry("assistant", 400));
        ContextBudget.Result result = ContextBudget.fit(entries, 100_000, 4_000);
        assertFalse(result.truncated());
        assertEquals(3, result.entries().size());
    }

    @Test
    void toolResultsElideBeforeTurnsDrop() {
        List<ContextBudget.Entry> entries = new ArrayList<>();
        entries.add(entry("system", 1_000));
        for (int i = 0; i < 30; i++) {
            entries.add(entry("user", 2_000));
            entries.add(new ContextBudget.Entry("tool", "y".repeat(20_000), "id" + i, null));
        }
        // Budget forces work: ~ (30*22k chars)/4 tokens >> 40k budget.
        ContextBudget.Result result = ContextBudget.fit(entries, 50_000, 4_000);
        assertTrue(result.truncated());
        assertTrue(result.entries().stream()
                        .anyMatch(e -> "[result elided to fit context]".equals(e.content())),
                "old tool results should elide first");
        // System prompt survives.
        assertEquals("system", result.entries().get(0).role());
    }

    @Test
    void systemAndRecentAlwaysSurvive() {
        List<ContextBudget.Entry> entries = new ArrayList<>();
        entries.add(entry("system", 2_000));
        for (int i = 0; i < 100; i++) {
            entries.add(entry("user", 8_000));
        }
        ContextBudget.Result result = ContextBudget.fit(entries, 20_000, 4_000);
        assertTrue(result.truncated());
        assertTrue(result.entries().size() >= 9, "system + 8 recent must survive");
        assertEquals("system", result.entries().get(0).role());
    }

    @Test
    void estimator() {
        assertEquals(0, ContextBudget.estimateTokens(null));
        assertEquals(1, ContextBudget.estimateTokens("abc"));
        assertEquals(2, ContextBudget.estimateTokens("abcdefgh"));
    }
}
