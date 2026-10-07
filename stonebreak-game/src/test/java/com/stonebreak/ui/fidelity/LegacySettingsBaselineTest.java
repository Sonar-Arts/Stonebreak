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
 * Pixel baselines of the legacy settings screen (#299) on a pinned CPU raster: every category, the
 * scrolled list, open dropdowns, the UI-scale confirmation and the hover looks
 * ({@link SettingsFixtures}).
 *
 * <p>Regenerate after an intended legacy change with {@code -Dui.fidelity.write=true}.
 */
@Tag("regression")
class LegacySettingsBaselineTest {

    static final GoldenStore STORE = GoldenStore.forTests("ui/fidelity/settings", "ui.fidelity.write");

    static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("settings", List.of("general", "quality"),
            FidelityCase.STANDARD));
        for (String v : List.of("quality-scrolled", "performance", "advanced", "extras", "audio", "general-open",
                "general-open-hover-item2", "advanced-open", "general-confirm", "general-confirm-hover-keep",
                "general-hover-category2", "general-hover-row0", "general-hover-apply", "general-hover-back",
                "audio-hover-row2")) {
            out.add(new FidelityCase("settings", v, FidelityCase.STANDARD.get(0)));
        }
        return out;
    }

    @Test
    void legacySettingsMatchItsBaselines() {
        LegacySettingsCapture legacy = new LegacySettingsCapture();
        for (FidelityCase c : cases()) {
            STORE.verify(c.id(), legacy.render(c).image(), PixelTolerance.RASTER_DRIFT);
        }
    }

    @Test
    void theGateAcceptsTheLegacyRendererAgainstItself() {
        LegacySettingsCapture legacy = new LegacySettingsCapture();
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("settings", cases(), legacy, legacy);
        assertTrue(r.passed(), r.table());
    }

    @Test
    void hitRegionsAreTheDrawnParts() {
        for (FidelityCase c : List.of(new FidelityCase("settings", "general", FidelityCase.STANDARD.get(0)),
                new FidelityCase("settings", "general-open", FidelityCase.STANDARD.get(0)))) {
            MigrationGate.Capture cap = new LegacySettingsCapture().render(c);
            cap.hits().forEach((part, hit) -> {
                if (!part.startsWith("row") && !part.equals("scrollbar")) {
                    assertArrayEquals(cap.rects().get(part), hit, 1e-3f, c.id() + " " + part);
                }
            });
        }
    }
}
