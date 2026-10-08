package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.FidelityCase.Viewport;
import com.openmason.engine.ui.fidelity.GoldenStore;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelComparator;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.inventoryScreen.core.LegacyInventoryCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pixel baselines of the legacy inventory screen (#300): the real renderer, empty and stocked (counts,
 * a selected hotbar slot, a craftable 2x2 grid showing Craft All, a level 3 character with vitals and
 * ability bonuses), across the fidelity viewports plus the 800x600@2 overflow case, and the hover
 * states: a slot's tooltip (also at 1.25x), each button, an inactive and the active tab, an equipment
 * slot and a status slot. The document must pass {@link MigrationGate} against
 * {@link LegacyInventoryCapture}.
 *
 * <p>Regenerate after an intended legacy change with {@code -Dui.fidelity.write=true} and review the
 * PNGs in {@code src/test/resources/ui/fidelity/inventory/}.
 */
@Tag("regression")
class LegacyInventoryBaselineTest {

    static final GoldenStore STORE = GoldenStore.forTests("ui/fidelity/inventory", "ui.fidelity.write");

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
    void legacyInventoryMatchesItsBaselines() {
        LegacyInventoryCapture legacy = new LegacyInventoryCapture();
        for (FidelityCase c : cases()) {
            STORE.verify(c.id(), legacy.render(c).image(), PixelTolerance.RASTER_DRIFT);
        }
    }

    @Test
    void stockingTheInventoryChangesPixels() {
        Viewport hd = FidelityCase.STANDARD.get(0);
        MigrationGate.Capture empty = new LegacyInventoryCapture().render(new FidelityCase("inventory", "empty", hd));
        MigrationGate.Capture stocked = new LegacyInventoryCapture().render(new FidelityCase("inventory", "stocked", hd));
        assertFalse(PixelComparator.compare(empty.image(), stocked.image(), PixelTolerance.EXACT).passed(),
            "counts, the selection ring, Craft All and the character show");
        assertTrue(stocked.rects().containsKey("craftall") && !empty.rects().containsKey("craftall"));
    }
}
