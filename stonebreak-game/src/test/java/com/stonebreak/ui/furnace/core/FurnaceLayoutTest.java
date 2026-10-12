package com.stonebreak.ui.furnace.core;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.ui.fidelity.GeometryComparator;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.stonebreak.config.Settings;
import com.stonebreak.ui.furnace.core.FurnaceLayout.Slots;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator;
import com.stonebreak.ui.support.Resolutions;
import com.stonebreak.ui.support.UiLayoutAssert;
import com.stonebreak.ui.support.UiLayoutAssert.Rect;

/**
 * Guards the radial/crucible furnace UI layout: the three slots (ingredient, fuel, output)
 * must be distinct, non-overlapping, and inside the panel at every resolution.
 *
 * <p>The regression this prevents: a change to the constants or formula that silently shifts
 * two furnace slots to the same position, or pushes a slot outside the panel bounds.
 */
class FurnaceLayoutTest {

    @Test
    void threeSlotsHaveDistinctPositionsAtEveryResolution() {
        for (Resolutions.Size size : Resolutions.ALL) {
            InventoryLayoutCalculator.InventoryLayout layout =
                InventoryLayoutCalculator.calculateWorkbenchLayout(size.width(), size.height());
            Slots s = FurnaceLayout.compute(layout);

            assertTrue(s.ingredientX != s.fuelX || s.ingredientY != s.fuelY,
                size + ": ingredient and fuel slots must not share the same position");
            assertTrue(s.ingredientX != s.outputX || s.ingredientY != s.outputY,
                size + ": ingredient and output slots must not share the same position");
            assertTrue(s.fuelX != s.outputX || s.fuelY != s.outputY,
                size + ": fuel and output slots must not share the same position");
        }
    }

    @Test
    void threeSlotsDoNotOverlapAtEveryResolution() {
        for (Resolutions.Size size : Resolutions.ALL) {
            InventoryLayoutCalculator.InventoryLayout layout =
                InventoryLayoutCalculator.calculateWorkbenchLayout(size.width(), size.height());
            Slots s = FurnaceLayout.compute(layout);
            int ss = s.slotSize;

            List<Rect> rects = new ArrayList<>();
            rects.add(new Rect(s.ingredientX, s.ingredientY, ss, ss));
            rects.add(new Rect(s.fuelX, s.fuelY, ss, ss));
            rects.add(new Rect(s.outputX, s.outputY, ss, ss));

            UiLayoutAssert.assertNoOverlap(rects, size.toString());
        }
    }

    @Test
    void threeSlotsSitInsideThePanelAtEveryResolution() {
        for (Resolutions.Size size : Resolutions.ALL) {
            InventoryLayoutCalculator.InventoryLayout layout =
                InventoryLayoutCalculator.calculateWorkbenchLayout(size.width(), size.height());
            Slots s = FurnaceLayout.compute(layout);
            int ss = s.slotSize;

            Rect panel = new Rect(layout.panelStartX, layout.panelStartY,
                layout.inventoryPanelWidth, layout.inventoryPanelHeight);

            for (Rect slot : List.of(
                    new Rect(s.ingredientX, s.ingredientY, ss, ss),
                    new Rect(s.fuelX, s.fuelY, ss, ss),
                    new Rect(s.outputX, s.outputY, ss, ss))) {
                UiLayoutAssert.assertContains(panel, slot,
                    size + ": furnace slot must sit inside panel");
            }
        }
    }

    @Test
    void slotSizeIsPositiveAtEveryResolution() {
        for (Resolutions.Size size : Resolutions.ALL) {
            InventoryLayoutCalculator.InventoryLayout layout =
                InventoryLayoutCalculator.calculateWorkbenchLayout(size.width(), size.height());
            Slots s = FurnaceLayout.compute(layout);
            assertTrue(s.slotSize > 0,
                size + ": furnace slot size must be positive");
        }
    }

    /** The layout maths agree with the committed legacy oracle, exactly (same integer maths). */
    @Test
    void layoutRectsMatchTheCommittedLegacyOracle() throws IOException {
        Path oracle = Path.of("../openmason-engine/src/test/resources/ui/fixtures/legacy-geometry.json");
        assumeTrue(Files.isRegularFile(oracle), "engine fixture not next to this module");
        float previous = Settings.getInstance().getUiScale();
        int compared = 0;
        try {
            for (JsonNode c : new ObjectMapper().readTree(oracle.toFile()).get("cases")) {
                if (!c.get("screen").asText().equals("furnace")) {
                    continue;
                }
                int w = c.get("framebuffer").get(0).asInt();
                int h = c.get("framebuffer").get(1).asInt();
                Settings.getInstance().setUiScale((float) c.get("uiScale").asDouble());
                Map<String, float[]> want = new LinkedHashMap<>();
                c.get("rects").properties().forEach(e -> want.put(e.getKey(), new float[]{
                    (float) e.getValue().get(0).asDouble(), (float) e.getValue().get(1).asDouble(),
                    (float) e.getValue().get(2).asDouble(), (float) e.getValue().get(3).asDouble()}));
                GeometryComparator.GeometryReport r = GeometryComparator.compare(want,
                    DocumentFurnaceCapture.layoutRects(w, h), w, h, GeometryRule.FLOAT_EXACT);
                assertTrue(r.passed(), w + "x" + h + ": " + r.summary());
                compared++;
            }
        } finally {
            Settings.getInstance().setUiScale(previous);
        }
        assertTrue(compared >= 5, "furnace cases in the oracle: " + compared);
    }
}
