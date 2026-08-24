package com.openmason.main.systems.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeBaseTest {

    @Test
    void everyPackLoads() {
        for (String pack : KnowledgeBase.PACKS) {
            String text = KnowledgeBase.pack(pack);
            assertFalse(text.isBlank(), pack + " is empty");
            assertTrue(text.length() > 500, pack + " is suspiciously short");
        }
    }

    @Test
    void indexListsAllPacks() {
        String index = KnowledgeBase.index();
        for (String pack : KnowledgeBase.PACKS) {
            assertTrue(index.contains(pack), "index missing " + pack);
        }
    }

    @Test
    void searchFindsWindingGuidance() {
        String result = KnowledgeBase.search("winding inverted face", 4);
        assertTrue(result.contains("winding"), "search should surface winding sections");
        assertTrue(result.contains("[pitfalls]") || result.contains("[tool_workflows]"),
                "expected a pack label prefix, got: " + result.substring(0, Math.min(200, result.length())));
    }

    @Test
    void unknownTopicTeaches() {
        assertThrows(IllegalArgumentException.class, () -> KnowledgeBase.pack("nope"));
    }

    @Test
    void noMatchReturnsGuidanceNotError() {
        String result = KnowledgeBase.search("zzzqqqxyzzy", 4);
        assertTrue(result.contains("No knowledge sections match"));
    }
}
