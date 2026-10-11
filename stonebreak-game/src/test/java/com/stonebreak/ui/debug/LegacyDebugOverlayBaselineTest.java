package com.stonebreak.ui.debug;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.GoldenStore;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Pixel baselines of the legacy F3 overlay (#301): RAM, VRAM (a red bar), the debug card and, with a
 * monitored document, the UI budget card, at 1920x1080 and the other standard viewports (never scaled).
 * Regenerate with {@code -Dui.fidelity.write=true}; PNGs in {@code src/test/resources/ui/fidelity/debug-overlay/}.
 */
@Tag("regression")
class LegacyDebugOverlayBaselineTest {

    static final GoldenStore STORE = GoldenStore.forTests("ui/fidelity/debug-overlay", "ui.fidelity.write");

    static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("debug-overlay", List.of("with-budgets"),
            FidelityCase.STANDARD));
        out.add(new FidelityCase("debug-overlay", "cards", FidelityCase.STANDARD.get(0)));
        return out;
    }

    @Test
    void legacyDebugOverlayMatchesItsBaselines() {
        LegacyDebugOverlayCapture legacy = new LegacyDebugOverlayCapture();
        for (FidelityCase c : cases()) {
            STORE.verify(c.id(), legacy.render(c).image(), PixelTolerance.RASTER_DRIFT);
        }
    }
}
