package com.openmason.main.systems.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeBaseTest {

    /** The built-in packs, as the core plugin registers them. */
    private final KnowledgeBase kb = KnowledgeBase.builtin();

    @Test
    void everyPackLoads() {
        for (String pack : KnowledgeBase.BUILTIN_PACKS) {
            String text = kb.pack(pack);
            assertFalse(text.isBlank(), pack + " is empty");
            assertTrue(text.length() > 500, pack + " is suspiciously short");
        }
    }

    @Test
    void indexListsAllPacks() {
        String index = kb.index();
        for (String pack : KnowledgeBase.BUILTIN_PACKS) {
            assertTrue(index.contains(pack), "index missing " + pack);
        }
    }

    @Test
    void searchFindsWindingGuidance() {
        String result = kb.search("winding inverted face", 4);
        assertTrue(result.contains("winding"), "search should surface winding sections");
        assertTrue(result.contains("[pitfalls]") || result.contains("[tool_workflows]"),
                "expected a pack label prefix, got: " + result.substring(0, Math.min(200, result.length())));
    }

    @Test
    void unknownTopicTeaches() {
        assertThrows(IllegalArgumentException.class, () -> kb.pack("nope"));
    }

    @Test
    void pluginPacksAreListedSearchedAndRead() {
        KnowledgeBase withPlugin = new KnowledgeBase(java.util.List.of(
                new com.openmason.api.knowledge.KnowledgePack("my_plugin_pack",
                        () -> "# Plugin pack summary\n\n## Frobnicating widgets\nAlways frobnicate twice.\n")));
        assertTrue(withPlugin.index().contains("my_plugin_pack: Plugin pack summary"));
        assertTrue(withPlugin.search("frobnicate", 4).contains("[my_plugin_pack]"));
        assertTrue(withPlugin.pack("my_plugin_pack").contains("Always frobnicate twice."));
    }

    @Test
    void noMatchReturnsGuidanceNotError() {
        String result = kb.search("zzzqqqxyzzy", 4);
        assertTrue(result.contains("No knowledge sections match"));
    }
}
