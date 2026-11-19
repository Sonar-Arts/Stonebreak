package com.stonebreak.world.generation.diffusion;

import com.stonebreak.world.generation.ClimateLayout;
import com.stonebreak.world.generation.HeightCurveVersion;
import com.stonebreak.world.generation.NativeCubeGenerator.HeightCurveSpec;
import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 1:1 preset (15 m blocks, 2026-11-19): what the handshake sends TGMPipe for it - a fractional {@code downscale}
 * (two blocks per 30 m model pixel), its own capped curve (the user's CAP decision) - and that adding a third preset
 * keeps every handshake world at a unique index without changing the 1:4 / 1:2 configs.
 */
class OneToOneScaleTest {

    private static final TerrainScalePreset ONE = TerrainScalePreset.ONE_TO_ONE;

    @Test
    void oneToOneSendsHalfAPixelPerBlockAndItsOwnCurve() {
        String v2 = TerrainScale.worldConfigJson(ONE, ClimateLayout.BELTS, HeightCurveVersion.V2);
        assertTrue(v2.contains("\"horizontal_m\":15.0"), v2);
        assertTrue(v2.contains("\"downscale\":0.5"), v2);
        assertTrue(v2.contains("\"lowland_m_per_block\":4.0") && v2.contains("\"midland_m_per_block\":6.85")
                && v2.contains("\"highland_m_per_block\":32.0"), v2);
        assertTrue(v2.contains("\"highland_base_m\":2600.0") && v2.contains("\"highland_blend_m\":1000.0"), v2);
        // v2 sea (round 2): a quarter of 1:4's shallows / shelf rates, deep ocean 48 (trench headroom).
        assertTrue(v2.contains("\"ocean_m_per_block\":48.0") && v2.contains("\"shelf_m_per_block\":1.5")
                && v2.contains("\"shallow_m_per_block\":1.0"), v2);
        assertEquals(0, TerrainScale.downscale(ONE));
        assertEquals(0.5, TerrainScale.pixelsPerBlock(ONE));
        // Standard worlds (native curve) read the v1 view: ocean 12 and the same knots.
        assertArrayEquals(new double[]{12.0, 4.0, 6.85, 32.0}, ONE.curveRates());
        assertArrayEquals(new double[]{600.0, 2600.0, 60.0, 150.0, 1000.0}, ONE.curveKnots());
        HeightCurveSpec v1 = ONE.heightCurve(HeightCurveVersion.V1);
        assertEquals(0.0, v1.shelfMetresPerBlock());
        assertArrayEquals(ONE.curveKnots(), v1.knots());
    }

    @Test
    void theOtherPresetsKeepWholePixelDownscalesAndTheSharedKnots() {
        assertTrue(TerrainScale.worldConfigJson(TerrainScalePreset.ONE_TO_FOUR).contains("\"downscale\":2,"));
        assertTrue(TerrainScale.worldConfigJson(TerrainScalePreset.ONE_TO_TWO).contains("\"downscale\":1,"));
        double[] shared = {600.0, 2000.0, 60.0, 150.0, 300.0};
        assertArrayEquals(shared, TerrainScalePreset.ONE_TO_FOUR.curveKnots());
        assertArrayEquals(shared, TerrainScalePreset.ONE_TO_TWO.curveKnots());
        assertArrayEquals(shared, TerrainScalePreset.ONE_TO_TWO.heightCurve(HeightCurveVersion.V1).knots());
        // v2 narrows the shore blend only (the shallows hold their rate up to the waterline).
        double[] v2 = TerrainScalePreset.ONE_TO_TWO.heightCurve(HeightCurveVersion.V2).knots();
        assertEquals(TerrainScalePreset.ONE_TO_TWO.seaV2().shoreBlendM(), v2[2]);
        v2[2] = shared[2];
        assertArrayEquals(shared, v2);
    }

    @Test
    void everyHandshakeWorldHasItsOwnIndex() {
        Set<Integer> seen = new HashSet<>();
        for (HeightCurveVersion c : HeightCurveVersion.values()) {
            for (ClimateLayout l : ClimateLayout.values()) {
                for (TerrainScalePreset p : TerrainScalePreset.values()) {
                    int i = TerrainScale.worldIndex(l, p, c);
                    assertTrue(seen.add(i), "index " + i + " twice");
                }
            }
        }
        int worlds = HeightCurveVersion.values().length * ClimateLayout.values().length
                * TerrainScalePreset.values().length;
        assertEquals(worlds, seen.size());
        assertEquals(worlds - 1, seen.stream().mapToInt(Integer::intValue).max().orElseThrow());
        String json = TerrainScale.handshakeJson();
        assertEquals(worlds, json.split("\"world_height\"").length - 1);
    }
}
