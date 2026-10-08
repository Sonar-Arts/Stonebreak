package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.FidelityCase.Viewport;
import com.openmason.engine.ui.fidelity.GoldenStore;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelComparator;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.inventoryScreen.core.LegacyWorkbenchCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pixel baselines of the legacy workbench (#300): the real renderer, empty and stocked (counts, a
 * selected hotbar slot, a craftable grid showing Craft All), across the fidelity viewports plus the
 * 800x600@2 overflow case, and the hover states: a slot's tooltip (also at 1.25x, where the tooltip
 * offset rounds) and each button's hover. The document must pass {@link MigrationGate} against
 * {@link LegacyWorkbenchCapture}.
 *
 * <p>Regenerate after an intended legacy change with {@code -Dui.fidelity.write=true} and review the
 * PNGs in {@code src/test/resources/ui/fidelity/workbench/}.
 */
@Tag("regression")
class LegacyWorkbenchBaselineTest {

    static final GoldenStore STORE = GoldenStore.forTests("ui/fidelity/workbench", "ui.fidelity.write");

    static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("workbench", List.of("empty", "stocked"),
            FidelityCase.STANDARD));
        out.add(new FidelityCase("workbench", "empty", new Viewport(800, 600, 2f)));
        // a scale whose meta font is off MFonts' half-pixel grid (14 x 1.8 = 25.2): the counts' font
        out.add(new FidelityCase("workbench", "stocked", new Viewport(1920, 1080, 1.8f)));
        Viewport hd = FidelityCase.STANDARD.get(0);
        for (String v : List.of("stocked-hover-main0", "stocked-hover-hot2", "stocked-hover-craft4",
                "stocked-hover-output", "empty-hover-recipes", "stocked-hover-craftall", "empty-hover-sort")) {
            out.add(new FidelityCase("workbench", v, hd));
        }
        out.add(new FidelityCase("workbench", "stocked-hover-main4", FidelityCase.STANDARD.get(3)));
        return out;
    }

    @Test
    void legacyWorkbenchMatchesItsBaselines() {
        LegacyWorkbenchCapture legacy = new LegacyWorkbenchCapture();
        for (FidelityCase c : cases()) {
            STORE.verify(c.id(), legacy.render(c).image(), PixelTolerance.RASTER_DRIFT);
        }
    }

    @Test
    void stockingTheTableChangesPixels() {
        Viewport hd = FidelityCase.STANDARD.get(0);
        MigrationGate.Capture empty = new LegacyWorkbenchCapture().render(new FidelityCase("workbench", "empty", hd));
        MigrationGate.Capture stocked = new LegacyWorkbenchCapture().render(new FidelityCase("workbench", "stocked", hd));
        assertFalse(PixelComparator.compare(empty.image(), stocked.image(), PixelTolerance.EXACT).passed(),
            "counts, the selection ring and Craft All show");
        assertTrue(stocked.rects().containsKey("craftall") && !empty.rects().containsKey("craftall"));
    }
}
