package com.stonebreak.world.chunk.api.mightyMesh.mmsIntegration;

import com.openmason.engine.voxel.mms.mmsCore.MmsLodQuadCodec;
import com.openmason.engine.voxel.mms.mmsCore.MmsQuadCodec;
import com.openmason.engine.voxel.mms.mmsCore.MmsWaterQuadCodec;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Ties the pulled-quad Y budgets to {@link WorldConfiguration#WORLD_HEIGHT}.
 *
 * <p>{@code PulledQuadWorldHeightTest} lives in openmason-engine, which cannot
 * see {@code WorldConfiguration}, so it pins the codecs against a hardcoded
 * 1023. That leaves the actual invariant — "the codecs reach the top of THIS
 * world" — unguarded, and its failure mode is silent: {@code FastLodMesher}
 * simply {@code return}s on an unrepresentable quad, so distant terrain
 * disappears with a clean compile and no log line. Raising WORLD_HEIGHT past
 * the budget must fail here rather than in-game.
 *
 * <p>WORLD_HEIGHT 1024 fits exactly, with zero headroom: whole-block Y is 10
 * bits (0..1023) in QUAD16/WATERQUAD16, and LODQUAD16 carries half blocks in 11
 * bits (0..2047 = y 1023.5). A taller world needs the bit budget re-cut first —
 * see the codec javadocs and their three GLSL mirrors (world.vert, water.vert,
 * and the shader embedded in ShadowMapRenderer).
 */
class PulledQuadCodecFitsWorldHeightTest {

    private static final int TOP = WorldConfiguration.WORLD_HEIGHT - 1;

    @Test
    void wholeBlockQuadReachesTheTopOfTheWorld() {
        int w0 = MmsQuadCodec.word0(0, TOP, 0, 0);
        assertEquals(TOP, MmsQuadCodec.y(w0),
            "QUAD16 cannot address y=" + TOP + " at WORLD_HEIGHT "
                + WorldConfiguration.WORLD_HEIGHT);
    }

    @Test
    void waterQuadReachesTheTopOfTheWorld() {
        int w0 = MmsWaterQuadCodec.word0(0, TOP, 0, 0, false, false, false);
        assertEquals(TOP, MmsWaterQuadCodec.y(w0),
            "WATERQUAD16 cannot address y=" + TOP + " at WORLD_HEIGHT "
                + WorldConfiguration.WORLD_HEIGHT);
    }

    @Test
    void lodQuadReachesTheTopOfTheWorldInHalfBlocks() {
        // FastLodMesher.record() rounds to half blocks: yHalf = round(y * 2).
        int w0 = MmsLodQuadCodec.word0(0, 0, TOP * 2, 0);
        assertEquals((float) TOP, MmsLodQuadCodec.y(w0),
            "LODQUAD16 cannot address y=" + TOP + " at WORLD_HEIGHT "
                + WorldConfiguration.WORLD_HEIGHT);
    }

    @Test
    void lodFoundationWallSpansTheWholeColumn() {
        // A foundation wall runs from the cell top down to y=0, so its height in
        // half blocks reaches 2*TOP — the widest h the LOD writer ever emits.
        int w1 = MmsLodQuadCodec.word1(1, TOP * 2, 0, false, false, false);
        assertEquals((float) TOP, MmsLodQuadCodec.height(w1),
            "LODQUAD16 h field cannot span a full " + WorldConfiguration.WORLD_HEIGHT
                + "-block column");
    }
}
