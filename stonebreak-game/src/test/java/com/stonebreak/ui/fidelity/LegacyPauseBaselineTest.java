package com.stonebreak.ui.fidelity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.FidelityCase.Viewport;
import com.openmason.engine.ui.fidelity.GeometryComparator;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.GoldenStore;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pixel baselines of the legacy pause menu (#296): the real {@link
 * com.stonebreak.ui.pauseMenu.SkijaPauseMenuRenderer} on a pinned CPU raster (bundled font,
 * checkerboard backdrop, pinned clock) at the standard viewports, offline and online, plus the
 * battle (single scrim) and hover states. #297's document must pass {@link MigrationGate} against
 * {@link LegacyPauseCapture}; these PNGs prove the legacy side has not moved underneath it.
 *
 * <p>Regenerate after an intended legacy change with {@code -Dui.fidelity.write=true} and review
 * the PNGs in {@code src/test/resources/ui/fidelity/pause/}.
 */
@Tag("regression")
class LegacyPauseBaselineTest {

    static final GoldenStore STORE = GoldenStore.forTests("ui/fidelity/pause", "ui.fidelity.write");

    /** Every committed pause baseline. */
    static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("pause", List.of("field-offline", "field-online"),
            FidelityCase.STANDARD));
        Viewport hd = FidelityCase.STANDARD.get(0);
        out.add(new FidelityCase("pause", "battle-offline", hd));
        out.add(new FidelityCase("pause", "field-offline-hover-quit", hd));
        out.add(new FidelityCase("pause", "field-online-hover-resync", FidelityCase.STANDARD.get(3)));
        return out;
    }

    @Test
    void legacyPauseMatchesItsBaselines() {
        LegacyPauseCapture legacy = new LegacyPauseCapture();
        for (FidelityCase c : cases()) {
            STORE.verify(c.id(), legacy.render(c).image(), PixelTolerance.RASTER_DRIFT);
        }
    }

    @Test
    void theGateAcceptsTheLegacyRendererAgainstItself() {
        // What #297 runs with its document as the candidate: here legacy vs legacy, so every case
        // must pass with the strictest tolerance (and no PNGs are needed).
        LegacyPauseCapture legacy = new LegacyPauseCapture();
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("pause", cases().subList(0, 2), legacy, legacy);
        assertTrue(r.passed(), r.table());
    }

    @Test
    void theFieldPauseIsDarkerThanTheBattlePause() {
        Viewport hd = FidelityCase.STANDARD.get(0);
        MigrationGate.Capture field = new LegacyPauseCapture().render(new FidelityCase("pause", "field-offline", hd));
        MigrationGate.Capture battle = new LegacyPauseCapture().render(new FidelityCase("pause", "battle-offline", hd));
        // (8, 8) is backdrop under the scrim only, inside the first checker square (CHECK_B)
        int f = field.image().pixel(8, 8) >>> 8 & 0xFF;
        int b = battle.image().pixel(8, 8) >>> 8 & 0xFF;
        int raw = LegacyUiRaster.CHECK_B >>> 8 & 0xFF;
        assertEquals(raw * (255 - 0x78) / 255.0, b, 1.5, "one 0x78 scrim");
        assertEquals(raw * Math.pow((255 - 0x78) / 255.0, 2), f, 1.5, "two stacked scrims");
    }

    @Test
    void hoverChangesOnlyTheHoveredButton() {
        Viewport hd = FidelityCase.STANDARD.get(0);
        MigrationGate.Capture plain = new LegacyPauseCapture().render(new FidelityCase("pause", "field-offline", hd));
        MigrationGate.Capture hover = new LegacyPauseCapture().render(new FidelityCase("pause", "field-offline-hover-quit", hd));
        float[] quit = plain.rects().get("quit");
        int inside = 0;
        int outside = 0;
        for (int y = 0; y < hd.height(); y++) {
            for (int x = 0; x < hd.width(); x++) {
                if (plain.image().pixel(x, y) != hover.image().pixel(x, y)) {
                    boolean in = x >= quit[0] - 8 && x < quit[0] + quit[2] + 8 && y >= quit[1] - 8 && y < quit[1] + quit[3] + 8;
                    if (in) {
                        inside++;
                    } else {
                        outside++;
                    }
                }
            }
        }
        assertTrue(inside > 1000, "the quit button lights up: " + inside);
        assertEquals(0, outside, "nothing else changes");
    }

    /**
     * What the game renderer actually draws (read through its layout sink, not a copy of its
     * maths) agrees with the oracle the engine fixtures pin
     * ({@code openmason-engine/src/test/resources/ui/fixtures/legacy-geometry.json}), so a gate
     * run here and the engine's flexbox gate judge the same rects. Never skipped: a runner that
     * cannot see the fixture fails loudly (#296 review).
     */
    @Test
    void rendererRectsMatchTheCommittedLegacyOracle() throws IOException {
        Path oracle = engineFixture("ui/fixtures/legacy-geometry.json");
        int compared = 0;
        for (JsonNode c : new ObjectMapper().readTree(oracle.toFile()).get("cases")) {
            if (!c.get("screen").asText().equals("pause")) {
                continue;
            }
            int w = c.get("framebuffer").get(0).asInt();
            int h = c.get("framebuffer").get(1).asInt();
            float s = (float) c.get("uiScale").asDouble();
            Map<String, float[]> want = new LinkedHashMap<>();
            c.get("rects").properties().forEach(e -> want.put(e.getKey(), new float[]{
                (float) e.getValue().get(0).asDouble(), (float) e.getValue().get(1).asDouble(),
                (float) e.getValue().get(2).asDouble(), (float) e.getValue().get(3).asDouble()}));
            GeometryComparator.GeometryReport r = GeometryComparator.compare(want,
                LegacyPauseCapture.rects(w, h, s, c.get("online").asBoolean()), w, h, GeometryRule.FLOAT_EXACT);
            assertTrue(r.passed(), w + "x" + h + "@" + s + ": " + r.summary());
            compared++;
        }
        assertTrue(compared >= 6, "pause cases in the oracle: " + compared);
    }

    /** {@code openmason-engine/src/test/resources/<relative>}, found from the working directory or a parent. */
    private static Path engineFixture(String relative) {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("openmason-engine/src/test/resources").resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("engine fixture " + relative + " not found from " + Path.of("").toAbsolutePath()
            + ": run from the repository or a module directory");
    }

    @Test
    void hitRegionsAndActionsComeFromTheLegacyInputCode() {
        FidelityCase c = new FidelityCase("pause", "field-online", FidelityCase.STANDARD.get(3));
        MigrationGate.Capture cap = new LegacyPauseCapture().render(c);
        assertEquals(cap.rects().keySet().stream().filter(k -> !k.equals("panel")).collect(java.util.stream.Collectors.toSet()),
            cap.hits().keySet(), "every drawn button is clickable");
        assertEquals(6, cap.hits().size(), "online: six buttons");
        for (String b : cap.hits().keySet()) {
            float[] drawn = cap.rects().get(b);
            float[] hit = cap.hits().get(b);
            for (int i = 0; i < 4; i++) {
                assertEquals(drawn[i], hit[i], 1e-3, b + " hit region axis " + i);
            }
        }
        assertEquals("stonebreak:network.resync", cap.actions().get("resync"));
        assertEquals("stonebreak:screen.pause.quit", cap.actions().get("quit"));
        assertFalse(LegacyPauseCapture.actions(false).containsKey("resync"));
    }
}
