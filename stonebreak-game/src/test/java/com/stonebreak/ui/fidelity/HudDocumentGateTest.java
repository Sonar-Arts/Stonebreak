package com.stonebreak.ui.fidelity;

import com.openmason.engine.cenda.CendaLua;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.hotbar.DocumentHudCapture;
import com.stonebreak.ui.hotbar.LegacyHudCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The HUD migration gate (#300): the shipped {@code ui/documents/hud.sbui}, authored in Open Mason,
 * against the legacy hotbar renderer on every committed HUD case (full and hurt players, the selected
 * item's tooltip, compressed heart rows, the Arcanist's mana bar and gauge, 800x600 at 2x). The document
 * re-derives the legacy layout in its code-behind, so the strictest rules hold: geometry
 * {@code FLOAT_EXACT}, pixels {@code EXACT}. Input pass-through is {@code HudDocumentInputTest}.
 */
@Tag("regression")
class HudDocumentGateTest {

    @Test
    void theShippedHudDocumentPassesTheMigrationGate() throws Exception {
        if (!CendaLua.isAvailable()) {
            assumeTrue(Boolean.getBoolean("ui.script.allowMissingNative"), "Cenda Lua host unavailable");
            throw new AssertionError("Cenda Lua host unavailable (build openmason-engine/cenda/build-kernels.sh): "
                + com.openmason.engine.ui.script.UiNativeHealth.check().problems());
        }
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("hud", LegacyHudBaselineTest.cases(), new LegacyHudCapture(),
                DocumentHudCapture.shipped());
        GateAssert.passed(r);
    }
}
