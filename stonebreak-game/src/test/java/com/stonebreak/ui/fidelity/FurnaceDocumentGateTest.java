package com.stonebreak.ui.fidelity;

import com.openmason.engine.cenda.CendaLua;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.FidelityCase.Viewport;
import com.openmason.engine.ui.fidelity.FrozenCaptures;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.furnace.core.DocumentFurnaceCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The furnace migration gate (#298): the shipped {@code ui/documents/furnace.sbui}, authored in Open
 * Mason, against the legacy renderer's frozen captures on every furnace case (unlit and lit across the
 * fidelity viewports, 800x600 at 2x, a hovered inventory slot). The document reproduces the legacy
 * integer layout (truncating centring included) in its code-behind, so the strictest rules hold:
 * geometry {@code FLOAT_EXACT}, pixels {@code EXACT}. Interaction parity is
 * {@code FurnaceDocumentInteractionTest}.
 */
@Tag("regression")
class FurnaceDocumentGateTest {

    /** Every frozen furnace capture: slots empty, the lit crucible on the pinned clock. */
    static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("furnace", List.of("unlit", "lit"),
            FidelityCase.STANDARD));
        out.add(new FidelityCase("furnace", "unlit", new Viewport(800, 600, 2f)));
        out.add(new FidelityCase("furnace", "lit-hover-main0", FidelityCase.STANDARD.get(0)));
        return out;
    }

    @Test
    void theShippedFurnaceDocumentPassesTheMigrationGate() throws Exception {
        if (!CendaLua.isAvailable()) {
            assumeTrue(Boolean.getBoolean("ui.script.allowMissingNative"), "Cenda Lua host unavailable");
            throw new AssertionError("Cenda Lua host unavailable (build openmason-engine/cenda/build-kernels.sh): "
                + com.openmason.engine.ui.script.UiNativeHealth.check().problems());
        }
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("furnace", cases(), FrozenCaptures.forTests("furnace").replay(), DocumentFurnaceCapture.shipped());
        assertTrue(r.passed(), r.table());
    }
}
