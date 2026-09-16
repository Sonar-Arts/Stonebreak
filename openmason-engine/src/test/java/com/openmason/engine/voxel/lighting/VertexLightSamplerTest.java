package com.openmason.engine.voxel.lighting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The per-vertex brightness math every chunk mesh bakes in. The numbers here are the shipped
 * look — the AO ramp (1.0 / 0.87 / 0.74 / 0.61), the Minecraft both-sides-force-full-corner
 * rule, the 2x2 sky-column average, and the contract that unloaded neighbors are dropped from
 * the average rather than counted dark (which would draw a shadow seam along every chunk
 * border still streaming in).
 */
class VertexLightSamplerTest {

    private static final int TOP = 0;
    private static final float EPS = 1e-5f;

    /** Columns and solids as independent maps; anything unset is unloaded / air. */
    private static final class FakeWorld implements LightingContext {
        final Map<Long, Integer> columns = new HashMap<>();
        final Set<Long> solids = new HashSet<>();

        void column(int x, int z, int skyStartsAt) {
            columns.put(pack(x, z), skyStartsAt);
        }

        void solid(int x, int y, int z) {
            solids.add(pack3(x, y, z));
        }

        @Override
        public int getColumnHeight(int worldX, int worldZ) {
            return columns.getOrDefault(pack(worldX, worldZ), -1);
        }

        @Override
        public boolean isSolidAt(int worldX, int worldY, int worldZ) {
            return solids.contains(pack3(worldX, worldY, worldZ));
        }

        private static long pack(int x, int z) {
            return ((long) x << 32) | (z & 0xFFFFFFFFL);
        }

        private static long pack3(int x, int y, int z) {
            return ((long) (x + 512)) + ((long) (y + 512) << 20) + ((long) (z + 512) << 40);
        }
    }

    private final FakeWorld world = new FakeWorld();

    @AfterEach
    void restoreSmoothLighting() {
        VertexLightSampler.setSmoothLightingEnabled(true);
    }

    /** Lights the 2x2 columns a top-face vertex at (0, 10, 0) averages over. */
    private void litNeighborhood() {
        for (int x = -1; x <= 0; x++) {
            for (int z = -1; z <= 0; z++) {
                world.column(x, z, 5); // sky begins well below the vertex
            }
        }
    }

    @Test
    void noContextMeansFullBright() {
        assertEquals(1.0f, VertexLightSampler.sampleCombined(null, 0, 10, 0, TOP), EPS);
    }

    @Test
    void anOpenSkyVertexIsFullBright() {
        litNeighborhood();

        assertEquals(1.0f, VertexLightSampler.sampleCombined(world, 0, 10, 0, TOP), EPS);
    }

    @Test
    void aVertexUnderPartialCoverAveragesItsFourColumns() {
        world.column(-1, -1, 5);
        world.column(0, -1, 5);
        world.column(-1, 0, 20); // roofed above the vertex
        world.column(0, 0, 20);

        assertEquals(0.5f, VertexLightSampler.sampleCombined(world, 0, 10, 0, TOP), EPS);
    }

    @Test
    void unloadedColumnsAreDroppedNotCountedDark() {
        world.column(0, 0, 5); // the other three columns of the 2x2 are unloaded

        assertEquals(1.0f, VertexLightSampler.sampleCombined(world, 0, 10, 0, TOP), EPS,
                "a lit vertex beside a streaming chunk must not pick up a shadow seam");
    }

    @Test
    void aFullyUnloadedNeighborhoodDefaultsToBright() {
        assertEquals(1.0f, VertexLightSampler.sampleCombined(world, 0, 10, 0, TOP), EPS);
    }

    // ── Ambient occlusion (top face: sides at (-1,10,0) and (0,10,-1)) ───────

    @Test
    void oneSolidNeighborDimsOneAoStep() {
        litNeighborhood();
        world.solid(-1, 10, 0);

        assertEquals(0.87f, VertexLightSampler.sampleCombined(world, 0, 10, 0, TOP), EPS);
    }

    @Test
    void aSolidCornerAloneAlsoDimsOneStep() {
        litNeighborhood();
        world.solid(-1, 10, -1);

        assertEquals(0.87f, VertexLightSampler.sampleCombined(world, 0, 10, 0, TOP), EPS);
    }

    @Test
    void bothSidesSolidForceTheFullCreaseEvenWithAnOpenCorner() {
        litNeighborhood();
        world.solid(-1, 10, 0);
        world.solid(0, 10, -1);

        assertEquals(0.61f, VertexLightSampler.sampleCombined(world, 0, 10, 0, TOP), EPS,
                "the Minecraft rule: two touching sides read as a closed crease");
    }

    @Test
    void skyAndAoMultiply() {
        world.column(-1, -1, 5);
        world.column(0, -1, 5);
        world.column(-1, 0, 20);
        world.column(0, 0, 20);
        world.solid(-1, 10, 0);

        assertEquals(0.5f * 0.87f, VertexLightSampler.sampleCombined(world, 0, 10, 0, TOP), EPS);
    }

