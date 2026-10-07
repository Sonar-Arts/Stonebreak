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
 * Pixel baselines of the legacy entity glossary (#299) on a pinned CPU raster: the cow page at the
 * standard viewports and a small window (rows compress), each other entity state, the second variant,
 * and hovered parts (hover was wired in #299; it existed but was never fed). Captured in
 * {@code Locale.US} (the badge groups digits).
 *
 * <p>Regenerate after an intended legacy change with {@code -Dui.fidelity.write=true}.
 */
@Tag("regression")
class LegacyGlossaryBaselineTest {

    static final GoldenStore STORE = GoldenStore.forTests("ui/fidelity/glossary", "ui.fidelity.write");

    static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("glossary", List.of("cow"), FidelityCase.STANDARD));
        FidelityCase.Viewport hd = FidelityCase.STANDARD.get(0);
        out.add(new FidelityCase("glossary", "cow", new FidelityCase.Viewport(800, 600, 2f)));
        for (String v : List.of("sheep", "chicken", "goose", "cow-variant2", "cow-hover-back", "cow-hover-row1",
                "cow-hover-right")) {
            out.add(new FidelityCase("glossary", v, hd));
        }
        out.add(new FidelityCase("glossary", "cow-hover-left", FidelityCase.STANDARD.get(3)));
        return out;
    }

    @Test
    void legacyGlossaryMatchesItsBaselines() throws Exception {
        LegacyGlossaryCapture legacy = new LegacyGlossaryCapture();
        LegacyStatisticsBaselineTest.inUs(() -> {
            for (FidelityCase c : cases()) {
                STORE.verify(c.id(), legacy.render(c).image(), PixelTolerance.RASTER_DRIFT);
            }
            return null;
        });
    }

    @Test
    void theGateAcceptsTheLegacyRendererAgainstItself() throws Exception {
        LegacyGlossaryCapture legacy = new LegacyGlossaryCapture();
        MigrationGate.Report r = LegacyStatisticsBaselineTest.inUs(() ->
            new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT).run("glossary", cases(), legacy, legacy));
        assertTrue(r.passed(), r.table());
    }

    @Test
    void hitRegionsAreTheDrawnParts() {
        for (FidelityCase c : cases()) {
            MigrationGate.Capture cap = new LegacyGlossaryCapture().render(c);
            cap.hits().forEach((part, hit) -> assertArrayEquals(cap.rects().get(part), hit, 1e-3f, c.id() + " " + part));
        }
    }
}
