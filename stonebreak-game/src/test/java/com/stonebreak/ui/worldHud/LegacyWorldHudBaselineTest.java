package com.stonebreak.ui.worldHud;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.GoldenStore;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Pixel baselines of the legacy world HUD (#301): damage numbers (sizes and fades), Doubt counts,
 * awareness glyphs, name tags, the Ranger's prey (with and without health) and snares, and the stealth
 * HUD's states, at 1920x1080; the mixed frame at every standard viewport (the text follows the UI
 * scale, the geometry does not).
 *
 * <p>Regenerate after an intended legacy change with {@code -Dui.fidelity.write=true} and review the
 * PNGs in {@code src/test/resources/ui/fidelity/world-hud/}.
 */
@Tag("regression")
class LegacyWorldHudBaselineTest {

    static final GoldenStore STORE = GoldenStore.forTests("ui/fidelity/world-hud", "ui.fidelity.write");

    static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>();
        for (String v : WorldHudFixtures.VARIANTS) {
            if (!v.equals("mixed")) {
                out.add(new FidelityCase("world-hud", v, FidelityCase.STANDARD.get(0)));
            }
        }
        out.addAll(FidelityCase.matrix("world-hud", List.of("mixed"), FidelityCase.STANDARD));
        return out;
    }

    @Test
    void legacyWorldHudMatchesItsBaselines() {
        LegacyWorldHudCapture legacy = new LegacyWorldHudCapture();
        for (FidelityCase c : cases()) {
            STORE.verify(c.id(), legacy.render(c).image(), PixelTolerance.RASTER_DRIFT);
        }
    }
}