    // ── Flat lighting ────────────────────────────────────────────────────────

    @Test
    void flatLightingTakesOneSampleAndSkipsAoEntirely() {
        VertexLightSampler.setSmoothLightingEnabled(false);
        world.column(0, 0, 5);          // the single sampled column: lit
        world.column(-1, 0, 20);        // would darken the smooth average — must be ignored
        world.solid(-1, 10, 0);         // would be an AO neighbor — must be ignored

        assertEquals(1.0f, VertexLightSampler.sampleCombined(world, 0, 10, 0, TOP), EPS);
    }

    @Test
    void flatLightingUnderARoofIsFullyShaded() {
        VertexLightSampler.setSmoothLightingEnabled(false);
        world.column(0, 0, 20);

        assertEquals(0.0f, VertexLightSampler.sampleCombined(world, 0, 10, 0, TOP), EPS);
    }

    // ── Point probe (first-person geometry) ──────────────────────────────────

    @Test
    void thePointProbeAnswersBrightAboveGroundHalfBelowAndBrightWhenUnloaded() {
        world.column(3, 3, 10);

        assertEquals(1.0f, VertexLightSampler.samplePointSky(world, 3.5f, 12.0f, 3.5f), EPS);
        assertEquals(0.5f, VertexLightSampler.samplePointSky(world, 3.5f, 6.0f, 3.5f), EPS);
        assertEquals(1.0f, VertexLightSampler.samplePointSky(world, 99.5f, 6.0f, 99.5f), EPS,
                "an unloaded column must not black out the player's arms");
    }

    // ── Shaped (stair) geometry: fractional vertices + own-cell exclusion (#224) ──

    private static final int NORTH = 2;
    private static final int EAST = 4;

