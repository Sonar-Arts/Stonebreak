package com.stonebreak.world.generation.diffusion;

import com.stonebreak.world.generation.ClimateLayout;
import com.stonebreak.world.generation.HeightCurveVersion;
import com.stonebreak.world.generation.NativeCubeGenerator.HeightCurveSpec;
import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The handshake's world configs (one per height curve, climate layout and terrain scale, protocol v2) must name exactly the fields of TGMPipe's
 * {@code WorldConfig} — the service refuses a missing or unknown key, so drift would only
 * surface as a failed world load.
 */
class TerrainScaleTest {

    private static final Pattern KEY = Pattern.compile("\"(\\w+)\":");
    private static final Pattern FIELD = Pattern.compile("(?m)^    (\\w+): (?:int|float|str)(?: = [^\\n]*)?$");

    @Test
    void worldConfigKeysMatchThePythonWorldConfig() throws IOException {
        Path python = Stream.of("..", ".")
                .map(root -> Path.of(root, "Models", "DaedalusTGM-Exp", "terrain_slm", "world", "world_config.py"))
                .filter(Files::isRegularFile)
                .findFirst().orElse(null);
        assumeTrue(python != null, "Models/DaedalusTGM-Exp is not checked out next to this module");

        Set<String> fields = new LinkedHashSet<>();
        Matcher f = FIELD.matcher(Files.readString(python));
        while (f.find()) fields.add(f.group(1));

        for (HeightCurveVersion curve : HeightCurveVersion.values()) {
            for (ClimateLayout layout : ClimateLayout.values()) {
                for (TerrainScalePreset preset : TerrainScalePreset.values()) {
                    Set<String> sent = new LinkedHashSet<>();
                    Matcher k = KEY.matcher(TerrainScale.worldConfigJson(preset, layout, curve));
                    while (k.find()) sent.add(k.group(1));
                    assertEquals(fields, sent, curve + " " + layout + " " + preset.name());
                }
            }
        }
    }

    @Test
    void handshakeListsOneWorldPerCurveLayoutAndScaleAtItsIndex() {
        String json = TerrainScale.handshakeJson();
        StringBuilder expected = new StringBuilder("{\"worlds\":[");
        int index = 0;
        for (HeightCurveVersion c : HeightCurveVersion.values()) {
            for (ClimateLayout l : ClimateLayout.values()) {
                for (TerrainScalePreset p : TerrainScalePreset.values()) {
                    assertEquals(index++, TerrainScale.worldIndex(l, p, c), c + " " + l + " " + p);
                    expected.append(TerrainScale.worldConfigJson(p, l, c)).append(',');
                }
            }
        }
        expected.setLength(expected.length() - 1);
        assertEquals(expected.append("]}").toString(), json);
    }

    @Test
    void v1WorldsKeepThePreCurveIndices() {
        int presets = TerrainScalePreset.values().length;
        for (ClimateLayout l : ClimateLayout.values()) {
            for (TerrainScalePreset p : TerrainScalePreset.values()) {
                // The index a v1 (saved) world had before curve versions existed.
                assertEquals(l.ordinal() * presets + p.ordinal(), TerrainScale.worldIndex(l, p, HeightCurveVersion.V1));
            }
        }
        for (TerrainScalePreset p : TerrainScalePreset.values()) {
            assertEquals(p.ordinal(), TerrainScale.worldIndex(ClimateLayout.BELTS, p, HeightCurveVersion.V1));
            // No curve named = the current curve (new worlds, previews).
            assertEquals(TerrainScale.worldIndex(null, p, HeightCurveVersion.CURRENT), TerrainScale.worldIndex(null, p));
            assertEquals(TerrainScale.worldConfigJson(p), TerrainScale.worldConfigJson(p, ClimateLayout.BELTS));
            assertEquals(TerrainScale.worldConfigJson(p),
                    TerrainScale.worldConfigJson(p, ClimateLayout.BELTS, HeightCurveVersion.CURRENT));
        }
    }

