package com.stonebreak.ui.fidelity;

import com.openmason.engine.cenda.CendaLua;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.inventoryScreen.core.DocumentInventoryCapture;
import com.stonebreak.ui.inventoryScreen.core.LegacyInventoryCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The inventory screen's migration gate (#300): the shipped {@code ui/documents/inventory.sbui}, authored
 * in Open Mason, against the legacy renderer on every committed inventory case (empty and stocked across
 * the fidelity viewports, 800x600 at 2x, slot tooltips, button, tab and side-column hovers). The document re-derives
 * the legacy integer layout in its code-behind, so the strictest rules hold: geometry
 * {@code FLOAT_EXACT}, pixels {@code EXACT}. Interaction parity is {@code InventoryDocumentInteractionTest}.
 */
@Tag("regression")
class InventoryDocumentGateTest {

    @Test
    void theShippedInventoryDocumentPassesTheMigrationGate() throws Exception {
        if (!CendaLua.isAvailable()) {
            assumeTrue(Boolean.getBoolean("ui.script.allowMissingNative"), "Cenda Lua host unavailable");
            throw new AssertionError("Cenda Lua host unavailable (build openmason-engine/cenda/build-kernels.sh): "
                + com.openmason.engine.ui.script.UiNativeHealth.check().problems());
        }
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("inventory", LegacyInventoryBaselineTest.cases(), new LegacyInventoryCapture(),
                DocumentInventoryCapture.shipped());
        GateAssert.passed(r);
    }
}
