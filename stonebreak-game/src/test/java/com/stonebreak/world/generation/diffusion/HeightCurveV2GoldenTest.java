package com.stonebreak.world.generation.diffusion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.stonebreak.world.generation.ClimateLayout;
import com.stonebreak.world.generation.HeightCurveVersion;
import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;
import org.junit.jupiter.api.Test;

/**
 * FROZEN height curve v2 (2026-11-19 release freeze, EXP-2 release exp2-r3). Saved worlds created on curve v2 depend
 * on these exact handshake numbers: a world must never get different block heights for land it has already explored.
 * Any later tuning is a NEW {@link HeightCurveVersion} (v3), never an edit of v2. If this test fails, revert the curve
 * change or add v3. The Python side pins the same configs and their block heights
 * ({@code Models/DaedalusTGM-Exp/tests/test_height_curve_v2_golden.py}).
 */
class HeightCurveV2GoldenTest {

    private static final String ONE_TO_FOUR = "{\"world_height\":640,\"min_y\":-384,\"sea_level\":64,\"horizontal_m\":60.0,"
        + "\"downscale\":2,\"tile_size\":256,\"ocean_m_per_block\":32.0,\"lowland_m_per_block\":16.0,"
        + "\"midland_m_per_block\":24.0,\"highland_m_per_block\":38.0,\"lowland_top_m\":600.0,\"highland_base_m\":2000.0,"
        + "\"shore_blend_m\":10.0,\"midland_blend_m\":150.0,\"highland_blend_m\":300.0,\"climate_layout\":\"belts\","
        + "\"curve_version\":2,\"shelf_m_per_block\":6.0,\"shelf_base_m\":-300.0,\"shelf_blend_m\":150.0,"
        + "\"shallow_m_per_block\":4.0,\"shallow_base_m\":-24.0,\"shallow_blend_m\":12.0}";

    private static final String ONE_TO_TWO = "{\"world_height\":640,\"min_y\":-384,\"sea_level\":64,\"horizontal_m\":30.0,"
        + "\"downscale\":1,\"tile_size\":256,\"ocean_m_per_block\":32.0,\"lowland_m_per_block\":8.0,"
        + "\"midland_m_per_block\":12.0,\"highland_m_per_block\":19.0,\"lowland_top_m\":600.0,\"highland_base_m\":2000.0,"
        + "\"shore_blend_m\":10.0,\"midland_blend_m\":150.0,\"highland_blend_m\":300.0,\"climate_layout\":\"belts\","
        + "\"curve_version\":2,\"shelf_m_per_block\":3.0,\"shelf_base_m\":-300.0,\"shelf_blend_m\":150.0,"
        + "\"shallow_m_per_block\":2.0,\"shallow_base_m\":-24.0,\"shallow_blend_m\":12.0}";

    private static final String ONE_TO_ONE = "{\"world_height\":640,\"min_y\":-384,\"sea_level\":64,\"horizontal_m\":15.0,"
        + "\"downscale\":0.5,\"tile_size\":256,\"ocean_m_per_block\":48.0,\"lowland_m_per_block\":4.0,"
        + "\"midland_m_per_block\":6.85,\"highland_m_per_block\":32.0,\"lowland_top_m\":600.0,\"highland_base_m\":2600.0,"
        + "\"shore_blend_m\":10.0,\"midland_blend_m\":150.0,\"highland_blend_m\":1000.0,\"climate_layout\":\"belts\","
        + "\"curve_version\":2,\"shelf_m_per_block\":1.5,\"shelf_base_m\":-300.0,\"shelf_blend_m\":150.0,"
        + "\"shallow_m_per_block\":1.0,\"shallow_base_m\":-24.0,\"shallow_blend_m\":12.0}";

    @Test
    void curveV2HandshakeIsFrozenAtEveryScale() {
        assertEquals(ONE_TO_FOUR, TerrainScale.worldConfigJson(TerrainScalePreset.ONE_TO_FOUR, ClimateLayout.BELTS, HeightCurveVersion.V2));
        assertEquals(ONE_TO_TWO, TerrainScale.worldConfigJson(TerrainScalePreset.ONE_TO_TWO, ClimateLayout.BELTS, HeightCurveVersion.V2));
        assertEquals(ONE_TO_ONE, TerrainScale.worldConfigJson(TerrainScalePreset.ONE_TO_ONE, ClimateLayout.BELTS, HeightCurveVersion.V2));
    }
}
