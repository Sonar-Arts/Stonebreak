package com.stonebreak.ui.fidelity;

import com.openmason.engine.cenda.CendaLua;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.FidelityCase.Viewport;
import com.openmason.engine.ui.fidelity.FrozenCaptures;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.hotbar.DocumentHudCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The HUD migration gate (#300): the shipped {@code ui/documents/hud.sbui}, authored in Open Mason,
 * against the legacy hotbar renderer's frozen captures on every HUD case (full and hurt players, the selected
 * item's tooltip, compressed heart rows, the Arcanist's mana bar and gauge, 800x600 at 2x). The document
 * re-derives the legacy layout in its code-behind, so the strictest rules hold: geometry
 * {@code FLOAT_EXACT}, pixels {@code EXACT}. Input pass-through is {@code HudDocumentInputTest}.
 */
@Tag("regression")
class HudDocumentGateTest {

    /** Every frozen HUD capture. */
    static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("hud", List.of("full", "hurt", "tip"),
            FidelityCase.STANDARD));
        Viewport hd = FidelityCase.STANDARD.get(0);
        out.add(new FidelityCase("hud", "hardy", hd));
        out.add(new FidelityCase("hud", "arcanist", hd));
        out.add(new FidelityCase("hud", "arcanist", FidelityCase.STANDARD.get(3)));
        out.add(new FidelityCase("hud", "hurt", new Viewport(800, 600, 2f)));
        // SBO item sprites: the hotbar's own icon inset, max(2, slot / 12), at scales where it parts from
        // the provider's round(3 x scale) default (0.85x, 1.8x; the slider is continuous over 0.5-2)
        for (Viewport v : List.of(hd, FidelityCase.STANDARD.get(3), FidelityCase.STANDARD.get(2),
                new Viewport(1280, 720, 0.85f), new Viewport(1920, 1080, 1.8f))) {
            out.add(new FidelityCase("hud", "kit", v));
        }
        return out;
    }

    @Test
    void theShippedHudDocumentPassesTheMigrationGate() throws Exception {
        if (!CendaLua.isAvailable()) {
            assumeTrue(Boolean.getBoolean("ui.script.allowMissingNative"), "Cenda Lua host unavailable");
            throw new AssertionError("Cenda Lua host unavailable (build openmason-engine/cenda/build-kernels.sh): "
                + com.openmason.engine.ui.script.UiNativeHealth.check().problems());
        }
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("hud", cases(), FrozenCaptures.forTests("hud").replay(),
                DocumentHudCapture.shipped());
        GateAssert.passed(r);
    }
}
