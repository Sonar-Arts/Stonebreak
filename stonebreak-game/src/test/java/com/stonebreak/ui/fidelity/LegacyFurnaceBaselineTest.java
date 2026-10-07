package com.stonebreak.ui.fidelity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.FidelityCase.Viewport;
import com.openmason.engine.ui.fidelity.GeometryComparator;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.GoldenStore;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelComparator;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.config.Settings;
import com.stonebreak.ui.LegacyUiClock;
import com.stonebreak.ui.furnace.core.LegacyFurnaceCapture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pixel baselines of the legacy furnace (#296): the real renderer with an empty inventory, unlit
 * and lit (crucible animation frozen by the pinned clock), at the standard viewports plus the
 * 800x600@2 overflow case and a hovered slot. #298's document must pass {@link MigrationGate}
 * against {@link LegacyFurnaceCapture} under {@link GeometryRule#INTEGER_CENTRED}.
 *
 * <p>Regenerate after an intended legacy change with {@code -Dui.fidelity.write=true} and review
 * the PNGs in {@code src/test/resources/ui/fidelity/furnace/}.
 */
@Tag("regression")
class LegacyFurnaceBaselineTest {

    static final GoldenStore STORE = GoldenStore.forTests("ui/fidelity/furnace", "ui.fidelity.write");

    static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("furnace", List.of("unlit", "lit"),
            FidelityCase.STANDARD));
        out.add(new FidelityCase("furnace", "unlit", new Viewport(800, 600, 2f)));
        out.add(new FidelityCase("furnace", "lit-hover-main0", FidelityCase.STANDARD.get(0)));
        return out;
    }

    @Test
    void legacyFurnaceMatchesItsBaselines() {
        LegacyFurnaceCapture legacy = new LegacyFurnaceCapture();
        for (FidelityCase c : cases()) {
            STORE.verify(c.id(), legacy.render(c).image(), PixelTolerance.RASTER_DRIFT);
        }
    }

    @Test
    void thePinnedClockMakesTheLitCrucibleRepeatable() {
        FidelityCase lit = new FidelityCase("furnace", "lit", FidelityCase.STANDARD.get(0));
        MigrationGate.Capture a = new LegacyFurnaceCapture().render(lit);
        MigrationGate.Capture b = new LegacyFurnaceCapture().render(lit);
        assertTrue(PixelComparator.compare(a.image(), b.image(), PixelTolerance.EXACT).passed());
        assertFalse(LegacyUiClock.isPinned(), "the raster releases the clock");
    }

    @Test
    void lightingTheFurnaceChangesTheCrucibleOnly() {
        Viewport hd = FidelityCase.STANDARD.get(0);
        MigrationGate.Capture unlit = new LegacyFurnaceCapture().render(new FidelityCase("furnace", "unlit", hd));
        MigrationGate.Capture lit = new LegacyFurnaceCapture().render(new FidelityCase("furnace", "lit", hd));
        float[] in = unlit.rects().get("ingredient");
        float[] out = unlit.rects().get("output");
        float[] fuel = unlit.rects().get("fuel");
        int changed = 0;
        for (int y = 0; y < hd.height(); y++) {
            for (int x = 0; x < hd.width(); x++) {
                if (unlit.image().pixel(x, y) != lit.image().pixel(x, y)) {
                    changed++;
                    // everything that changes sits in the crucible band between the three slots
                    assertTrue(x >= in[0] - 40 && x <= out[0] + out[2] + 40 && y >= in[1] - 40
                        && y <= fuel[1] + fuel[3] + 40, "change outside the crucible at " + x + "," + y);
                }
            }
        }
        assertTrue(changed > 500, "the lit crucible shows: " + changed);
    }

    /** The renderer's layout agrees with the committed oracle, exactly (same integer maths). */
    @Test
    void rendererRectsMatchTheCommittedLegacyOracle() throws IOException {
        Path oracle = Path.of("../openmason-engine/src/test/resources/ui/fixtures/legacy-geometry.json");
        assumeTrue(Files.isRegularFile(oracle), "engine fixture not next to this module");
        float previous = Settings.getInstance().getUiScale();
        int compared = 0;
        try {
            for (JsonNode c : new ObjectMapper().readTree(oracle.toFile()).get("cases")) {
                if (!c.get("screen").asText().equals("furnace")) {
                    continue;
                }
                int w = c.get("framebuffer").get(0).asInt();
                int h = c.get("framebuffer").get(1).asInt();
                Settings.getInstance().setUiScale((float) c.get("uiScale").asDouble());
                Map<String, float[]> want = new LinkedHashMap<>();
                c.get("rects").properties().forEach(e -> want.put(e.getKey(), new float[]{
                    (float) e.getValue().get(0).asDouble(), (float) e.getValue().get(1).asDouble(),
                    (float) e.getValue().get(2).asDouble(), (float) e.getValue().get(3).asDouble()}));
                GeometryComparator.GeometryReport r = GeometryComparator.compare(want,
                    LegacyFurnaceCapture.rects(w, h), w, h, GeometryRule.FLOAT_EXACT);
                assertTrue(r.passed(), w + "x" + h + ": " + r.summary());
                compared++;
            }
        } finally {
            Settings.getInstance().setUiScale(previous);
        }
        assertTrue(compared >= 5, "furnace cases in the oracle: " + compared);
    }
}
