package com.stonebreak.ui.crosshair;

import com.openmason.engine.cenda.CendaLua;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.fidelity.GateAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The crosshair migration gate (#301): the shipped {@code ui/documents/crosshair.sbui} against the
 * legacy {@code MCrosshairRenderer} on every committed case, at the strictest rule (pixels
 * {@code EXACT}): the code-behind places device-pixel boxes, rings and discs whose strokes are centred
 * on their edges exactly as the legacy {@code drawRect}/{@code drawCircle} strokes.
 */
@Tag("regression")
class CrosshairDocumentGateTest {

    static void requireLua() {
        if (!CendaLua.isAvailable()) {
            assumeTrue(Boolean.getBoolean("ui.script.allowMissingNative"), "Cenda Lua host unavailable");
            throw new AssertionError("Cenda Lua host unavailable (build openmason-engine/cenda/build-kernels.sh): "
                + com.openmason.engine.ui.script.UiNativeHealth.check().problems());
        }
    }

    @Test
    void theShippedCrosshairDocumentPassesTheMigrationGate() throws Exception {
        requireLua();
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("crosshair", LegacyCrosshairBaselineTest.gateCases(), new LegacyCrosshairCapture(),
                DocumentCrosshairCapture.shipped());
        GateAssert.passed(r);
    }

    /**
     * A gap wider than the size (reachable from the settings: size 4-64, gap 0-16) leaves arms of
     * negative length; the legacy renderer threw on them until it learned to skip them. The document
     * draws only the arms that are left: none of the cross, the T's bar without its stem.
     */
    @Test
    void armsTheGapSwallowedAreNotDrawn() throws Exception {
        requireLua();
        var sbui = com.stonebreak.ui.runtime.GameUiDocuments.readScreen(Crosshair.DOCUMENT_ID);
        try (var stage = new com.stonebreak.ui.fidelity.DocumentStage(sbui, 1920, 1080, 1f,
                DocumentCrosshairCapture.services(CrosshairFixtures.crosshair("wide-gap", null)),
                com.stonebreak.network.MultiplayerSession.Mode.SINGLEPLAYER)) {
            stage.paint();
            for (int i = 1; i <= 4; i++) {
                assertEquals(true, stage.q("#arm" + i).isCollapsed(), "arm " + i);
                assertEquals(true, stage.q("#rim" + i).isCollapsed(), "rim " + i);
            }
        }
        try (var stage = new com.stonebreak.ui.fidelity.DocumentStage(sbui, 1920, 1080, 1f,
                DocumentCrosshairCapture.services(CrosshairFixtures.crosshair("t-wide", null)),
                com.stonebreak.network.MultiplayerSession.Mode.SINGLEPLAYER)) {
            stage.paint();
            assertEquals(false, stage.q("#arm1").isCollapsed(), "the bar");
            assertEquals(true, stage.q("#arm2").isCollapsed(), "the stem (5 - 7 = -2 px)");
        }
    }
}
