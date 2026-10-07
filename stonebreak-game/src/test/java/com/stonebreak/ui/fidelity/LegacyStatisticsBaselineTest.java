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
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pixel baselines of the legacy statistics screen (#299) on a pinned CPU raster: a played world
 * at the standard viewports, a fresh one (all zeros) and the hovered Back button. Values are
 * formatted in the default locale, so captures pin {@link Locale#US}.
 *
 * <p>Regenerate after an intended legacy change with {@code -Dui.fidelity.write=true} and review the
 * PNGs in {@code src/test/resources/ui/fidelity/statistics/}.
 */
@Tag("regression")
class LegacyStatisticsBaselineTest {

    static final GoldenStore STORE = GoldenStore.forTests("ui/fidelity/statistics", "ui.fidelity.write");

    static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("statistics", List.of("played"),
            FidelityCase.STANDARD));
        out.add(new FidelityCase("statistics", "fresh", FidelityCase.STANDARD.get(0)));
        out.add(new FidelityCase("statistics", "played-hover-back", FidelityCase.STANDARD.get(0)));
        out.add(new FidelityCase("statistics", "played-hover-back", FidelityCase.STANDARD.get(3)));
        return out;
    }

    /** Runs {@code r} with the default locale pinned to US English (the baselines' separators). */
    static <T> T inUs(java.util.concurrent.Callable<T> r) throws Exception {
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.US);
        try {
            return r.call();
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void legacyStatisticsMatchesItsBaselines() throws Exception {
        LegacyStatisticsCapture legacy = new LegacyStatisticsCapture();
        inUs(() -> {
            for (FidelityCase c : cases()) {
                STORE.verify(c.id(), legacy.render(c).image(), PixelTolerance.RASTER_DRIFT);
            }
            return null;
        });
    }

    @Test
    void theGateAcceptsTheLegacyRendererAgainstItself() throws Exception {
        LegacyStatisticsCapture legacy = new LegacyStatisticsCapture();
        MigrationGate.Report r = inUs(() -> new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("statistics", cases(), legacy, legacy));
        assertTrue(r.passed(), r.table());
    }

    @Test
    void theHitRegionIsTheDrawnButton() {
        for (FidelityCase c : cases()) {
            MigrationGate.Capture cap = new LegacyStatisticsCapture().render(c);
            assertArrayEquals(cap.rects().get("back"), cap.hits().get("back"), 1e-3f, c.id());
        }
    }
}
