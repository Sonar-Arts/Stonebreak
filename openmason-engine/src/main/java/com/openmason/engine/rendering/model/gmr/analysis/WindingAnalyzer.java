package com.openmason.engine.rendering.model.gmr.analysis;

import com.openmason.engine.rendering.model.gmr.editable.EditableFace;
import com.openmason.engine.rendering.model.gmr.editable.EditableMesh;
import com.openmason.engine.rendering.model.gmr.editable.MeshImporter;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GL-free winding / degeneracy / planarity validator for editable meshes.
 *
 * <p>SBO authoring history left shipped assets with mixed triangle winding
 * (see {@code SBONormalComputer}), so a face's cross-product normal cannot be
 * trusted to point outward. This analyzer derives a geometric ground truth per
 * face and compares the loop-winding (Newell) normal against it:
 *
 * <ol>
 *   <li><b>Closed manifold parts</b> (no boundary edges, no over-shared edges,
 *       non-trivial signed volume): a parity ray probe — cast from just outside
 *       the face along its Newell normal and count crossings with the rest of
 *       the part. Even crossings ⇒ the normal escapes ⇒ OUTWARD; odd ⇒ INWARD.
 *       Grazing hits retry with deterministically jittered rays.</li>
 *   <li><b>Open / zero-volume parts</b> (planes, sprite crosses): "outward" is
 *       undefined — faces are INDETERMINATE with a part-level reason, never an
 *       error. The one exception: faces lying flush on the part's AABB boundary
 *       (the voxel-block case) are judged against the AABB outward direction.</li>
 *   <li><b>Fallback</b> (parity inconclusive, or parts over the triangle cap):
 *       centroid-dot — sign of {@code n · (faceCentroid − partCentroid)}, with
 *       a dead zone reported as INDETERMINATE rather than guessed.</li>
 * </ol>
 *
 * <p>Face-id contract violations (one face id must map to one triangulated
 * simple polygon) are taken from {@link MeshImporter.ImportWarning}s captured
 * during import.
 */
public final class WindingAnalyzer {

    /** Parity probes are capped like SBONormalComputer's parity walk. */
    private static final int PARITY_TRIANGLE_LIMIT = 4096;
    /** Dead zone for the centroid-dot fallback (|cos| below this ⇒ INDETERMINATE). */
    private static final float DOT_DEAD_ZONE = 0.2f;
    /** Deterministic jitter directions for grazing parity rays (scaled by 1e-3). */
    private static final float[][] JITTERS = {
            {1.7e-3f, 3.1e-3f, -2.3e-3f},
            {-2.9e-3f, 1.3e-3f, 2.1e-3f},
            {2.3e-3f, -2.7e-3f, 1.9e-3f},
    };

    /** Geometric orientation of a face's winding normal. */
    public enum Orientation { OUTWARD, INWARD, INDETERMINATE }

    /**
     * Per-face verdict.
     *
     * @param method how the orientation was decided: {@code parity},
     *               {@code bbox_flush}, {@code centroid_dot}, {@code none}
     * @param normal unit Newell normal of the loop winding (zero for degenerate)
     */
    public record FaceResult(int faceId, Orientation orientation, String method,
                             float[] normal, float area,
                             boolean degenerate, boolean nonPlanar, boolean contractViolation) {
    }

    /**
     * Per-part report.
     *
     * @param indeterminateReason why orientation could not be decided part-wide
     *                            ({@code open_mesh}, {@code zero_volume},
     *                            {@code non_manifold}, {@code too_large}) or
     *                            {@code null} when the parity probe ran
     */
    public record PartReport(String part, int faceCount, int outward, int inward, int indeterminate,
                             boolean mixedWinding, boolean closed, float signedVolume,
                             String indeterminateReason, List<FaceResult> faces, List<String> hints) {

        public int[] invertedFaceIds() {
            return idsWhere(f -> f.orientation() == Orientation.INWARD && !f.degenerate());
        }

        public int[] degenerateFaceIds() {
            return idsWhere(FaceResult::degenerate);
        }

        public int[] nonPlanarFaceIds() {
            return idsWhere(FaceResult::nonPlanar);
        }

        public int[] contractViolationFaceIds() {
            return idsWhere(FaceResult::contractViolation);
        }

        private int[] idsWhere(java.util.function.Predicate<FaceResult> p) {
            return faces.stream().filter(p).mapToInt(FaceResult::faceId).sorted().toArray();
        }
    }

