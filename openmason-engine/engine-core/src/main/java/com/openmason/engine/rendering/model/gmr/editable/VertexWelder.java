package com.openmason.engine.rendering.model.gmr.editable;

import com.openmason.engine.rendering.model.gmr.GMRConstants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Position welding for the import path: collapses coincident soup vertices
 * (within {@link GMRConstants#POSITION_EPSILON}) into shared welded ids.
 *
 * <p>Spatial-hash grid (cell = 2× epsilon, 27-cell neighbor search) for O(n)
 * behavior — the same algorithm the legacy {@code UniqueVertexMapper} used,
 * extracted here because after the {@link EditableMesh} migration welding
 * only ever happens once, at import time.
 */
public final class VertexWelder {

    private VertexWelder() {
        // Static utility — no instantiation
    }

    /**
     * Result of a weld pass.
     *
     * @param soupToWelded  For each soup vertex index, its welded vertex id
     * @param weldedPositions Welded positions (x,y,z interleaved), one entry
     *                        per welded id, taken from the first occurrence
     * @param weldedCount   Number of welded vertices
     */
    public record WeldResult(int[] soupToWelded, float[] weldedPositions, int weldedCount) {
    }

    /** Weld with the canonical {@link GMRConstants#POSITION_EPSILON}. */
    public static WeldResult weld(float[] vertices) {
        return weld(vertices, GMRConstants.POSITION_EPSILON);
    }

    /**
     * Weld coincident positions in a soup vertex array.
     *
     * @param vertices Soup positions (x,y,z interleaved)
     * @param epsilon  Distance below which two positions are the same vertex
     */
    public static WeldResult weld(float[] vertices, float epsilon) {
        if (vertices == null || vertices.length == 0) {
            return new WeldResult(new int[0], new float[0], 0);
        }
        if (vertices.length % 3 != 0) {
            throw new IllegalArgumentException(
                "Vertex array length must be divisible by 3: " + vertices.length);
        }

        int soupCount = vertices.length / 3;
        int[] soupToWelded = new int[soupCount];
        List<Integer> representatives = new ArrayList<>();

        float cellSize = epsilon * 2.0f;
        float epsilonSq = epsilon * epsilon;
        Map<Long, List<Integer>> spatialHash = new HashMap<>();

        for (int soupIdx = 0; soupIdx < soupCount; soupIdx++) {
            float x = vertices[soupIdx * 3];
            float y = vertices[soupIdx * 3 + 1];
            float z = vertices[soupIdx * 3 + 2];

            int cx = (int) Math.floor(x / cellSize);
            int cy = (int) Math.floor(y / cellSize);
            int cz = (int) Math.floor(z / cellSize);

            int matched = -1;
            searchLoop:
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        List<Integer> candidates = spatialHash.get(cellKey(cx + dx, cy + dy, cz + dz));
                        if (candidates == null) {
                            continue;
                        }
                        for (int candidate : candidates) {
                            float ddx = x - vertices[candidate * 3];
                            float ddy = y - vertices[candidate * 3 + 1];
                            float ddz = z - vertices[candidate * 3 + 2];
                            if (ddx * ddx + ddy * ddy + ddz * ddz < epsilonSq) {
                                matched = soupToWelded[candidate];
                                break searchLoop;
                            }
                        }
                    }
                }
            }

            if (matched >= 0) {
                soupToWelded[soupIdx] = matched;
            } else {
                soupToWelded[soupIdx] = representatives.size();
                representatives.add(soupIdx);
            }

            spatialHash.computeIfAbsent(cellKey(cx, cy, cz), k -> new ArrayList<>()).add(soupIdx);
        }

        float[] weldedPositions = new float[representatives.size() * 3];
        for (int w = 0; w < representatives.size(); w++) {
            int src = representatives.get(w) * 3;
            weldedPositions[w * 3]     = vertices[src];
            weldedPositions[w * 3 + 1] = vertices[src + 1];
            weldedPositions[w * 3 + 2] = vertices[src + 2];
        }

        return new WeldResult(soupToWelded, weldedPositions, representatives.size());
    }

    /** Pack a 3D grid cell into a hash key (21 bits per axis, sign-preserving). */
    private static long cellKey(int x, int y, int z) {
        return ((x & 0x1FFFFFL) << 42) | ((y & 0x1FFFFFL) << 21) | (z & 0x1FFFFFL);
    }
}
