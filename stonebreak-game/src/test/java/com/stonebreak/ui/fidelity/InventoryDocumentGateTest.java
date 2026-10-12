package com.stonebreak.ui.fidelity;

import com.openmason.engine.cenda.CendaLua;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.FidelityCase.Viewport;
import com.openmason.engine.ui.fidelity.FrozenCaptures;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.inventoryScreen.core.DocumentInventoryCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The inventory screen's migration gate (#300): the shipped {@code ui/documents/inventory.sbui}, authored
 * in Open Mason, against the legacy renderer's frozen captures on every inventory case (empty and stocked
 * across the fidelity viewports, 800x600 at 2x, slot tooltips, button, tab and side-column hovers). The
 * document re-derives the legacy integer layout in its code-behind, so the strictest rules hold: geometry
 * {@code FLOAT_EXACT}, pixels {@code EXACT}. Interaction parity is {@code InventoryDocumentInteractionTest}.
 */
@Tag("regression")
class InventoryDocumentGateTest {

    /** Every frozen inventory capture. */
    static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("inventory", List.of("empty", "stocked"),
            FidelityCase.STANDARD));
        out.add(new FidelityCase("inventory", "empty", new Viewport(800, 600, 2f)));
        // a scale whose meta font is off MFonts' half-pixel grid (14 x 1.8 = 25.2): the counts' font
        out.add(new FidelityCase("inventory", "stocked", new Viewport(1920, 1080, 1.8f)));
        Viewport hd = FidelityCase.STANDARD.get(0);
        for (String v : List.of("stocked-hover-main0", "stocked-hover-hot2", "stocked-hover-craft3",
                "stocked-hover-output", "empty-hover-recipes", "stocked-hover-craftall", "empty-hover-sort",
                "empty-hover-tab1", "empty-hover-tab0", "empty-hover-equip0", "empty-hover-status5")) {
            out.add(new FidelityCase("inventory", v, hd));
        }
        out.add(new FidelityCase("inventory", "stocked-hover-main4", FidelityCase.STANDARD.get(3)));
        return out;
    }

    @Test
    void theShippedInventoryDocumentPassesTheMigrationGate() throws Exception {
        if (!CendaLua.isAvailable()) {
            assumeTrue(Boolean.getBoolean("ui.script.allowMissingNative"), "Cenda Lua host unavailable");
            throw new AssertionError("Cenda Lua host unavailable (build openmason-engine/cenda/build-kernels.sh): "
                + com.openmason.engine.ui.script.UiNativeHealth.check().problems());
        }
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("inventory", cases(), FrozenCaptures.forTests("inventory").replay(),
                DocumentInventoryCapture.shipped());
        GateAssert.passed(r);
    }
}