    private WindingAnalyzer() {
        // Static utility
    }

    /**
     * Convenience: import triangle soup (capturing contract-violation warnings)
     * and analyze the result.
     */
    public static PartReport analyzeSoup(String partName, float[] vertices,
                                         int[] indices, int[] triangleToFaceId) {
        List<MeshImporter.ImportWarning> warnings = new ArrayList<>();
        MeshImporter.ImportResult result =
                MeshImporter.importSoup(vertices, null, indices, triangleToFaceId, warnings::add);
        return analyze(partName, result.mesh(), warnings);
    }

    /**
     * Analyze one part's mesh.
     *
     * @param importWarnings warnings captured while importing this part's soup
     *                       (may be {@code null}); BOUNDARY_WALK_FAILED entries
     *                       mark contract violations, DEGENERATE_FACE entries
     *                       add synthetic degenerate rows for skipped faces
     */
    public static PartReport analyze(String partName, EditableMesh mesh,
                                     Collection<MeshImporter.ImportWarning> importWarnings) {
        List<EditableFace> faces = new ArrayList<>(mesh.faces());
        faces.sort(Comparator.comparingInt(EditableFace::faceId));

        // Part-level geometry: bbox, centroid, fan triangles, edge manifold info.
        Vector3f bbMin = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f bbMax = new Vector3f(Float.NEGATIVE_INFINITY);
        Vector3f partCentroid = new Vector3f();
        int vertexSamples = 0;
        for (int v = 0; v < mesh.vertexCount(); v++) {
            Vector3f p = mesh.position(v);
            bbMin.min(p);
            bbMax.max(p);
            partCentroid.add(p);
            vertexSamples++;
        }
        if (vertexSamples > 0) {
            partCentroid.div(vertexSamples);
        }
        float diag = Float.isFinite(bbMin.x) ? bbMax.distance(bbMin) : 0f;
        float areaEps = Math.max(1e-12f, 1e-6f * diag * diag);
        float planarTol = Math.max(1e-6f, 1e-3f * diag);

        // Undirected edge sharing decides closedness — winding-independent, so a
        // flipped face (the very defect we classify) does not read as a hole.
        Map<Long, Integer> undirectedEdges = new HashMap<>();
        List<int[]> fanTris = new ArrayList<>();      // triples of vertex ids
        List<Integer> fanTriFace = new ArrayList<>(); // face id per fan triangle
        float signedVolume6 = 0f;
        Map<Integer, FaceGeom> geom = new HashMap<>();

        for (EditableFace face : faces) {
            int[] loop = face.loop();
            FaceGeom g = FaceGeom.of(mesh, loop, areaEps, planarTol);
            geom.put(face.faceId(), g);
            for (int i = 0; i < loop.length; i++) {
                int a = loop[i];
                int b = loop[(i + 1) % loop.length];
                undirectedEdges.merge(edgeKey(Math.min(a, b), Math.max(a, b)), 1, Integer::sum);
            }
            if (!g.degenerate) {
                Vector3f v0 = mesh.position(loop[0]);
                for (int i = 1; i < loop.length - 1; i++) {
                    Vector3f v1 = mesh.position(loop[i]);
                    Vector3f v2 = mesh.position(loop[i + 1]);
                    fanTris.add(new int[]{loop[0], loop[i], loop[i + 1]});
                    fanTriFace.add(face.faceId());
                    signedVolume6 += v0.dot(new Vector3f(v1).cross(v2, new Vector3f()));
                }
            }
        }
        float signedVolume = signedVolume6 / 6f;

        int boundaryEdges = 0;
        boolean nonManifold = false;
        for (int shares : undirectedEdges.values()) {
            if (shares == 1) {
                boundaryEdges++;
            } else if (shares > 2) {
                nonManifold = true;
            }
        }
        boolean closed = boundaryEdges == 0 && !nonManifold && !faces.isEmpty();
        float volumeEps = Math.max(1e-12f, 1e-6f * diag * diag * diag);
        boolean hasVolume = Math.abs(signedVolume) > volumeEps;

        String reason = null;
        boolean parityMode = false;
        if (faces.isEmpty()) {
            reason = "no_faces";
        } else if (nonManifold) {
            reason = "non_manifold";
        } else if (boundaryEdges > 0) {
            reason = "open_mesh";
        } else if (!hasVolume) {
            reason = "zero_volume";
        } else if (fanTris.size() > PARITY_TRIANGLE_LIMIT) {
            reason = "too_large"; // centroid-dot still classifies below
        } else {
            parityMode = true;
        }

        java.util.Set<Integer> contractViolations = new java.util.LinkedHashSet<>();
        Map<Integer, String> skippedDegenerate = new java.util.LinkedHashMap<>();
        if (importWarnings != null) {
            for (MeshImporter.ImportWarning w : importWarnings) {
                if (w.kind() == MeshImporter.ImportWarning.Kind.BOUNDARY_WALK_FAILED) {
                    contractViolations.add(w.faceId());
                } else if (w.kind() == MeshImporter.ImportWarning.Kind.DEGENERATE_FACE) {
                    skippedDegenerate.put(w.faceId(), w.message());
                }
            }
        }

        List<FaceResult> results = new ArrayList<>(faces.size() + skippedDegenerate.size());
        for (EditableFace face : faces) {
            int faceId = face.faceId();
            FaceGeom g = geom.get(faceId);
            Orientation orientation = Orientation.INDETERMINATE;
            String method = "none";
            if (!g.degenerate) {
                if (parityMode) {
                    Orientation parity = parityProbe(mesh, g, faceId, fanTris, fanTriFace, diag);
                    if (parity != null) {
                        orientation = parity;
                        method = "parity";
                    } else {
                        Orientation dot = centroidDot(g, partCentroid);
                        orientation = dot;
                        method = dot == Orientation.INDETERMINATE ? "none" : "centroid_dot";
                    }
                } else if ("too_large".equals(reason)) {
                    Orientation dot = centroidDot(g, partCentroid);
                    orientation = dot;
                    method = dot == Orientation.INDETERMINATE ? "none" : "centroid_dot";
                } else {
                    Orientation flush = bboxFlush(mesh, face, g, bbMin, bbMax, planarTol);
                    if (flush != null) {
                        orientation = flush;
                        method = "bbox_flush";
                    }
                }
            }
            results.add(new FaceResult(faceId, orientation, method,
                    new float[]{g.normal.x, g.normal.y, g.normal.z}, g.area,
                    g.degenerate, g.nonPlanar, contractViolations.contains(faceId)));
        }
        for (Map.Entry<Integer, String> e : skippedDegenerate.entrySet()) {
            results.add(new FaceResult(e.getKey(), Orientation.INDETERMINATE, "none",
                    new float[]{0, 0, 0}, 0f, true, false,
                    contractViolations.contains(e.getKey())));
        }
        results.sort(Comparator.comparingInt(FaceResult::faceId));

        int outward = 0;
        int inward = 0;
        int indeterminate = 0;
        for (FaceResult r : results) {
            if (r.degenerate()) {
                continue;
            }
            switch (r.orientation()) {
                case OUTWARD -> outward++;
                case INWARD -> inward++;
                case INDETERMINATE -> indeterminate++;
            }
        }
        boolean mixed = outward > 0 && inward > 0;

        PartReport report = new PartReport(partName, results.size(), outward, inward, indeterminate,
                mixed, closed, signedVolume, reason, List.copyOf(results), List.of());
        return new PartReport(partName, results.size(), outward, inward, indeterminate,
                mixed, closed, signedVolume, reason, report.faces(), hints(report));
    }

