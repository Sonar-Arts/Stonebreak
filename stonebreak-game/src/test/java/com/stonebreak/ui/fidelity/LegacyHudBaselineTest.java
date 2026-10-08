package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.FidelityCase.Viewport;
import com.openmason.engine.ui.fidelity.GoldenStore;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelComparator;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.hotbar.LegacyHudCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pixel baselines of the legacy gameplay HUD (#300): the real hotbar renderer with a full and a hurt
 * player across the fidelity viewports, plus the selected item's tooltip, a 60 HP player (hearts
 * compressed into rows, no stamina bar), the Arcanist (mana bar and resonance gauge) and the 800x600@2
 * case. The document must pass {@link MigrationGate} against {@link LegacyHudCapture}.
 *
 * <p>Regenerate after an intended legacy change with {@code -Dui.fidelity.write=true} and review the
 * PNGs in {@code src/test/resources/ui/fidelity/hud/}.
 */
@Tag("regression")
class LegacyHudBaselineTest {

    static final GoldenStore STORE = GoldenStore.forTests("ui/fidelity/hud", "ui.fidelity.write");

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
    void legacyHudMatchesItsBaselines() {
        LegacyHudCapture legacy = new LegacyHudCapture();
        for (FidelityCase c : cases()) {
            STORE.verify(c.id(), legacy.render(c).image(), PixelTolerance.RASTER_DRIFT);
        }
    }

    @Test
    void theHurtPlayerShowsDifferentHeartsAndBars() {
        Viewport hd = FidelityCase.STANDARD.get(0);
        MigrationGate.Capture full = new LegacyHudCapture().render(new FidelityCase("hud", "full", hd));
        MigrationGate.Capture hurt = new LegacyHudCapture().render(new FidelityCase("hud", "hurt", hd));
        assertFalse(PixelComparator.compare(full.image(), hurt.image(), PixelTolerance.EXACT).passed());
        assertTrue(full.rects().containsKey("stamina") && full.rects().containsKey("heart9"));
    }
}
