package com.stonebreak.ui.crosshair;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.GoldenStore;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Pixel baselines of the legacy crosshair (#301): every {@link CrosshairFixtures} variant at 1920x1080
 * (whole-pixel centre) and 1921x1081@1.25 (half-pixel centre; the crosshair ignores the UI scale). The
 * document must pass the migration gate against {@link LegacyCrosshairCapture}.
 *
 * <p>Regenerate after an intended legacy change with {@code -Dui.fidelity.write=true} and review the
 * PNGs in {@code src/test/resources/ui/fidelity/crosshair/}.
 */
@Tag("regression")
class LegacyCrosshairBaselineTest {

    static final GoldenStore STORE = GoldenStore.forTests("ui/fidelity/crosshair", "ui.fidelity.write");

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

    @Test
    void legacyCrosshairMatchesItsBaselines() {
        LegacyCrosshairCapture legacy = new LegacyCrosshairCapture();
        for (FidelityCase c : cases()) {
            STORE.verify(c.id(), legacy.render(c).image(), PixelTolerance.RASTER_DRIFT);
        }
    }
}
