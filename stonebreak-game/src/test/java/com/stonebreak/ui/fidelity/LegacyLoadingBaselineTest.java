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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pixel baselines of the legacy world loading screen (#299) on a pinned CPU raster: the stage bar at
 * the standard viewports (the screen ignores the UI scale, so the scale must not move anything) and the
 * other progress states.
 *
 * <p>Regenerate after an intended legacy change with {@code -Dui.fidelity.write=true}.
 */
@Tag("regression")
class LegacyLoadingBaselineTest {

    static final GoldenStore STORE = GoldenStore.forTests("ui/fidelity/loading", "ui.fidelity.write");

    static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("loading", List.of("caves"), FidelityCase.STANDARD));
        FidelityCase.Viewport hd = FidelityCase.STANDARD.get(0);
        for (String v : List.of("first", "meshing", "density")) {
            out.add(new FidelityCase("loading", v, hd));
        }
        out.add(new FidelityCase("loading", "caves", new FidelityCase.Viewport(800, 600, 1f)));
        return out;
    }

    @Test
    void legacyLoadingMatchesItsBaselines() {
        LegacyLoadingCapture legacy = new LegacyLoadingCapture();
        for (FidelityCase c : cases()) {
            STORE.verify(c.id(), legacy.render(c).image(), PixelTolerance.RASTER_DRIFT);
        }
    }

    @Test
    void theGateAcceptsTheLegacyRendererAgainstItself() {
        LegacyLoadingCapture legacy = new LegacyLoadingCapture();
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("loading", cases(), legacy, legacy);
        assertTrue(r.passed(), r.table());
    }

    @Test
    void theUiScaleMovesNothing() {
        FidelityCase.Viewport a = new FidelityCase.Viewport(1920, 1080, 1f);
        FidelityCase.Viewport b = new FidelityCase.Viewport(1920, 1080, 2f);
        var one = new LegacyLoadingCapture().render(new FidelityCase("loading", "caves", a)).image();
        var two = new LegacyLoadingCapture().render(new FidelityCase("loading", "caves", b)).image();
        int differ = 0;
        for (int y = 0; y < 1080; y++) {
            for (int x = 0; x < 1920; x++) {
                if (one.pixel(x, y) != two.pixel(x, y)) {
                    differ++;
                }
            }
        }
        assertEquals(0, differ, "the loading screen draws in device pixels at every UI scale");
    }
}
