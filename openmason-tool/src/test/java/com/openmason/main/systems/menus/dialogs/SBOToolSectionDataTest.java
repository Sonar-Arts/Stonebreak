package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.sbo.SBOFormat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Data side of the SBO editor's Tool tab and the mining index — no ImGui
 * frame needed: load → save conversion, validation, and index aggregation.
 */
class SBOToolSectionDataTest {

    private static SBOFormat.ToolData pick() {
        return new SBOFormat.ToolData("pickaxe", 1, 0.2f, List.of("stone", "ore"), 131, 2.5f);
    }

    @Test
    void toolDataRoundTripsThroughTheForm() {
        SBOToolSection section = new SBOToolSection(null);
        section.setFromToolData(pick());
        assertNull(section.validate());
        assertEquals(pick(), section.toToolData());

        SBOFormat.ToolData bare = new SBOFormat.ToolData("shovel", 0, 0.5f, List.of("dirt"), null, null);
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
        section.setFromToolData(new SBOFormat.ToolData("pickaxe", 0, 0.5f, List.of(), null, null));
        assertNotNull(section.validate());
    }

    @Test
    void miningIndexCollectsMaterialsAndSortsTools() {
        SBOFormat.GameProperties stone = new SBOFormat.GameProperties(1, 4f, true, true, 0, 0, "OPAQUE",
                false, false, false, 64, "BLOCKS", true, "stone", 0);
        SBOFormat.GameProperties plain = new SBOFormat.GameProperties(2, 1f, true, true, 0, 0, "OPAQUE",
                false, false, false, 64, "BLOCKS", true);
        SBOMiningIndex idx = SBOMiningIndex.of(List.of(
                doc("t:stone", stone, null),
                doc("t:plain", plain, null),
                doc("t:stone_pick", null, pick()),
                doc("t:wood_pick", null, new SBOFormat.ToolData("pickaxe", 0, 0.5f, List.of("stone"), null, null)),
                doc("t:axe", null, new SBOFormat.ToolData("axe", 0, 0.5f, List.of("wood"), null, null))));
        assertEquals(List.of("ore", "stone", "wood"), idx.materials(), "block + tool materials, sorted, unique");
        assertEquals(List.of("t:axe", "t:wood_pick", "t:stone_pick"),
                idx.tools().stream().map(SBOMiningIndex.KnownTool::objectId).toList(),
                "grouped by class, then tier");
    }

    private static SBOFormat.Document doc(String id, SBOFormat.GameProperties gp, SBOFormat.ToolData tool) {
        return new SBOFormat.Document("1.9", id, id, tool != null ? "item" : "block", "p", "c", "a",
                null, null, "model.omo", null, gp, null, null, null, null, null, null, null, tool);
    }
}
