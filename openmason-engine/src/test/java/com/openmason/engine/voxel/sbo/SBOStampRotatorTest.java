package com.openmason.engine.voxel.sbo;

import com.openmason.engine.voxel.sbo.SBOMeshProcessor.BlockStamp;
import com.openmason.engine.voxel.sbo.SBOMeshProcessor.FaceStamp;
import com.openmason.engine.voxel.sbo.sboRenderer.SBOFaceConventions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Quarter-turn rotation of a baked stamp.
 *
 * <p>The direction pinned here is the one the game's cell-space query inverts,
 * so drawn geometry and the shape a body collides with turn together. Getting
 * the two out of step is invisible in isolation and obvious in play.
 */
class SBOStampRotatorTest {

    private static final float EPS = 1e-5f;

    @Test
    void oneQuarterTurnCarriesTheSouthSideToTheEast() {
        BlockStamp turned = SBOStampRotator.rotateY(marker(SBOFaceConventions.MMS_SOUTH), 1);

        FaceStamp east = turned.faces()[SBOFaceConventions.MMS_EAST];
        assertEquals(3, east.vertexCount(), "the south geometry must land in the east bucket");
        // The marker vertex sat at +Z; a turn must place it at +X.
        assertEquals(0.5f, east.positions()[0], EPS);
        assertEquals(0f, east.positions()[2], EPS);
        // ...and its normal with it.
        assertEquals(1f, east.normals()[0], EPS);
        assertEquals(0f, east.normals()[2], EPS);
        assertEquals(0, turned.faces()[SBOFaceConventions.MMS_SOUTH].vertexCount());
    }

    @Test
    void facesCycleThroughEveryHorizontalSideAndComeBack() {
        int face = SBOFaceConventions.MMS_SOUTH;
        assertEquals(SBOFaceConventions.MMS_EAST, SBOStampRotator.rotateFace(face, 1));
        assertEquals(SBOFaceConventions.MMS_NORTH, SBOStampRotator.rotateFace(face, 2));
        assertEquals(SBOFaceConventions.MMS_WEST, SBOStampRotator.rotateFace(face, 3));
        assertEquals(face, SBOStampRotator.rotateFace(face, 4));
        // Top and bottom are invariant under a vertical-axis turn.
        assertEquals(SBOFaceConventions.MMS_TOP,
                SBOStampRotator.rotateFace(SBOFaceConventions.MMS_TOP, 1));
        assertEquals(SBOFaceConventions.MMS_BOTTOM,
                SBOStampRotator.rotateFace(SBOFaceConventions.MMS_BOTTOM, 3));
    }

    @Test
    void interiorGeometryAndTheOcclusionMaskTurnWithTheFaces() {
        BlockStamp base = marker(SBOFaceConventions.MMS_SOUTH);
        BlockStamp turned = SBOStampRotator.rotateY(base, 1);

        assertEquals(3, turned.interior()[SBOFaceConventions.MMS_EAST].vertexCount());
        assertEquals(0.5f, turned.interior()[SBOFaceConventions.MMS_EAST].positions()[0], EPS);
        // Only the marked face occludes, and it must follow the geometry.
        assertEquals(true, turned.occludesFace()[SBOFaceConventions.MMS_EAST]);
        assertEquals(false, turned.occludesFace()[SBOFaceConventions.MMS_SOUTH]);
    }

    @Test
    void fourTurnsRestoreTheOriginal() {
        BlockStamp base = marker(SBOFaceConventions.MMS_SOUTH);
        BlockStamp full = SBOStampRotator.rotateY(base, 4);
        assertSame(base, full, "a whole turn is a no-op");

        BlockStamp roundTrip = SBOStampRotator.rotateY(
                SBOStampRotator.rotateY(base, 3), 1);
        FaceStamp original = base.faces()[SBOFaceConventions.MMS_SOUTH];
        FaceStamp back = roundTrip.faces()[SBOFaceConventions.MMS_SOUTH];
        for (int i = 0; i < original.positions().length; i++) {
            assertEquals(original.positions()[i], back.positions()[i], EPS);
        }
    }

    @Test
    void texturesRideAlongWithTheGeometry() {
        BlockStamp base = marker(SBOFaceConventions.MMS_SOUTH);
        BlockStamp turned = SBOStampRotator.rotateY(base, 2);
        FaceStamp moved = turned.faces()[SBOFaceConventions.MMS_NORTH];
        // The face that was the front keeps the front's UVs and layer after
        // turning, rather than picking up the texture of its new side.
        assertArrayEquals(base.faces()[SBOFaceConventions.MMS_SOUTH].atlasUVs(), moved.atlasUVs(), EPS);
        assertArrayEquals(base.faces()[SBOFaceConventions.MMS_SOUTH].layers(), moved.layers(), EPS);
    }

    /**
     * A stamp with one triangle in {@code face}'s flush and interior buckets,
     * its lone marked vertex at that face's outward extreme, and only that face
     * flagged as occluding.
     */
    private static BlockStamp marker(int face) {
        FaceStamp[] flush = new FaceStamp[SBOFaceConventions.FACE_COUNT];
        FaceStamp[] interior = new FaceStamp[SBOFaceConventions.FACE_COUNT];
        boolean[] occludes = new boolean[SBOFaceConventions.FACE_COUNT];
        for (int f = 0; f < SBOFaceConventions.FACE_COUNT; f++) {
            flush[f] = empty();
            interior[f] = empty();
        }
        flush[face] = triangle();
        interior[face] = triangle();
        occludes[face] = true;
        return new BlockStamp(flush, interior, occludes);
    }

    private static FaceStamp empty() {
        return new FaceStamp(new float[0], new float[0], new float[0], new float[0], 0);
    }

    /** Triangle on the +Z boundary; the first vertex carries the +Z normal. */
    private static FaceStamp triangle() {
        return new FaceStamp(
                new float[]{0f, 0f, 0.5f, -0.5f, 0f, 0.5f, 0.5f, 0f, 0.5f},
                new float[]{0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f},
                new float[]{0.5f, 0.5f, 0f, 0.5f, 1f, 0.5f},
                new float[]{7f, 7f, 7f},
                3);
    }
}
