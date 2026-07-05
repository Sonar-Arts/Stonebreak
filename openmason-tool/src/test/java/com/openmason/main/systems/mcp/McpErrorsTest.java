package com.openmason.main.systems.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpErrorsTest {

    @Test
    void unknownEntityListsKnownNamesAndListTool() {
        String msg = McpErrors.unknownEntity("part", "bod", List.of("body", "head", "leg"), "list_parts")
                .getMessage();
        assertTrue(msg.contains("No part 'bod'"));
        assertTrue(msg.contains("body"));
        assertTrue(msg.contains("list_parts"));
        assertTrue(msg.contains("Did you mean 'body'?"));
    }

    @Test
    void unknownEntityWithNothingKnown() {
        String msg = McpErrors.unknownEntity("bone", "spine", List.of(), "bone_list").getMessage();
        assertTrue(msg.contains("None exist yet"));
        assertTrue(msg.contains("bone_list"));
    }

    @Test
    void unknownEntityCapsListedNames() {
        List<String> many = IntStream.range(0, 30).mapToObj(i -> "part_" + i).toList();
        String msg = McpErrors.unknownEntity("part", "zzzzzz", many, "list_parts").getMessage();
        assertTrue(msg.contains("(30 total)"));
        assertFalse(msg.contains("part_20")); // capped at 15
    }

    @Test
    void outOfRangeNamesTheRangeAndTool() {
        String msg = McpErrors.outOfRange("face id", 14, 0, 11, "part 'body'", "part_mesh")
                .getMessage();
        assertEquals("face id 14 out of range; part 'body' has face ids 0..11 (call part_mesh)", msg);
    }

    @Test
    void invalidEnumSuggestsClosest() {
        String msg = McpErrors.invalidEnum("shape", "cyllinder",
                List.of("cube", "cylinder", "sphere")).getMessage();
        assertTrue(msg.contains("Did you mean 'cylinder'?"));
        assertTrue(msg.contains("Valid: cube, cylinder, sphere."));
    }

    @Test
    void closestOnlySuggestsPlausibleTypos() {
        assertEquals("cylinder", McpErrors.closest("cyllinder", List.of("cube", "cylinder")));
        assertNull(McpErrors.closest("zzzzz", List.of("cube", "cylinder")));
        // exact match is not a typo suggestion
        assertNull(McpErrors.closest("cube", List.of("cube")));
    }
}