    @Test
    void eachWorldCarriesItsHeightCurve() {
        for (TerrainScalePreset p : TerrainScalePreset.values()) {
            String v1 = TerrainScale.worldConfigJson(p, ClimateLayout.BELTS, HeightCurveVersion.V1);
            assertTrue(v1.contains("\"curve_version\":1,") && v1.contains("\"shelf_m_per_block\":0.0,"), v1);
            String v2 = TerrainScale.worldConfigJson(p, ClimateLayout.BELTS, HeightCurveVersion.V2);
            assertTrue(v2.contains("\"curve_version\":2,"), v2);
            assertFalse(v2.contains("\"shelf_m_per_block\":0.0,"), v2);
            // v1 is the Standard generator's curve; v2 adds the shelf band and keeps the land rates and the knots.
            HeightCurveSpec a = p.heightCurve(HeightCurveVersion.V1), b = p.heightCurve(HeightCurveVersion.V2);
            assertArrayEquals(p.curveRates(), a.rates());
            assertArrayEquals(java.util.Arrays.copyOfRange(p.curveRates(), 1, 4),
                    java.util.Arrays.copyOfRange(b.rates(), 1, 4));
            assertTrue(b.rates()[0] > b.shelfMetresPerBlock(), "the shelf is finer than the deep ocean");
            double[] kb = b.knots();
            assertEquals(p.seaV2().shoreBlendM(), kb[2]);                 // v2 narrows the shore blend only
            kb[2] = p.curveKnots()[2];
            assertArrayEquals(p.curveKnots(), kb);
            assertTrue(b.shallowMetresPerBlock() > 0.0 && b.shallowMetresPerBlock() < b.shelfMetresPerBlock());
            assertEquals(0.0, a.shallowMetresPerBlock());
            assertTrue(b.shallowBaseM() + b.shallowBlendM() <= -b.knots()[2]);   // v2's own (narrow) shore blend
            assertTrue(b.shallowBaseM() - b.shallowBlendM() >= b.shelfBaseM() + b.shelfBlendM());
            assertEquals(0.0, a.shelfMetresPerBlock());
            assertTrue(b.shelfMetresPerBlock() > 0.0 && b.shelfMetresPerBlock() < p.curveRates()[0]);
            // Blend windows must not overlap (the service rejects it): shelf blend ends before the shore blend.
            assertTrue(b.shelfBaseM() + b.shelfBlendM() <= -b.knots()[2]);
        }
        // Same exaggeration at 1:2: half the 1:4 shelf rate, like every other rate.
        assertEquals(TerrainScalePreset.ONE_TO_FOUR.heightCurve(HeightCurveVersion.V2).shelfMetresPerBlock() / 2.0,
                TerrainScalePreset.ONE_TO_TWO.heightCurve(HeightCurveVersion.V2).shelfMetresPerBlock());
    }

    @Test
    void standardCurveIsUnchanged() {
        // TerrainScale.curveRates() feeds the distilled Standard generator: worlds of every curve version keep it.
        assertArrayEquals(new double[]{48.0, 16.0, 24.0, 38.0}, TerrainScale.curveRates());
        assertArrayEquals(new double[]{600.0, 2000.0, 60.0, 150.0, 300.0}, TerrainScale.curveKnots());
        assertArrayEquals(new double[]{48.0, 16.0, 24.0, 38.0}, TerrainScalePreset.ONE_TO_FOUR.curveRates());
        assertArrayEquals(new double[]{24.0, 8.0, 12.0, 19.0}, TerrainScalePreset.ONE_TO_TWO.curveRates());
    }

    @Test
    void eachWorldCarriesItsClimateLayout() {
        for (ClimateLayout l : ClimateLayout.values()) {
            assertTrue(TerrainScale.worldConfigJson(TerrainScalePreset.ONE_TO_TWO, l)
                    .contains(",\"climate_layout\":\"" + l.value() + "\","), l.name());
        }
    }

    @Test
    void eachWorldCarriesItsScale() {
        String quarter = TerrainScale.worldConfigJson(TerrainScalePreset.ONE_TO_FOUR);
        assertTrue(quarter.startsWith("{\"world_height\":"));
        assertTrue(quarter.contains("\"horizontal_m\":60.0,") && quarter.contains("\"downscale\":2,"));
        assertTrue(quarter.contains("\"lowland_m_per_block\":16.0,"));
        assertTrue(quarter.contains("\"tile_size\":" + TerrainScale.TILE_SIZE_BLOCKS + ","));
        String half = TerrainScale.worldConfigJson(TerrainScalePreset.ONE_TO_TWO);
        assertTrue(half.contains("\"horizontal_m\":30.0,") && half.contains("\"downscale\":1,"));
        assertTrue(half.contains("\"lowland_m_per_block\":8.0,") && half.contains("\"highland_m_per_block\":19.0,"));
        assertEquals(2, TerrainScale.downscale(TerrainScalePreset.ONE_TO_FOUR));
        assertEquals(1, TerrainScale.downscale(TerrainScalePreset.ONE_TO_TWO));
    }
}