    /**
     * Issue #224's layout: a north-facing stair at (0,10,0) on flat ground (sky at 10),
     * its own column therefore reads 11. The upper riser is the interior face at
     * z = 0.5 spanning y 10.5..11.
     */
    private void stairOnOpenGround() {
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                world.column(x, z, 10);
            }
        }
        world.column(0, 0, 11);
        world.solid(0, 10, 0);
    }

    @Test
    void aStairsUpperRiserInOpenDaylightIsFullBright() {
        stairOnOpenGround();

        // Bottom and top vertices of the riser, mid-span in x.
        assertEquals(1.0f, VertexLightSampler.sampleCombined(world, 0.5f, 10.5f, 0.5f, NORTH, 0, 10, 0), EPS,
                "the stair must not shade its own interior riser");
        assertEquals(1.0f, VertexLightSampler.sampleCombined(world, 0.5f, 11.0f, 0.5f, NORTH, 0, 10, 0), EPS);
    }

    @Test
    void theRoundingSamplerWouldHaveShadedTheSameRiser() {
        stairOnOpenGround();

        // Documents the bug the geometry-aware variant fixes: rounding z=0.5 → 1 puts
        // the north face's air side inside the stair's own cell, which then reads as
        // overhead cover (one of the four sky samples) and as an AO neighbour.
        float legacy = VertexLightSampler.sampleCombined(world, 0.5f, 10.5f, 0.5f, NORTH);
        assertEquals(0.75f * 0.87f, legacy, EPS);
    }

    @Test
    void aLowerTreadInOpenDaylightIsFullBright() {
        stairOnOpenGround();

        assertEquals(1.0f, VertexLightSampler.sampleCombined(world, 0.5f, 10.5f, 0.25f, TOP, 0, 10, 0), EPS);
    }

    // ── Stacked formations: interior faces of a lower cell (#stacked-cacti) ──

    /**
     * Two stacked shaped blocks on a sand floor: lower cell (0,10,0), upper (0,11,0),
     * sand below at (0,9,0). The formation raises its own column to 12, so the shipped
     * topmost-occluder branch (h == ownY+1) no longer fires for the lower cell — and
     * its interior side faces (sides inside the cell along the normal) collapse every
     * tangent axis to the own column, which is genuinely occluded by the block above.
     * Without the interior-face openness the lower cell samples sky 0 and renders
     * uniformly dark, seamed exactly at the cell boundary.
     */
    private void stackedFormation() {
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                world.column(x, z, 10);
            }
        }
        world.column(0, 0, 12); // the stacked formation raises its own column
        world.solid(0, 9, 0);   // the sand floor under the formation
        world.solid(0, 10, 0);  // lower cell
        world.solid(0, 11, 0);  // upper cell
    }

    @Test
    void aStackedFormationsLowerCellMatchesTheUpperCellsBoundaryBrightness() {
        stackedFormation();

        // Interior east face at x = 0.9375 (0.0625 inside the cell along +x): the
        // tangent axes collapse to the own column, so the interior-face openness
        // makes the emitting row count as under sky. Both edges of the lower cell's
        // side face sample one lit own-row column and one genuinely-shaded column
        // (the sand below / the block above) → sky 0.5; AO dims one step (the sand
        // below the bottom edge, the block above the top edge) → 0.435.
        assertEquals(0.5f * 0.87f, VertexLightSampler.sampleCombined(world, 0.9375f, 10.0f, 0.5f, EAST, 0, 10, 0), EPS,
                "the lower cell must not sample sky 0 through its own occluded column");
        assertEquals(0.5f * 0.87f, VertexLightSampler.sampleCombined(world, 0.9375f, 11.0f, 0.5f, EAST, 0, 10, 0), EPS);
    }

    @Test
    void theUpperCellOfAStackedFormationKeepsItsShippedLook() {
        stackedFormation();

        // Upper cell (ownY=11, its column h=12 == ownY+1): the topmost-occluder
        // branch fires first — unchanged by the fix. Its bottom edge samples the
        // lower block as genuinely shaded → 0.5 × 0.87 — exactly the lower cell's
        // boundary brightness, so the seam at the cell boundary is gone. Top edge
        // is genuinely under sky, no AO → 1.0.
        assertEquals(0.5f * 0.87f, VertexLightSampler.sampleCombined(world, 0.9375f, 11.0f, 0.5f, EAST, 0, 11, 0), EPS,
                "the upper cell's bottom edge must match the lower cell's top edge");
        assertEquals(1.0f, VertexLightSampler.sampleCombined(world, 0.9375f, 12.0f, 0.5f, EAST, 0, 11, 0), EPS);
    }

    @Test
    void aOneTallFormationIsUnchanged() {
        // Same layout without the upper cell: the column reads 11 == ownY+1, so the
        // shipped topmost-occluder branch fires — the gradient is the shipped look.
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                world.column(x, z, 10);
            }
        }
        world.column(0, 0, 11);
        world.solid(0, 9, 0);   // the sand floor
        world.solid(0, 10, 0);

        assertEquals(0.5f * 0.87f, VertexLightSampler.sampleCombined(world, 0.9375f, 10.0f, 0.5f, EAST, 0, 10, 0), EPS);
        assertEquals(1.0f, VertexLightSampler.sampleCombined(world, 0.9375f, 11.0f, 0.5f, EAST, 0, 10, 0), EPS);
    }

    @Test
    void aFlushFaceOfAStackedFormationStillSamplesLikeACube() {
        stackedFormation();

        // The lower cell's flush top face sits on the y=11 boundary (not interior):
        // the interior-plane key does not fire, so the shipped behaviour is untouched.
        // A mid-span vertex's tangent axes collapse to the own column — genuinely
        // occluded by the block above — so it samples sky 0; in game that face is
        // culled by the stacked-face cull policy, so its lighting never shows.
        assertEquals(0.0f, VertexLightSampler.sampleCombined(world, 0.5f, 11.0f, 0.5f, TOP, 0, 10, 0), EPS);
    }

    @Test
    void aWallBesideTheRiserStillCreasesItsEndVertex() {
        stairOnOpenGround();
        world.column(-1, 0, 12);
        world.solid(-1, 10, 0);
        world.solid(-1, 11, 0);

        // Left-end bottom vertex: columns (-1,0) roofed + (0,0) open → sky 0.5; the wall
        // is the only solid air-side cell (own cell excluded) → one AO step.
        assertEquals(0.5f * 0.87f, VertexLightSampler.sampleCombined(world, 0.0f, 10.5f, 0.5f, NORTH, 0, 10, 0), EPS);
        // Mid-span is untouched by the wall.
        assertEquals(1.0f, VertexLightSampler.sampleCombined(world, 0.5f, 10.5f, 0.5f, NORTH, 0, 10, 0), EPS);
    }

    @Test
    void integralCornersMatchTheRoundingSamplerOnEveryFace() {
        litNeighborhood();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                world.column(x, z, (x + z) % 2 == 0 ? 5 : 20);
            }
        }
        world.solid(-1, 10, 0);
        world.solid(0, 9, -1);
        world.solid(-1, 9, -1);
        world.solid(1, 10, 1);

        for (int face = 0; face < 6; face++) {
            for (int x = -1; x <= 1; x++) {
                for (int y = 9; y <= 11; y++) {
                    for (int z = -1; z <= 1; z++) {
                        float legacy = VertexLightSampler.sampleCombined(world, x, y, z, face);
                        // Own cell far away so it never intersects the sampled neighbourhood.
                        float shaped = VertexLightSampler.sampleCombined(world, x, y, z, face, 100, 100, 100);
                        assertEquals(legacy, shaped, EPS,
                                "face " + face + " at (" + x + "," + y + "," + z + ")");
                    }
                }
            }
        }
    }
}
