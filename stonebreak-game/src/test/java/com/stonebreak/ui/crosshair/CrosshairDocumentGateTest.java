package com.stonebreak.ui.crosshair;

import com.openmason.engine.cenda.CendaLua;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.FrozenCaptures;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.fidelity.GateAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The crosshair migration gate (#301): the shipped {@code ui/documents/crosshair.sbui} against the
 * legacy crosshair renderer's frozen captures on every committed case, at the strictest rule (pixels
 * {@code EXACT}): the code-behind places device-pixel boxes, rings and discs whose strokes are centred
 * on their edges exactly as the legacy {@code drawRect}/{@code drawCircle} strokes.
 */
@Tag("regression")
class CrosshairDocumentGateTest {

    /**
     * Every {@link CrosshairFixtures} variant at 1920x1080 (whole-pixel centre) and 1921x1081@1.25
     * (half-pixel centre; the crosshair ignores the UI scale).
     */
    static List<FidelityCase> cases() {
        return FidelityCase.matrix("crosshair", CrosshairFixtures.VARIANTS,
            List.of(FidelityCase.STANDARD.get(0), FidelityCase.STANDARD.get(3)));
    }

    /**
     * The gate's cases: all but {@code wide-gap}, whose cross the gap swallows whole, leaving no element
     * to compare geometry on (its baseline is the bare backdrop; the gate's own test checks the document
     * collapses every arm).
     */
    static List<FidelityCase> gateCases() {
        return cases().stream().filter(c -> !c.id().startsWith("crosshair-wide-gap_")).toList();
    }

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
            .run("crosshair", gateCases(), FrozenCaptures.forTests("crosshair").replay(),
                DocumentCrosshairCapture.shipped());
        GateAssert.passed(r);
    }

    /**
     * A gap wider than the size (reachable from the settings: size 4-64, gap 0-16) leaves arms of
     * negative length. The document
     * draws only the arms that are left: none of the cross, the T's bar without its stem.
     */
    @Test
    void armsTheGapSwallowedAreNotDrawn() throws Exception {
        requireLua();
        var sbui = com.stonebreak.ui.runtime.GameUiDocuments.readScreen(Crosshair.DOCUMENT_ID);
        try (var stage = new com.stonebreak.ui.fidelity.DocumentStage(sbui, 1920, 1080, 1f,
                DocumentCrosshairCapture.services(CrosshairFixtures.crosshair("wide-gap")),
                com.stonebreak.network.MultiplayerSession.Mode.SINGLEPLAYER)) {
            stage.paint();
            for (int i = 1; i <= 4; i++) {
                assertEquals(true, stage.q("#arm" + i).isCollapsed(), "arm " + i);
                assertEquals(true, stage.q("#rim" + i).isCollapsed(), "rim " + i);
            }
        }
        try (var stage = new com.stonebreak.ui.fidelity.DocumentStage(sbui, 1920, 1080, 1f,
                DocumentCrosshairCapture.services(CrosshairFixtures.crosshair("t-wide")),
                com.stonebreak.network.MultiplayerSession.Mode.SINGLEPLAYER)) {
            stage.paint();
            assertEquals(false, stage.q("#arm1").isCollapsed(), "the bar");
            assertEquals(true, stage.q("#arm2").isCollapsed(), "the stem (5 - 7 = -2 px)");
        }
    }
}
