package com.stonebreak.rendering.gameWorld.sky.clouds;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a {@link CloudPattern} coverage grid into a single static mesh of
 * cuboid cloud cells.
 *
 * <p>Each occupied cell becomes a box {@code CELL_WIDTH x CELL_HEIGHT x
 * CELL_DEPTH} world units. Faces shared with an adjacent occupied cell are
 * culled, so only the silhouette plus every cell's top and bottom are emitted.
 * Each vertex carries a position (3 floats) and a flat face-shade factor
 * (1 float) used by the cloud shader for cheap directional shading.</p>
 *
 * <p>The mesh is built in a local space spanning {@code [0, size*CELL_WIDTH]}
 * on X/Z and {@code [0, CELL_HEIGHT]} on Y; the renderer positions it via its
 * model matrix.</p>
 */
public final class CloudMeshBuilder {

    /** Horizontal size of one cloud cell, in world units. */
    public static final float CELL_WIDTH = 12.0f;
    /** Vertical thickness of the cloud layer, in world units. */
    public static final float CELL_HEIGHT = 4.0f;
    /** Depth of one cloud cell, in world units. */
    public static final float CELL_DEPTH = 12.0f;

    /** Floats per vertex: x, y, z, shade. */
    public static final int FLOATS_PER_VERTEX = 4;

    private static final float SHADE_TOP = 1.0f;
    private static final float SHADE_SIDE = 0.75f;
    private static final float SHADE_BOTTOM = 0.55f;

    private CloudMeshBuilder() {
    }

    /** Immutable result of a mesh build: interleaved vertex data and indices. */
    public record CloudMeshData(float[] vertices, int[] indices) {
        public int indexCount() {
            return indices.length;
        }
    }

    /**
     * Builds the cloud mesh for the given coverage pattern.
     */
    public static CloudMeshData build(CloudPattern pattern) {
        int size = pattern.getSize();
        List<Float> verts = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();

        for (int gx = 0; gx < size; gx++) {
            for (int gz = 0; gz < size; gz++) {
                if (!pattern.isCloud(gx, gz)) {
                    continue;
                }

                float x0 = gx * CELL_WIDTH;
                float x1 = x0 + CELL_WIDTH;
                float z0 = gz * CELL_DEPTH;
                float z1 = z0 + CELL_DEPTH;
                float y0 = 0.0f;
                float y1 = CELL_HEIGHT;

                // Top and bottom faces are always exposed.
                emitQuad(verts, indices, SHADE_TOP,
                        x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
                emitQuad(verts, indices, SHADE_BOTTOM,
                        x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);

                // Side faces only where the neighbouring cell is open sky.
                if (!pattern.isCloud(gx + 1, gz)) {
                    emitQuad(verts, indices, SHADE_SIDE,
                            x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
                }
                if (!pattern.isCloud(gx - 1, gz)) {
                    emitQuad(verts, indices, SHADE_SIDE,
                            x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
                }
                if (!pattern.isCloud(gx, gz + 1)) {
                    emitQuad(verts, indices, SHADE_SIDE,
                            x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
                }
                if (!pattern.isCloud(gx, gz - 1)) {
                    emitQuad(verts, indices, SHADE_SIDE,
                            x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
                }
            }
        }

        return new CloudMeshData(toFloatArray(verts), toIntArray(indices));
    }

    /**
     * Emits one quad from four corners given in counter-clockwise order as seen
     * from outside the cuboid (so default GL_CCW back-face culling keeps it).
     */
    private static void emitQuad(List<Float> verts, List<Integer> indices, float shade,
                                  float ax, float ay, float az,
                                  float bx, float by, float bz,
                                  float cx, float cy, float cz,
                                  float dx, float dy, float dz) {
        int base = verts.size() / FLOATS_PER_VERTEX;

        pushVertex(verts, ax, ay, az, shade);
        pushVertex(verts, bx, by, bz, shade);
        pushVertex(verts, cx, cy, cz, shade);
        pushVertex(verts, dx, dy, dz, shade);

        indices.add(base);
        indices.add(base + 1);
        indices.add(base + 2);
        indices.add(base);
        indices.add(base + 2);
        indices.add(base + 3);
    }

    private static void pushVertex(List<Float> verts, float x, float y, float z, float shade) {
        verts.add(x);
        verts.add(y);
        verts.add(z);
        verts.add(shade);
    }

    private static float[] toFloatArray(List<Float> list) {
        float[] out = new float[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }
}
