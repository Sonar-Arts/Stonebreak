package com.openmason.engine.voxel.sbo;

import com.openmason.engine.voxel.sbo.SBOMeshProcessor.BlockStamp;
import com.openmason.engine.voxel.sbo.SBOMeshProcessor.FaceStamp;
import com.openmason.engine.voxel.sbo.sboRenderer.SBOFaceConventions;

/**
 * Rotates a pre-baked {@link BlockStamp} in quarter turns about the block's
 * vertical axis, so an orientable shape (stairs today) can be registered once
 * per facing instead of being rotated per instance at mesh time.
 *
 * <p>Positions and normals rotate; UVs and texture layers ride along with the
 * geometry, so the side that was modelled as the front keeps the front's
 * texture after the turn. Face buckets rotate with them — the boundary-flush
 * geometry that used to sit on the south plane is checked against the east
 * neighbour after a single turn — which keeps culling and the occlusion mask
 * correct for the rotated shape.
 *
 * <p>Rotation follows the JOML {@code rotateY} convention used everywhere else
 * in the game: {@code x' = x·cosθ + z·sinθ}, {@code z' = −x·sinθ + z·cosθ}.
 * One quarter turn therefore maps +Z (south) onto +X (east).
 */
public final class SBOStampRotator {

    private SBOStampRotator() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * A copy of {@code stamp} turned {@code quarterTurns} × 90° about +Y.
     * Returns the input unchanged for a zero (or multiple-of-four) turn.
     */
    public static BlockStamp rotateY(BlockStamp stamp, int quarterTurns) {
        int turns = Math.floorMod(quarterTurns, 4);
        if (turns == 0) {
            return stamp;
        }

        FaceStamp[] faces = new FaceStamp[SBOFaceConventions.FACE_COUNT];
        FaceStamp[] interior = new FaceStamp[SBOFaceConventions.FACE_COUNT];
        boolean[] occludes = new boolean[SBOFaceConventions.FACE_COUNT];

        for (int face = 0; face < SBOFaceConventions.FACE_COUNT; face++) {
            int rotated = rotateFace(face, turns);
            faces[rotated] = rotateGeometry(stamp.faces()[face], turns);
            interior[rotated] = rotateGeometry(stamp.interior()[face], turns);
            occludes[rotated] = stamp.occludesFace()[face];
        }
        return new BlockStamp(faces, interior, occludes);
    }

    /** The MMS face a face ends up pointing at after {@code turns} quarter turns. */
    public static int rotateFace(int mmsFace, int turns) {
        int t = Math.floorMod(turns, 4);
        int face = mmsFace;
        for (int i = 0; i < t; i++) {
            face = switch (face) {
                case SBOFaceConventions.MMS_SOUTH -> SBOFaceConventions.MMS_EAST;
                case SBOFaceConventions.MMS_EAST -> SBOFaceConventions.MMS_NORTH;
                case SBOFaceConventions.MMS_NORTH -> SBOFaceConventions.MMS_WEST;
                case SBOFaceConventions.MMS_WEST -> SBOFaceConventions.MMS_SOUTH;
                default -> face; // top and bottom are invariant under a Y turn
            };
        }
        return face;
    }

    private static FaceStamp rotateGeometry(FaceStamp source, int turns) {
        int vertexCount = source.vertexCount();
        if (vertexCount == 0) {
            return source; // nothing to rotate; the empty stamp is immutable
        }
        float[] positions = source.positions().clone();
        float[] normals = source.normals().clone();
        for (int v = 0; v < vertexCount; v++) {
            int off = v * 3;
            rotateXZ(positions, off, turns);
            rotateXZ(normals, off, turns);
        }
        // UVs and layers are unchanged: the texture turns with the geometry.
        return new FaceStamp(positions, normals, source.atlasUVs(), source.layers(), vertexCount);
    }

    /** In-place quarter-turn rotation of the (x, z) pair at {@code off}. */
    private static void rotateXZ(float[] data, int off, int turns) {
        float x = data[off];
        float z = data[off + 2];
        switch (turns) {
            case 1 -> { data[off] = z;  data[off + 2] = -x; }
            case 2 -> { data[off] = -x; data[off + 2] = -z; }
            case 3 -> { data[off] = -z; data[off + 2] = x; }
            default -> { /* unreachable: callers normalise to 1..3 */ }
        }
    }
}
