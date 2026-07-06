package com.openmason.engine.rendering.model.gmr.editable;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds an {@link EditableMesh} from legacy triangle soup (duplicated
 * per-face corners + triangle indices + triangle→face mapping) — the OMO
 * on-disk representation and the part-rebuild input.
 *
 * <p>Steps:
 * <ol>
 *   <li>Weld coincident soup positions into shared vertex ids
 *       ({@link VertexWelder}); the welded id IS the editable vertex id.</li>
 *   <li>Group triangles by face id and drop triangles degenerate after
 *       welding (repeated welded ids).</li>
 *   <li>Reconstruct each face's polygon loop by directed boundary-edge
 *       walking over welded ids: a directed edge (a→b) is a boundary edge
 *       when its reverse (b→a) appears in no other triangle of the face.
 *       Working on welded ids (rather than raw corner indices) lets interior
 *       edges cancel even when the soup used coincident duplicate corners.</li>
 *   <li>Attach authored per-corner UVs (when the soup carries texCoords) so
 *       texturing that is not a per-face region projection — sphere lat/long
 *       grids, hand-authored UVs — survives the import.</li>
 * </ol>
 *
 * <p>Face ids are preserved verbatim — materials, UV regions and part face
 * ranges key off them.
 */
public final class MeshImporter {

    private static final Logger logger = LoggerFactory.getLogger(MeshImporter.class);

    /**
     * Import result.
     *
     * @param mesh                  Imported mesh
     * @param vertexIdToSoupIndices ALL soup vertex indices welded into each
     *                              editable vertex id (the part system's
     *                              combined-buffer index space). Welding can
     *                              cross part boundaries, so a shared seam
     *                              vertex may map into several parts' ranges —
     *                              consumers must consider every index, not
     *                              just a representative.
     */
    public record ImportResult(EditableMesh mesh, int[][] vertexIdToSoupIndices) {
    }

    private MeshImporter() {
        // Static utility — no instantiation
    }

    /** Position-only import (no authored UVs). */
    public static EditableMesh importSoup(float[] vertices, int[] indices, int[] triangleToFaceId) {
        return importSoup(vertices, null, indices, triangleToFaceId).mesh();
    }

    /**
     * Import triangle soup into a new {@link EditableMesh}.
     *
     * @param vertices          Soup positions (x,y,z interleaved)
     * @param texCoords         Soup UVs (u,v interleaved) or {@code null};
     *                          when present, faces carry them as authored UVs
     * @param indices           Triangle indices into the soup
     * @param triangleToFaceId  Face id per triangle; {@code null} means each
     *                          triangle is its own face (ids = triangle index)
     */
    public static ImportResult importSoup(float[] vertices, float[] texCoords,
                                          int[] indices, int[] triangleToFaceId) {
        EditableMesh mesh = new EditableMesh();
        if (vertices == null || vertices.length < 9 || indices == null || indices.length < 3) {
            return new ImportResult(mesh, new int[0][]);
        }

        VertexWelder.WeldResult weld = VertexWelder.weld(vertices);
        for (int w = 0; w < weld.weldedCount(); w++) {
            mesh.addVertex(new Vector3f(
                weld.weldedPositions()[w * 3],
                weld.weldedPositions()[w * 3 + 1],
                weld.weldedPositions()[w * 3 + 2]));
        }

        boolean hasUVs = texCoords != null && texCoords.length >= (vertices.length / 3) * 2;

        // Group non-degenerate triangles by face id; remember, per face, a soup
        // corner for each welded id (for authored-UV lookup).
        Map<Integer, List<int[]>> faceTriangles = new LinkedHashMap<>();
        Map<Integer, Map<Integer, Integer>> faceWeldedToSoup = hasUVs ? new HashMap<>() : null;
        int triangleCount = indices.length / 3;
        int[] soupToWelded = weld.soupToWelded();

        int skippedOutOfRange = 0;
        for (int t = 0; t < triangleCount; t++) {
            int sa = indices[t * 3];
            int sb = indices[t * 3 + 1];
            int sc = indices[t * 3 + 2];
            if (sa < 0 || sa >= soupToWelded.length
                    || sb < 0 || sb >= soupToWelded.length
                    || sc < 0 || sc >= soupToWelded.length) {
                // Corrupt input (indices past the vertex array) — dropping the
                // triangle keeps the rest of the mesh usable instead of
                // aborting the whole rebuild deep inside the pipeline.
                skippedOutOfRange++;
                continue;
            }
            int a = soupToWelded[sa];
            int b = soupToWelded[sb];
            int c = soupToWelded[sc];
            if (a == b || b == c || a == c) {
                continue; // degenerate after welding
            }
            int faceId = (triangleToFaceId != null && t < triangleToFaceId.length)
                ? triangleToFaceId[t] : t;
            if (faceId < 0) {
                continue;
            }
            faceTriangles.computeIfAbsent(faceId, k -> new ArrayList<>()).add(new int[]{a, b, c});
            if (hasUVs) {
                Map<Integer, Integer> weldedToSoup =
                    faceWeldedToSoup.computeIfAbsent(faceId, k -> new HashMap<>());
                weldedToSoup.putIfAbsent(a, sa);
                weldedToSoup.putIfAbsent(b, sb);
                weldedToSoup.putIfAbsent(c, sc);
            }
        }
        if (skippedOutOfRange > 0) {
            logger.error("Import dropped {} triangle(s) whose indices exceed the {}-vertex soup "
                    + "— the input geometry is corrupt (stale part ranges?)",
                    skippedOutOfRange, soupToWelded.length);
        }

        for (Map.Entry<Integer, List<int[]>> entry : faceTriangles.entrySet()) {
            int faceId = entry.getKey();
            int[] loop = reconstructLoop(entry.getValue());
            if (loop.length < 3) {
                logger.warn("Face {} degenerate after welding ({} loop vertices) — skipped",
                    faceId, loop.length);
                continue;
            }

            float[] cornerUVs = null;
            if (hasUVs) {
                Map<Integer, Integer> weldedToSoup = faceWeldedToSoup.get(faceId);
                cornerUVs = new float[loop.length * 2];
                for (int i = 0; i < loop.length; i++) {
                    Integer soupIdx = weldedToSoup.get(loop[i]);
                    if (soupIdx == null) {
                        cornerUVs = null; // loop vertex not seen in triangles — bail out
                        break;
                    }
                    cornerUVs[i * 2]     = texCoords[soupIdx * 2];
                    cornerUVs[i * 2 + 1] = texCoords[soupIdx * 2 + 1];
                }
            }

            mesh.addFaceWithId(faceId, loop, cornerUVs);
        }

        // All soup indices per welded vertex (part-system index space) —
        // welding crosses part boundaries, so every occurrence matters.
        List<List<Integer>> soupPerVertex = new ArrayList<>(weld.weldedCount());
        for (int w = 0; w < weld.weldedCount(); w++) {
            soupPerVertex.add(new ArrayList<>(2));
        }
        for (int soupIdx = 0; soupIdx < soupToWelded.length; soupIdx++) {
            soupPerVertex.get(soupToWelded[soupIdx]).add(soupIdx);
        }
        int[][] vertexIdToSoupIndices = new int[weld.weldedCount()][];
        for (int w = 0; w < weld.weldedCount(); w++) {
            List<Integer> list = soupPerVertex.get(w);
            int[] arr = new int[list.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = list.get(i);
            }
            vertexIdToSoupIndices[w] = arr;
        }

        logger.debug("Imported soup: {} soup vertices -> {} shared vertices, {} faces (authoredUVs={})",
            vertices.length / 3, mesh.vertexCount(), mesh.faceCount(), hasUVs);
        return new ImportResult(mesh, vertexIdToSoupIndices);
    }