    // ------------------------------------------------------------------ hints

    private static List<String> hints(PartReport r) {
        List<String> hints = new ArrayList<>();
        int[] inverted = r.invertedFaceIds();
        if (inverted.length > 0) {
            hints.add("part '" + r.part() + "': " + inverted.length + " face(s) wind inward "
                    + idsPreview(inverted)
                    + " — reverse those loops (script: replace each face loop with its reverse) "
                    + "or re-export from the source model");
        }
        int[] contract = r.contractViolationFaceIds();
        if (contract.length > 0) {
            hints.add("part '" + r.part() + "': face-id contract violated " + idsPreview(contract)
                    + " — one face id must map to one triangulated simple polygon; "
                    + "split the offending id(s) into one id per polygon");
        }
        int[] degenerate = r.degenerateFaceIds();
        if (degenerate.length > 0) {
            hints.add("part '" + r.part() + "': " + degenerate.length + " degenerate face(s) "
                    + idsPreview(degenerate) + " — near-zero area; delete or rebuild them");
        }
        if (r.indeterminateReason() != null && !"no_faces".equals(r.indeterminateReason())) {
            hints.add("part '" + r.part() + "': orientation is informational only ("
                    + r.indeterminateReason()
                    + ") — open or flat geometry has no defined outward side");
        }
        return List.copyOf(hints);
    }

