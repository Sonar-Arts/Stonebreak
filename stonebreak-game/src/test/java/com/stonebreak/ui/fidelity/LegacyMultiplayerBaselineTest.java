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
 * Pixel baselines of the legacy multiplayer menu, host-a-world and join screens (#299) on a pinned
 * CPU raster (they draw in device pixels at every UI scale, over the shared dirt backdrop).
 *
 * <p>Regenerate after an intended legacy change with {@code -Dui.fidelity.write=true}.
 */
@Tag("regression")
class LegacyMultiplayerBaselineTest {

    static final GoldenStore STORE = GoldenStore.forTests("ui/fidelity/multiplayer", "ui.fidelity.write");

    static List<FidelityCase> menuCases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("multiplayer", List.of("plain"), FidelityCase.STANDARD));
        for (String v : List.of("hover-host", "hover-join", "hover-back")) {
            out.add(new FidelityCase("multiplayer", v, FidelityCase.STANDARD.get(0)));
        }
        return out;
    }

    static List<FidelityCase> hostCases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("host-world", List.of("worlds"), FidelityCase.STANDARD));
        for (String v : List.of("empty", "nine", "worlds-focused", "worlds-status", "worlds-hover-row1",
                "worlds-hover-start", "worlds-hover-back")) {
            out.add(new FidelityCase("host-world", v, FidelityCase.STANDARD.get(0)));
        }
        return out;
    }

    static List<FidelityCase> joinCases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("join-world", List.of("filled"), FidelityCase.STANDARD));
        for (String v : List.of("filled-focused", "filled-status", "filled-hover-connect", "filled-hover-back")) {
            out.add(new FidelityCase("join-world", v, FidelityCase.STANDARD.get(0)));
        }
        return out;
    }

    static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(menuCases());
        out.addAll(hostCases());
        out.addAll(joinCases());
        return out;
    }

    @Test
    void legacyMultiplayerScreensMatchTheirBaselines() {
        LegacyMultiplayerCapture legacy = new LegacyMultiplayerCapture();
        for (FidelityCase c : cases()) {
            STORE.verify(c.id(), legacy.render(c).image(), PixelTolerance.RASTER_DRIFT);
        }
    }

    @Test
    void theGateAcceptsTheLegacyRendererAgainstItself() {
        LegacyMultiplayerCapture legacy = new LegacyMultiplayerCapture();
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("multiplayer", cases(), legacy, legacy);
        assertTrue(r.passed(), r.table());
    }

    @Test
    void hitRegionsAreTheDrawnButtons() {
        for (FidelityCase c : cases()) {
            MigrationGate.Capture cap = new LegacyMultiplayerCapture().render(c);
            cap.hits().forEach((part, hit) -> assertArrayEquals(cap.rects().get(part), hit, 1e-3f, c.id() + " " + part));
        }
    }
}
