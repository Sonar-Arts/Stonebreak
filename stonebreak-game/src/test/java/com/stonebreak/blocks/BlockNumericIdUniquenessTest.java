package com.stonebreak.blocks;

import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOParseResult;
import com.stonebreak.rendering.sbo.SBOBlockRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every block SBO must claim its own numeric ID. Chunk saves store that ID, and on a collision
 * {@code BlockType} silently drops the later block while {@code BlockRegistry.getById} answers
 * with whichever SBO the filesystem happened to list last — so a stair could resolve to a log's
 * geometry in one checkout and not another. Branches assign IDs independently, so this is the
 * check that catches a collision a merge brings in.
 */
class BlockNumericIdUniquenessTest {

    @Test
    void noTwoBlockSbosShareANumericId() {
        SBOBlockRegistry sbo = new SBOBlockRegistry();
        sbo.scanAndLoad();

        Map<Integer, List<String>> byId = new TreeMap<>();
        for (SBOParseResult result : sbo.getAll()) {
            SBOFormat.GameProperties gp = result.manifest().gameProperties();
            if (gp == null || gp.numericId() < 0) continue;
            byId.computeIfAbsent(gp.numericId(), id -> new ArrayList<>()).add(result.getObjectId());
        }

        String collisions = byId.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
        assertTrue(byId.size() > 1, "no block SBOs were loaded");
        assertTrue(collisions.isEmpty(), "block numeric ID collisions: " + collisions);
    }
}
