package com.stonebreak.ui.focusBattle.fidelity;

import com.openmason.engine.ui.fidelity.FrozenCaptures;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.chat.ChatDocumentGateTest;
import com.stonebreak.ui.fidelity.GateAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The focus battle HUD migration gate (#301): {@code ui/documents/battle_hud.sbui} against the legacy
 * whole-HUD renderer's frozen captures (window rects {@code FLOAT_EXACT}, pixels {@code EXACT}).
 */
@Tag("regression")
class BattleHudDocumentGateTest {

    @Test
    void theShippedBattleHudPassesTheMigrationGate() throws Exception {
        ChatDocumentGateTest.requireLua();
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("battle-hud", BattleHudFixtures.cases(), FrozenCaptures.forTests("battle-hud").replay(), DocumentBattleHudCapture.shipped());
        GateAssert.passed(r);
    }
}
