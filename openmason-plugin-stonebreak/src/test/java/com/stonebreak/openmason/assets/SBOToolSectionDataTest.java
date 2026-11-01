package com.stonebreak.openmason.assets;

import com.stonebreak.gameplay.SboGameplay;
import com.openmason.engine.format.sbo.SBOFormat;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Data side of the SBO editor's Tool tab and the mining index — no ImGui
 * frame needed: load → save conversion, validation, and index aggregation.
 */
@Tag("regression")
class SBOToolSectionDataTest {

    private static SboGameplay.ToolData pick() {
        return new SboGameplay.ToolData("pickaxe", 1, 0.2f, List.of("stone", "ore"), 131, 2.5f);
    }

    @Test
    void toolDataRoundTripsThroughTheForm() {
        SBOToolSection section = new SBOToolSection(null);
        section.setFromToolData(pick());
        assertNull(section.validate());
        assertEquals(pick(), section.toToolData());

        SboGameplay.ToolData bare = new SboGameplay.ToolData("shovel", 0, 0.5f, List.of("dirt"), null, null);
        section.setFromToolData(bare);
        assertEquals(bare, section.toToolData(), "unset optional stats stay unset");
    }

    @Test
    void absentToolStaysAbsent() {
        SBOToolSection section = new SBOToolSection(null);
        section.setFromToolData(null);
        assertNull(section.validate());
        assertNull(section.toToolData());
    }

    @Test
    void toolWithoutMaterialsIsRejected() {
        SBOToolSection section = new SBOToolSection(null);
        section.setFromToolData(new SboGameplay.ToolData("pickaxe", 0, 0.5f, List.of(), null, null));
        assertNotNull(section.validate());
    }

    @Test
    void miningIndexCollectsMaterialsAndSortsTools() {
        SboGameplay.GameProperties stone = new SboGameplay.GameProperties(1, 4f, true, true, 0, 0,
                false, false, 64, "BLOCKS", true, "stone", 0);
        SboGameplay.GameProperties plain = new SboGameplay.GameProperties(2, 1f, true, true, 0, 0,
                false, false, 64, "BLOCKS", true, null, 0);
        SBOMiningIndex idx = SBOMiningIndex.of(List.of(
                doc("t:stone", stone, null),
                doc("t:plain", plain, null),
                doc("t:stone_pick", null, pick()),
                doc("t:wood_pick", null, new SboGameplay.ToolData("pickaxe", 0, 0.5f, List.of("stone"), null, null)),
                doc("t:axe", null, new SboGameplay.ToolData("axe", 0, 0.5f, List.of("wood"), null, null))));
        assertEquals(List.of("ore", "stone", "wood"), idx.materials(), "block + tool materials, sorted, unique");
        assertEquals(List.of("t:axe", "t:wood_pick", "t:stone_pick"),
                idx.tools().stream().map(SBOMiningIndex.KnownTool::objectId).toList(),
                "grouped by class, then tier");
    }

    private static SBOFormat.Document doc(String id, SboGameplay.GameProperties gp, SboGameplay.ToolData tool) {
        SBOFormat.Document d = new SBOFormat.Document("1.11", id, id, tool != null ? "item" : "block", "p", "c", "a",
                null, null, "model.omo", null, null, null, null, null, null);
        return SboGameplay.NONE.withGameProperties(gp).withTool(tool).applyTo(d);
    }
}
