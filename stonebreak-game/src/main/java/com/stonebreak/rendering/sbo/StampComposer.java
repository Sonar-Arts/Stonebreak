package com.stonebreak.rendering.sbo;

import com.openmason.engine.voxel.sbo.SBOMeshProcessor.BlockStamp;
import com.openmason.engine.voxel.sbo.SBOMeshProcessor.FaceStamp;

import java.util.List;

/**
 * Builds one {@link BlockStamp} out of several: the parts a multipart block draws for one
 * neighbour combination, merged so the mesher emits them with one cache lookup.
 *
 * <p>Buckets merge slot by slot (flush, interior, protruding), so each part keeps its culling.
 * A boundary plane counts as occluding when the parts' flush geometry together covers it, so a
 * face cut into several pieces (a leaf's flat face beside its rounded border) still hides its
 * neighbour.
 */
public final class StampComposer {

    private static final int FACES = 6;
    /** Same slack as the stamp baker's own covered-area test. */
    private static final float AREA_EPSILON = 1e-3f;
    private static final FaceStamp EMPTY = new FaceStamp(new float[0], new float[0], new float[0], new float[0], 0);

    private StampComposer() {
    }

    /** The parts merged into one stamp; an empty list gives a stamp that draws nothing. */
    public static BlockStamp compose(List<BlockStamp> parts) {
        FaceStamp[] faces = new FaceStamp[FACES];
        FaceStamp[] interior = new FaceStamp[FACES];
        FaceStamp[][] protruding = new FaceStamp[FACES][FACES];
        boolean[] occludes = new boolean[FACES];
        for (int f = 0; f < FACES; f++) {
            final int face = f;
            faces[f] = concat(parts.stream().map(p -> p.faces()[face]).toList());
            interior[f] = concat(parts.stream().map(p -> p.interior()[face]).toList());
            for (int along = 0; along < FACES; along++) {
                final int a = along;
                protruding[f][a] = concat(parts.stream().map(p -> p.protruding()[face][a]).toList());
            }
            occludes[f] = flushArea(faces[f], f) >= 1f - AREA_EPSILON;
        }
        return new BlockStamp(faces, interior, occludes, protruding);
    }

    /**
     * True when a stamp is a plain unit cube: nothing inside the cell, nothing poking out, and all
     * six boundary planes covered. Such a shape draws exactly like the cube path's six faces, so a
     * cell resolving to it can stay on that path.
     */
    public static boolean isUnitCube(BlockStamp stamp) {
        if (stamp == null || stamp.hasProtrusions()) return false;
        for (int f = 0; f < FACES; f++) {
            if (stamp.interior()[f].vertexCount() != 0 || !stamp.occludesFace()[f]) return false;
        }
        return true;
    }

    private static FaceStamp concat(List<FaceStamp> stamps) {
        int vertices = 0;
        for (FaceStamp s : stamps) vertices += s.vertexCount();
        if (vertices == 0) return EMPTY;
        float[] pos = new float[vertices * 3];
        float[] nrm = new float[vertices * 3];
        float[] uv = new float[vertices * 2];
        float[] layers = new float[vertices];
        int v = 0;
        for (FaceStamp s : stamps) {
            int n = s.vertexCount();
            System.arraycopy(s.positions(), 0, pos, v * 3, n * 3);
            System.arraycopy(s.normals(), 0, nrm, v * 3, n * 3);
            System.arraycopy(s.atlasUVs(), 0, uv, v * 2, n * 2);
            System.arraycopy(s.layers(), 0, layers, v, n);
            v += n;
        }
        return new FaceStamp(pos, nrm, uv, layers, vertices);
    }

    /** Area the flush triangles cover on face {@code face}'s boundary plane. */
    private static float flushArea(FaceStamp stamp, int face) {
        int axis = com.openmason.engine.voxel.sbo.sboRenderer.SBOFaceConventions.axisOf(face);
        int u = (axis + 1) % 3;
        int w = (axis + 2) % 3;
        float[] p = stamp.positions();
        float area = 0f;
        for (int t = 0; t < stamp.vertexCount() / 3; t++) {
            int o = t * 9;
            float au = p[o + 3 + u] - p[o + u];
            float aw = p[o + 3 + w] - p[o + w];
            float bu = p[o + 6 + u] - p[o + u];
            float bw = p[o + 6 + w] - p[o + w];
            area += Math.abs(au * bw - aw * bu) * 0.5f;
        }
        return area;
    }
}
