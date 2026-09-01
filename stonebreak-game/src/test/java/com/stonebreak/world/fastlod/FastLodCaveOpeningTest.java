package com.stonebreak.world.fastlod;

import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.openmason.engine.voxel.mms.mmsCore.MmsVertexFormat;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.textures.BlockTextureArray;
import com.stonebreak.world.generation.TerrainGenerationSystem;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The cave-mouth notch: a recessed pit the mesher cuts into a coarse cell where the cell's
 * footprint contains an opening its single height probe missed.
 *
 * <p>Why the channel exists at all. LOD heights are one representative probe per cell, so an
 * opening only survives coarsening if the probe lands inside it — measured hit rates were 79%
 * at L1, 54% at L2, 33% at L3 and 15% at L4. Ravines are long and wide enough to be hit
 * regardless, which is why the distance rings showed ravines and essentially nothing else; a
 * worm mouth or a sinkhole a few blocks across was sampled away. Aggregating the heights
 * instead is the tempting fix and it is wrong: minimum-over-cell reports a mean carve of 32
 * blocks at L4 against a true mean of 2.8, gouging trenches through open ground. So the
 * surface keeps its point sample and the opening rides beside it as its own channel.
 *
 * <p>The notch is additive geometry inside one cell — the same shape of change as a tree
 * silhouette. Nothing here may move the cell's surface height, its skirts, or its water sheet.
 */
class FastLodCaveOpeningTest {

    private static final float EPS = 1e-4f;
    private static final int GROUND = 300;

    private FastLodMesher mesher;

    @BeforeEach
    void setUp() {
        MmsVertexFormat.override(MmsVertexFormat.LEGACY40);
        BlockTextureArray textures = mock(BlockTextureArray.class);
        when(textures.getBlockFaceLayer(any(), anyInt())).thenReturn(7);
        mesher = new FastLodMesher(textures);
    }

    @AfterEach
    void restoreFormat() {
        MmsVertexFormat.override(MmsVertexFormat.DEFAULT);
    }

    /** Flat node at {@link #GROUND}, dry, with no opening channel unless one is supplied. */
    private static FastLodChunkData flat(FastLodLevel level, int[] openFloor, byte[] openCover) {
        int[] heights = new int[level.heightCount()];
        Arrays.fill(heights, GROUND);
        int[] water = new int[level.cellCount()];
        Arrays.fill(water, TerrainTile.NO_WATER);
        BlockType[] surface = new BlockType[level.cellCount()];
        Arrays.fill(surface, BlockType.GRASS);
        return new FastLodChunkData(FastLodKey.of(level, 0, 0), heights, water, surface, null,
                openFloor, openCover);
    }

    private static int[] noOpenings(FastLodLevel level) {
        int[] f = new int[level.cellCount()];
        Arrays.fill(f, TerrainGenerationSystem.NO_OPENING);
        return f;
    }

    private static int quadCount(MmsMeshData mesh) {
        return mesh.getIndexCount() / 6;
    }

    private static boolean hasVertexAtY(MmsMeshData mesh, float y) {
        float[] pos = mesh.getVertexPositions();
        for (int i = 1; i < pos.length; i += 3) {
            if (Math.abs(pos[i] - y) < EPS) return true;
        }
        return false;
    }

    @Test
    void anOpeningAddsARecessedPitToTheCell() {
        FastLodLevel level = FastLodLevel.L2;
        int[] none = noOpenings(level);
        FastLodMesher.Result plain = mesher.build(flat(level, none, new byte[level.cellCount()]));

        int[] floor = noOpenings(level);
        byte[] cover = new byte[level.cellCount()];
        floor[0] = GROUND - 10;
        cover[0] = (byte) 128;
        FastLodMesher.Result withMouth = mesher.build(flat(level, floor, cover));

        assertEquals(quadCount(plain.mesh()) + 5, quadCount(withMouth.mesh()),
                "one cave mouth is a floor quad plus four walls");
        assertTrue(hasVertexAtY(withMouth.mesh(), GROUND - 10),
                "the pit floor is drawn at the opening floor");
        assertFalse(hasVertexAtY(plain.mesh(), GROUND - 10),
                "and only when there is an opening");
        assertEquals(plain.maxY(), withMouth.maxY(), EPS, "a notch never raises the terrain");
    }

