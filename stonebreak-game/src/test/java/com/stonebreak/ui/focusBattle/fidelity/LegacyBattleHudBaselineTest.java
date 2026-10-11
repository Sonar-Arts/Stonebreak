package com.stonebreak.ui.focusBattle.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.GoldenStore;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Pixel baselines of the legacy focus battle HUD (#301), the first golden images of it (the older battle raster tests
 * are relative): the open menu at every standard viewport, the snapshot gallery's flow scenes plus a telegraph, low
 * HP with statuses, the timing ring and a hovered row at 1920x1080 (aiming also at 1921x1081@1.25, victory at
 * 1280x720@0.75). Regenerate with {@code -Dui.fidelity.write=true}; PNGs in
 * {@code src/test/resources/ui/fidelity/battle-hud/}.
 */
@Tag("regression")
class LegacyBattleHudBaselineTest {

    static final GoldenStore STORE = GoldenStore.forTests("ui/fidelity/battle-hud", "ui.fidelity.write");

    @Test
    void legacyBattleHudMatchesItsBaselines() {
        LegacyBattleHudCapture legacy = new LegacyBattleHudCapture();
        for (FidelityCase c : BattleHudFixtures.cases()) {
            STORE.verify(c.id(), legacy.render(c).image(), PixelTolerance.RASTER_DRIFT);
        }
    }
}
