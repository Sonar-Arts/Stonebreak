package com.stonebreak.world.chunk.api.mightyMesh.mmsIntegration;

import com.openmason.engine.voxel.mms.mmsCore.MmsVertexFormat;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PulledQuadCodecFitsWorldHeightTest} for the fourth mesh format.
 *
 * <p>{@link MmsVertexFormat#COMPACT20} carries everything the pulled formats
 * cannot — SBO stamps and crosses under {@code QUAD16}, and every chunk vertex
 * when the format is forced — as an i16 fixed-point offset from the mesh
 * origin, whose Y is 0. So its reach from the origin is a world-height budget
 * exactly like the pulled codecs' Y fields, and it was the one the widening for
 * the 1024-tall world missed: at its original 1/64-block unit it reached ±512,
 * and every chunk holding a stamp above y=512 threw out of the mesher and
 * rendered as nothing at all. Its window is now both finer-grained and
 * shifted up, so it clears the top of the column rather than stopping one
 * quantization step short of it.
 */
class Compact20FitsWorldHeightTest {

    /**
     * The highest vertex any mesher can emit: the top face of a block on the
     * world's top layer. Unlike the pulled codecs, which store a cell and let
     * the shader's corner table add the face offset, COMPACT20 stores the
     * vertex — so its ceiling is WORLD_HEIGHT, not WORLD_HEIGHT - 1.
     */
    private static final int TOP_VERTEX = WorldConfiguration.WORLD_HEIGHT;

    @Test
    void compact20ReachesTheTopVertexOfTheWorld() {
        MmsVertexFormat f = MmsVertexFormat.COMPACT20;
        ByteBuffer buf = ByteBuffer.allocate(f.stride()).order(ByteOrder.nativeOrder());
        assertDoesNotThrow(() ->
                f.encode(buf, 0f, TOP_VERTEX, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f,
                    0f, 0f, 0f),
            "COMPACT20 cannot address y=" + TOP_VERTEX + " at WORLD_HEIGHT "
                + WorldConfiguration.WORLD_HEIGHT + "; from a zero Y origin it spans "
                + MmsVertexFormat.COMPACT_MIN_Y + " .. " + MmsVertexFormat.COMPACT_MAX_Y);
        assertEquals((float) TOP_VERTEX, f.position(buf, 0, 1, 0f, 0f, 0f));
    }

    /** The floor has to encode from the same origin the ceiling does. */
    @Test
    void compact20ReachesTheFloorOfTheWorld() {
        MmsVertexFormat f = MmsVertexFormat.COMPACT20;
        ByteBuffer buf = ByteBuffer.allocate(f.stride()).order(ByteOrder.nativeOrder());
        assertDoesNotThrow(() ->
                f.encode(buf, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f),
            "COMPACT20 cannot address y=0; it spans " + MmsVertexFormat.COMPACT_MIN_Y
                + " .. " + MmsVertexFormat.COMPACT_MAX_Y + " from a zero Y origin");
        assertEquals(0f, f.position(buf, 0, 1, 0f, 0f, 0f));
    }

    /**
     * The Y window is shifted, not just widened, so the headroom above the
     * world is finite and asymmetric. Keep it visible: a WORLD_HEIGHT bump that
     * still fits should say by how much it fits, not pass silently until the
     * next one overflows.
     */
    @Test
    void compact20KeepsHeadroomAboveTheWorld() {
        float headroom = MmsVertexFormat.COMPACT_MAX_Y - TOP_VERTEX;
        assertTrue(headroom >= 0f,
            "COMPACT20 tops out at " + MmsVertexFormat.COMPACT_MAX_Y + ", below y=" + TOP_VERTEX);
    }
}
