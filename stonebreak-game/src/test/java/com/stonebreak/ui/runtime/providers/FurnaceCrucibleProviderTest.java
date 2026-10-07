package com.stonebreak.ui.runtime.providers;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.runtime.UiRect;
import com.stonebreak.ui.fidelity.LegacyUiRaster;
import com.stonebreak.ui.furnace.core.FurnaceLayout;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The crucible providers (#298) see the same geometry as the legacy screen: an element over the
 * slot band (the panel's width, the band's rows) centres the crucible, its radius and the three
 * slots the chutes run to exactly where {@link FurnaceLayout#compute} puts them, at every scale of
 * the fidelity matrix. Both then paint with the one {@code CruciblePainter}.
 */
class FurnaceCrucibleProviderTest {

    @Test
    void anElementOverTheSlotBandReproducesTheLegacyGeometry() {
        List<FidelityCase.Viewport> viewports = new ArrayList<>(FidelityCase.STANDARD);
        viewports.add(new FidelityCase.Viewport(800, 600, 2f));
        for (FidelityCase.Viewport vp : viewports) {
            try (LegacyUiRaster ignored = new LegacyUiRaster(vp.width(), vp.height(), vp.uiScale())) {
                var layout = InventoryLayoutCalculator.calculateWorkbenchLayout(vp.width(), vp.height());
                FurnaceLayout.Slots legacy = FurnaceLayout.compute(layout);
                int ss = InventoryLayoutCalculator.getSlotSize();
                int pad = InventoryLayoutCalculator.getSlotPadding();
                int top = layout.panelStartY + InventoryLayoutCalculator.getPanelPadding()
                    + InventoryLayoutCalculator.getTitleHeight() + InventoryLayoutCalculator.getSectionSpacing();
                UiRect band = new UiRect(layout.panelStartX, top, layout.inventoryPanelWidth,
                    FurnaceLayout.bandHeight(ss, pad));

                FurnaceLayout.Slots doc = FurnaceCrucibleProvider.geometry(band, vp.uiScale(),
                    FurnaceCrucibleProvider.SLOT_SIZE, FurnaceCrucibleProvider.SLOT_GAP);

                String at = vp.width() + "x" + vp.height() + "@" + vp.uiScale();
                assertEquals(legacy.slotSize, doc.slotSize, at + " slot size");
                assertEquals(legacy.crucibleCenterX, doc.crucibleCenterX, 0f, at + " centre x");
                assertEquals(legacy.crucibleCenterY, doc.crucibleCenterY, 0f, at + " centre y");
                assertEquals(legacy.crucibleRadius, doc.crucibleRadius, 0f, at + " radius");
                assertEquals(legacy.ingredientX, doc.ingredientX, at);
                assertEquals(legacy.ingredientY, doc.ingredientY, at);
                assertEquals(legacy.fuelX, doc.fuelX, at);
                assertEquals(legacy.fuelY, doc.fuelY, at);
                assertEquals(legacy.outputX, doc.outputX, at);
                assertEquals(legacy.outputY, doc.outputY, at);
            }
        }
    }

    @Test
    void bothCrucibleProvidersAreDeclared() {
        assertTrue(GameDrawProviders.DECLARED.containsKey(FurnaceCrucibleProvider.BOWL_ID));
        assertTrue(GameDrawProviders.DECLARED.containsKey(FurnaceCrucibleProvider.RINGS_ID));
    }
}