    private static String idsPreview(int[] ids) {
        StringBuilder sb = new StringBuilder("[");
        int shown = Math.min(ids.length, 8);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(ids[i]);
        }
        if (ids.length > shown) {
            sb.append(", …+").append(ids.length - shown);
        }
        return sb.append(']').toString();
    }

    // -------------------------------------------------------- orientation ops

    /**
     * Parity ray probe. Returns null when every jittered attempt stayed
     * ambiguous (caller falls back to centroid-dot).
     */
    private static Orientation parityProbe(EditableMesh mesh, FaceGeom g, int faceId,
                                           List<int[]> fanTris, List<Integer> fanTriFace,
                                           float diag) {
        float eps = Math.max(1e-6f, 1e-4f * diag);
        for (int attempt = 0; attempt <= JITTERS.length; attempt++) {
            Vector3f dir = new Vector3f(g.normal);
            if (attempt > 0) {
                float[] j = JITTERS[attempt - 1];
                dir.add(j[0], j[1], j[2]).normalize();
            }
            Vector3f origin = new Vector3f(g.centroid).fma(eps, dir);
            int crossings = 0;
            boolean ambiguous = false;
            for (int t = 0; t < fanTris.size(); t++) {
                if (fanTriFace.get(t) == faceId) {
                    continue;
                }
                int[] tri = fanTris.get(t);
                int hit = rayTriangle(origin, dir,
                        mesh.position(tri[0]), mesh.position(tri[1]), mesh.position(tri[2]));
                if (hit == HIT_AMBIGUOUS) {
                    ambiguous = true;
                    break;
                }
                if (hit == HIT_YES) {
                    crossings++;
                }
            }
            if (!ambiguous) {
                return (crossings % 2 == 0) ? Orientation.OUTWARD : Orientation.INWARD;
            }
        }
        return null;
    }

    private static Orientation centroidDot(FaceGeom g, Vector3f partCentroid) {
        Vector3f toFace = new Vector3f(g.centroid).sub(partCentroid);
        float len = toFace.length();
        if (len < 1e-9f) {
            return Orientation.INDETERMINATE;
        }
        float d = g.normal.dot(toFace.div(len));
        if (Math.abs(d) < DOT_DEAD_ZONE) {
            return Orientation.INDETERMINATE;
        }
        return d > 0 ? Orientation.OUTWARD : Orientation.INWARD;
    }

    /**
     * For open meshes: if the face lies flush on one of the part-AABB boundary
     * planes (and the part has extent on that axis), judge the normal against
     * the AABB outward direction. Returns null when not flush.
     */
    private static Orientation bboxFlush(EditableMesh mesh, EditableFace face, FaceGeom g,
                                         Vector3f bbMin, Vector3f bbMax, float tol) {
        float[] min = {bbMin.x, bbMin.y, bbMin.z};
        float[] max = {bbMax.x, bbMax.y, bbMax.z};
        float[] normal = {g.normal.x, g.normal.y, g.normal.z};
        int[] loop = face.loop();
        for (int axis = 0; axis < 3; axis++) {
            if (max[axis] - min[axis] <= tol) {
                continue; // flat part on this axis — no outward side
            }
            for (int side = 0; side < 2; side++) {
                float plane = side == 0 ? min[axis] : max[axis];
                boolean flush = true;
                for (int v : loop) {
                    float c = axisCoord(mesh.position(v), axis);
                    if (Math.abs(c - plane) > tol) {
                        flush = false;
                        break;
                    }
                }
                if (flush) {
                    float outwardSign = side == 0 ? -1f : 1f;
                    float n = normal[axis];
                    if (Math.abs(n) < 0.5f) {
                        return Orientation.INDETERMINATE; // flush but normal not axis-dominant
                    }
                    return Math.signum(n) == outwardSign ? Orientation.OUTWARD : Orientation.INWARD;
                }
            }
        }
        return null;
    }

    private static float axisCoord(Vector3f p, int axis) {
        return switch (axis) {
            case 0 -> p.x;
            case 1 -> p.y;
            default -> p.z;
        };
    }

    // ------------------------------------------------------ ray intersection

    private static final int HIT_NO = 0;
    private static final int HIT_YES = 1;
    private static final int HIT_AMBIGUOUS = 2;

    /** Möller–Trumbore with grazing detection. */
    private static int rayTriangle(Vector3f origin, Vector3f dir,
                                   Vector3f a, Vector3f b, Vector3f c) {
        float e1x = b.x - a.x;
        float e1y = b.y - a.y;
        float e1z = b.z - a.z;
        float e2x = c.x - a.x;
        float e2y = c.y - a.y;
        float e2z = c.z - a.z;
        float px = dir.y * e2z - dir.z * e2y;
        float py = dir.z * e2x - dir.x * e2z;
        float pz = dir.x * e2y - dir.y * e2x;
        float det = e1x * px + e1y * py + e1z * pz;
        if (Math.abs(det) < 1e-12f) {
            return HIT_NO; // parallel — jitter pass covers true grazes
        }
        float inv = 1f / det;
        float tx = origin.x - a.x;
        float ty = origin.y - a.y;
        float tz = origin.z - a.z;
        float u = (tx * px + ty * py + tz * pz) * inv;
        float qx = ty * e1z - tz * e1y;
        float qy = tz * e1x - tx * e1z;
        float qz = tx * e1y - ty * e1x;
        float v = (dir.x * qx + dir.y * qy + dir.z * qz) * inv;
        float t = (e2x * qx + e2y * qy + e2z * qz) * inv;
        float edgeEps = 1e-5f;
        if (u < -edgeEps || v < -edgeEps || u + v > 1 + edgeEps || t <= 1e-6f) {
            return HIT_NO;
        }
        if (u < edgeEps || v < edgeEps || u + v > 1 - edgeEps || t < 1e-4f) {
            return HIT_AMBIGUOUS; // edge/vertex graze or immediate self-hit
        }
        return HIT_YES;
    }

    // ------------------------------------------------------------- face geom

    private record FaceGeom(Vector3f normal, Vector3f centroid, float area,
                            boolean degenerate, boolean nonPlanar) {

        static FaceGeom of(EditableMesh mesh, int[] loop, float areaEps, float planarTol) {
            Vector3f centroid = new Vector3f();
            for (int v : loop) {
                centroid.add(mesh.position(v));
            }
            centroid.div(loop.length);

            // Newell's method — robust for near-planar n-gons.
            float nx = 0;
            float ny = 0;
            float nz = 0;
            for (int i = 0; i < loop.length; i++) {
                Vector3f cur = mesh.position(loop[i]);
                Vector3f next = mesh.position(loop[(i + 1) % loop.length]);
                nx += (cur.y - next.y) * (cur.z + next.z);
                ny += (cur.z - next.z) * (cur.x + next.x);
                nz += (cur.x - next.x) * (cur.y + next.y);
            }
            Vector3f normal = new Vector3f(nx, ny, nz);
            float len = normal.length();
            float area = len * 0.5f;
            boolean degenerate = area < areaEps || len < 1e-12f;
            if (!degenerate) {
                normal.div(len);
            } else {
                normal.set(0, 0, 0);
            }

            boolean nonPlanar = false;
            if (!degenerate && loop.length > 3) {
                for (int v : loop) {
                    Vector3f d = new Vector3f(mesh.position(v)).sub(centroid);
                    if (Math.abs(d.dot(normal)) > planarTol) {
                        nonPlanar = true;
                        break;
                    }
                }
            }
            return new FaceGeom(normal, centroid, area, degenerate, nonPlanar);
        }
    }

    private static long edgeKey(int src, int dst) {
        return ((long) src << 32) | (dst & 0xFFFFFFFFL);
    }
}
