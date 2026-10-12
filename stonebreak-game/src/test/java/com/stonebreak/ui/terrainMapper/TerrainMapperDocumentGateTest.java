package com.stonebreak.ui.terrainMapper;

import com.openmason.engine.ui.fidelity.FrozenCaptures;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.UiWallClock;
import com.stonebreak.ui.chat.ChatDocumentGateTest;
import com.stonebreak.ui.fidelity.GateAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The terrain mapper migration gate (#301): {@code ui/documents/terrain_mapper.sbui} against the legacy
 * renderer's frozen captures (panels, map, fields, view modes, spawn and footer buttons
 * {@code FLOAT_EXACT}, pixels {@code EXACT}, the live map included).
 */
@Tag("regression")
class TerrainMapperDocumentGateTest {

    @Test
    void theShippedTerrainMapperPassesTheMigrationGate() throws Exception {
        ChatDocumentGateTest.requireLua();
        try {
            MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
                .run("terrain-mapper", TerrainMapperFixtures.cases(), FrozenCaptures.forTests("terrain-mapper").replay(),
                    DocumentTerrainMapperCapture.shipped());
            GateAssert.passed(r);
        } finally {
            UiWallClock.release();
        }
    }
}
