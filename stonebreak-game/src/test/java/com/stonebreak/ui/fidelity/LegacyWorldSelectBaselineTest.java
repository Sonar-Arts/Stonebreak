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
 * Pixel baselines of the legacy world select screen (#299) on a pinned CPU raster: the list, the
 * scrolled list, the info card in each backup state (and flipped above its row where the window is
 * short), the delete confirmation, and the hover looks ({@link WorldSelectFixtures}).
 *
 * <p>Regenerate after an intended legacy change with {@code -Dui.fidelity.write=true}.
 */
@Tag("regression")
class LegacyWorldSelectBaselineTest {

    static final GoldenStore STORE = GoldenStore.forTests("ui/fidelity/world-select", "ui.fidelity.write");

    static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("world-select", List.of("worlds", "many-card7"),
            FidelityCase.STANDARD));
        for (String v : List.of("worlds-hover-row1", "worlds-hover-play", "worlds-hover-delete", "worlds-hover-create",
                "worlds-hover-back", "empty", "empty-hover-play", "many", "card", "card-hover-folder",
                "card-hover-backup", "card-long", "card-measuring", "card-running", "card-done", "card-failed",
                "delete", "delete-hover-confirm", "delete-hover-cancel")) {
            out.add(new FidelityCase("world-select", v, FidelityCase.STANDARD.get(0)));
        }
        return out;
    }

    @Test
    void legacyWorldSelectMatchesItsBaselines() {
        LegacyWorldSelectCapture legacy = new LegacyWorldSelectCapture();
        for (FidelityCase c : cases()) {
            STORE.verify(c.id(), legacy.render(c).image(), PixelTolerance.RASTER_DRIFT);
        }
    }

    @Test
    void theGateAcceptsTheLegacyRendererAgainstItself() {
        LegacyWorldSelectCapture legacy = new LegacyWorldSelectCapture();
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("world-select", cases(), legacy, legacy);
        assertTrue(r.passed(), r.table());
    }

    @Test
    void hitRegionsAreTheDrawnParts() {
        for (FidelityCase c : List.of(new FidelityCase("world-select", "worlds", FidelityCase.STANDARD.get(0)),
                new FidelityCase("world-select", "delete", FidelityCase.STANDARD.get(0)))) {
            MigrationGate.Capture cap = new LegacyWorldSelectCapture().render(c);
            cap.hits().forEach((part, hit) -> assertArrayEquals(cap.rects().get(part), hit, 1e-3f, c.id() + " " + part));
        }
    }
}
