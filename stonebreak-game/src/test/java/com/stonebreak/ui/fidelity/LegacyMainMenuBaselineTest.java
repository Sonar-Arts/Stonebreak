package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.GoldenStore;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pixel baselines of the legacy main menu (#299) on a pinned CPU raster: idle at the standard
 * viewports, hovered buttons, and the title easter egg (shake, the shockwave reveal, space) at fixed
 * animation times.
 *
 * <p>Regenerate after an intended legacy change with {@code -Dui.fidelity.write=true}.
 */
@Tag("regression")
class LegacyMainMenuBaselineTest {

    static final GoldenStore STORE = GoldenStore.forTests("ui/fidelity/main-menu", "ui.fidelity.write");

    static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("main-menu", List.of("idle"), FidelityCase.STANDARD));
        FidelityCase.Viewport hd = FidelityCase.STANDARD.get(0);
        for (String v : List.of("idle-hover-singleplayer", "idle-hover-quit", "shake", "reveal", "space")) {
            out.add(new FidelityCase("main-menu", v, hd));
        }
        out.add(new FidelityCase("main-menu", "space", FidelityCase.STANDARD.get(3)));
        return out;
    }

    @Test
    void legacyMainMenuMatchesItsBaselines() {
        LegacyMainMenuCapture legacy = new LegacyMainMenuCapture();
        for (FidelityCase c : cases()) {
            STORE.verify(c.id(), legacy.render(c).image(), PixelTolerance.RASTER_DRIFT);
        }
    }

    @Test
    void theGateAcceptsTheLegacyRendererAgainstItself() {
        LegacyMainMenuCapture legacy = new LegacyMainMenuCapture();
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("main-menu", cases(), legacy, legacy);
        assertTrue(r.passed(), r.table());
    }
}
