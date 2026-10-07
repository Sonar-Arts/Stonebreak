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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pixel baselines of the legacy death menu (#299): the real
 * {@link com.stonebreak.ui.deathMenu.SkijaDeathMenuRenderer} on a pinned CPU raster at the standard
 * viewports, plus the hovered Respawn button. The death document must pass {@link MigrationGate}
 * against {@link LegacyDeathCapture}; these PNGs prove the legacy side has not moved under it.
 *
 * <p>Regenerate after an intended legacy change with {@code -Dui.fidelity.write=true} and review the
 * PNGs in {@code src/test/resources/ui/fidelity/death/}.
 */
@Tag("regression")
class LegacyDeathBaselineTest {

    static final GoldenStore STORE = GoldenStore.forTests("ui/fidelity/death", "ui.fidelity.write");

    /** Every committed death baseline. */
    static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("death", List.of("dead"), FidelityCase.STANDARD));
        out.add(new FidelityCase("death", "dead-hover-respawn", FidelityCase.STANDARD.get(0)));
        out.add(new FidelityCase("death", "dead-hover-respawn", FidelityCase.STANDARD.get(3)));
        return out;
    }

    @Test
    void legacyDeathMenuMatchesItsBaselines() {
        LegacyDeathCapture legacy = new LegacyDeathCapture();
        for (FidelityCase c : cases()) {
            STORE.verify(c.id(), legacy.render(c).image(), PixelTolerance.RASTER_DRIFT);
        }
    }

    @Test
    void theGateAcceptsTheLegacyRendererAgainstItself() {
        LegacyDeathCapture legacy = new LegacyDeathCapture();
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("death", cases(), legacy, legacy);
        assertTrue(r.passed(), r.table());
    }

    @Test
    void theHitRegionIsTheDrawnButton() {
        for (FidelityCase c : cases()) {
            MigrationGate.Capture cap = new LegacyDeathCapture().render(c);
            assertArrayEquals(cap.rects().get("respawn"), cap.hits().get("respawn"), 1e-3f, c.id());
        }
    }
}