    /**
     * Reconstruct a face's ordered polygon loop from its triangles (welded ids)
     * via directed boundary-edge walking. Falls back to insertion order (with a
     * warning) if the boundary chain does not close — which only happens for
     * soup that was not produced by triangulating a simple polygon.
     */
    private static int[] reconstructLoop(List<int[]> triangles) {
        if (triangles.size() == 1) {
            return triangles.get(0).clone();
        }

        // Count directed edges; boundary edges are those whose reverse never appears.
        Map<Long, Integer> directedEdgeCounts = new LinkedHashMap<>();
        for (int[] tri : triangles) {
            directedEdgeCounts.merge(edgeKey(tri[0], tri[1]), 1, Integer::sum);
            directedEdgeCounts.merge(edgeKey(tri[1], tri[2]), 1, Integer::sum);
            directedEdgeCounts.merge(edgeKey(tri[2], tri[0]), 1, Integer::sum);
        }

        Map<Integer, Integer> boundaryNext = new LinkedHashMap<>();
        for (Map.Entry<Long, Integer> entry : directedEdgeCounts.entrySet()) {
            long key = entry.getKey();
            int src = (int) (key >>> 32);
            int dst = (int) key;
            if (!directedEdgeCounts.containsKey(edgeKey(dst, src))) {
                boundaryNext.put(src, dst);
            }
        }

        if (boundaryNext.size() >= 3) {
            int start = triangles.get(0)[0];
            if (!boundaryNext.containsKey(start)) {
                start = boundaryNext.keySet().iterator().next();
            }

            List<Integer> polygon = new ArrayList<>(boundaryNext.size());
            int current = start;
            do {
                polygon.add(current);
                Integer next = boundaryNext.get(current);
                if (next == null) {
                    break;
                }
                current = next;
            } while (current != start && polygon.size() < boundaryNext.size());

            if (polygon.size() >= 3 && current == start && polygon.size() == boundaryNext.size()) {
                int[] loop = new int[polygon.size()];
                for (int i = 0; i < loop.length; i++) {
                    loop[i] = polygon.get(i);
                }
                return loop;
            }
        }

        // Fallback: insertion order. Correct for fans; warn because winding
        // is not guaranteed for other patterns.
        logger.warn("Boundary walk failed for a {}-triangle face — using insertion-order fallback",
            triangles.size());
        Set<Integer> seen = new LinkedHashSet<>();
        for (int[] tri : triangles) {
            seen.add(tri[0]);
            seen.add(tri[1]);
            seen.add(tri[2]);
        }
        int[] loop = new int[seen.size()];
        int i = 0;
        for (int v : seen) {
            loop[i++] = v;
        }
        return loop;
    }

    private static long edgeKey(int src, int dst) {
        return ((long) src << 32) | (dst & 0xFFFFFFFFL);
    }
}