    @Test
    void theCellKeepsItsOwnSurfaceHeight() {
        FastLodLevel level = FastLodLevel.L2;
        int[] floor = noOpenings(level);
        byte[] cover = new byte[level.cellCount()];
        Arrays.fill(floor, GROUND - 12);
        Arrays.fill(cover, (byte) 255);

        FastLodMesher.Result r = mesher.build(flat(level, floor, cover));
        MmsMeshData mesh = r.mesh();
        float[] pos = mesh.getVertexPositions();

        // The notch must not become "lower the cell": the surface is still drawn at GROUND.
        int atGround = 0;
        for (int i = 1; i < pos.length; i += 3) {
            if (Math.abs(pos[i] - GROUND) < EPS) atGround++;
        }
        assertTrue(atGround > 0, "surface quads still sit at the original height");
        assertEquals(GROUND, r.maxY(), EPS);
    }

    /**
     * Coverage sizes the notch, and the cap is load-bearing: a cell that is entirely carved
     * must still keep a rim, or the notch degenerates into lowering the whole cell — which is
     * the min-over-cell behaviour this design exists to avoid.
     */
    @Test
    void aFullyCarvedCellStillKeepsARim() {
        FastLodLevel level = FastLodLevel.L4;   // one 16-block cell, easiest to measure
        int[] floor = noOpenings(level);
        byte[] cover = new byte[level.cellCount()];
        floor[0] = GROUND - 8;
        cover[0] = (byte) 255;                  // 100% of the footprint carved

        MmsMeshData mesh = mesher.build(flat(level, floor, cover)).mesh();
        float[] pos = mesh.getVertexPositions();

        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < pos.length; i += 3) {
            if (Math.abs(pos[i + 1] - (GROUND - 8)) < EPS) {   // notch floor verts only
                minX = Math.min(minX, pos[i]);
                maxX = Math.max(maxX, pos[i]);
            }
        }
        float side = maxX - minX;
        assertTrue(side > 0f, "notch has extent");
        assertTrue(side <= 16f * 0.8f + EPS,
                "notch spans at most 80% of the cell, leaving a rim; got " + side);
        assertTrue(minX > 0f, "notch is inset from the cell's edge, got minX=" + minX);
    }

    /** Deep chasms are clamped: a full-depth ravine inside one cell reads as a needle. */
    @Test
    void notchDepthIsClamped() {
        FastLodLevel level = FastLodLevel.L3;
        int[] floor = noOpenings(level);
        byte[] cover = new byte[level.cellCount()];
        floor[0] = GROUND - 200;
        cover[0] = (byte) 200;

        MmsMeshData mesh = mesher.build(flat(level, floor, cover)).mesh();
        assertTrue(hasVertexAtY(mesh, GROUND - 24),
                "the pit bottoms out at MAX_NOTCH_DEPTH, not at the ravine floor");

        // Node-border foundation walls legitimately run to y=0, so the assertion is that
        // nothing sits BETWEEN the clamp and the foundations.
        float[] pos = mesh.getVertexPositions();
        for (int i = 1; i < pos.length; i += 3) {
            assertTrue(pos[i] <= 0f + EPS || pos[i] >= GROUND - 24 - EPS,
                    "vertex at y=" + pos[i] + " is below the depth clamp");
        }
    }

    /**
     * L0 cells are single columns, so a carve there is already the cell's height. The finest
     * band must therefore emit no notch geometry at all — the LOD nearest the player is
     * exactly what it was before this channel existed.
     */
    @Test
    void theFinestLevelEmitsNoNotches() {
        FastLodChunkData data = flat(FastLodLevel.L0, null, null);
        assertFalse(data.hasOpenings());
        assertEquals(TerrainGenerationSystem.NO_OPENING, data.openingFloorAt(0, 0));
        assertEquals(0, data.openingCoverageAt(0, 0));
        assertNotNull(mesher.build(data).mesh());
    }

    /** A cell whose opening does not reach below the drawn surface has nothing to show. */
    @Test
    void anOpeningAtOrAboveTheDrawnSurfaceIsNotDrawn() {
        FastLodLevel level = FastLodLevel.L2;
        int[] none = noOpenings(level);
        int baseline = quadCount(mesher.build(flat(level, none, new byte[level.cellCount()])).mesh());

        int[] floor = noOpenings(level);
        byte[] cover = new byte[level.cellCount()];
        floor[0] = GROUND;          // level with the surface, not below it
        cover[0] = (byte) 255;

        assertEquals(baseline, quadCount(mesher.build(flat(level, floor, cover)).mesh()),
                "nothing to recess");
    }
}
