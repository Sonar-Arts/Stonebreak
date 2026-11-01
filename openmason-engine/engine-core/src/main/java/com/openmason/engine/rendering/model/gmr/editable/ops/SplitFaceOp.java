package com.openmason.engine.rendering.model.gmr.editable.ops;

import com.openmason.engine.rendering.model.gmr.GMRConstants;
import com.openmason.engine.rendering.model.gmr.editable.EditableFace;
import com.openmason.engine.rendering.model.gmr.editable.EditableMesh;
import com.openmason.engine.rendering.model.gmr.editable.PolygonTriangulator;
import com.openmason.engine.rendering.model.gmr.uv.FaceProjectionUtil;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Insert an edge between two existing vertices, splitting every face whose
 * loop contains both vertices non-adjacently into two faces along that edge
 * (Blender's "connect vertices", the legacy {@code insertEdgeBetweenVertices}).
 *
 * <p>For each split, the half whose loop runs vA→…→vB keeps the original face
 * id (materials/UV region follow it) and the other half gets a fresh id.
 * No vertices are created.
 *
 * <p>Each {@link Split} carries the UV propagation parameters (the split
 * edge's parametric position across the face and its dominant tangent-space
 * direction) so the caller can divide the parent's UV region between the two
 * halves via {@code FaceTextureManager.propagateSplitUV(parent, parent, new,
 * t, horizontal)} — the same convention the legacy processor used.
 */
public final class SplitFaceOp {

    /**
     * One face split.
     *
     * @param parentFaceId Face that was split (keeps its id on one half —
     *                     the [0..t] side of the UV region)
     * @param newFaceId    Freshly allocated id of the other half ([t..1])
     * @param uvT          Parametric position of the split within the face bounds
     * @param uvHorizontal Whether the region divides along U (true) or V
     */
    public record Split(int parentFaceId, int newFaceId, float uvT, boolean uvHorizontal) {
    }

    private SplitFaceOp() {
        // Static op — no instantiation
    }

    /**
     * @return Splits performed (empty if no face had both vertices non-adjacent)
     */
    public static List<Split> apply(EditableMesh mesh, int vA, int vB) {
        List<Split> splits = new ArrayList<>();
        if (vA == vB || !mesh.isValidVertex(vA) || !mesh.isValidVertex(vB)) {
            return splits;
        }

        // Snapshot candidates first — splitting adds faces while iterating.
        List<EditableFace> candidates = new ArrayList<>();
        for (EditableFace face : mesh.facesWithVertex(vA)) {
            if (face.containsVertex(vB) && face.adjacentPairIndex(vA, vB) < 0) {
                candidates.add(face);
            }
        }

        for (EditableFace face : candidates) {
            int[] loop = face.loop();
            float[] uvs = face.cornerUVs();
            int ia = face.indexOf(vA);
            int ib = face.indexOf(vB);

            // UV params from the ORIGINAL loop, before mutation.
            float[] uvParams = computeSplitUvParams(mesh, loop, vA, vB);

            // Half A: vA → … → vB (inclusive), following loop order.
            int lenA = ((ib - ia) + loop.length) % loop.length + 1;
            int[] halfA = new int[lenA];
            float[] uvsA = uvs != null ? new float[lenA * 2] : null;
            for (int i = 0; i < lenA; i++) {
                int src = (ia + i) % loop.length;
                halfA[i] = loop[src];
                if (uvsA != null) {
                    uvsA[i * 2]     = uvs[src * 2];
                    uvsA[i * 2 + 1] = uvs[src * 2 + 1];
                }
            }

            // Half B: vB → … → vA (inclusive), continuing around.
            int lenB = loop.length - lenA + 2;
            int[] halfB = new int[lenB];
            float[] uvsB = uvs != null ? new float[lenB * 2] : null;
            for (int i = 0; i < lenB; i++) {
                int src = (ib + i) % loop.length;
                halfB[i] = loop[src];
                if (uvsB != null) {
                    uvsB[i * 2]     = uvs[src * 2];
                    uvsB[i * 2 + 1] = uvs[src * 2 + 1];
                }
            }

            mesh.replaceFaceLoop(face.faceId(), halfA, uvsA);
            int newFaceId = mesh.addFace(halfB, uvsB);
            splits.add(new Split(face.faceId(), newFaceId, uvParams[0], uvParams[1] > 0.5f));
        }

        return splits;
    }

    /**
     * Parametric position and dominant direction of the split edge within the
     * face's tangent-space bounds — the legacy {@code computeSplitUVParams}
     * math over authoritative loop positions (Newell normal, no robust-normal
     * brute force needed).
     *
     * @return {@code [t, horizontal ? 1 : 0]}
     */
    private static float[] computeSplitUvParams(EditableMesh mesh, int[] loop, int vA, int vB) {
        Vector3f[] positions = new Vector3f[loop.length];
        for (int i = 0; i < loop.length; i++) {
            positions[i] = mesh.position(loop[i]);
        }

        Vector3f normal = PolygonTriangulator.newellNormal(positions);
        Vector3f[] frame = FaceProjectionUtil.computeTangentFrame(normal);
        if (frame == null) {
            return new float[]{0.5f, 1.0f};
        }
        Vector3f tangent = frame[0];
        Vector3f bitangent = frame[1];
        Vector3f ref = positions[0];

        float minS = Float.MAX_VALUE, maxS = -Float.MAX_VALUE;
        float minT = Float.MAX_VALUE, maxT = -Float.MAX_VALUE;
        for (Vector3f p : positions) {
            float s = projS(p, ref, tangent);
            float t = projS(p, ref, bitangent);
            minS = Math.min(minS, s);
            maxS = Math.max(maxS, s);
            minT = Math.min(minT, t);
            maxT = Math.max(maxT, t);
        }

        Vector3f pa = mesh.position(vA);
        Vector3f pb = mesh.position(vB);
        float sA = projS(pa, ref, tangent);
        float tA = projS(pa, ref, bitangent);
        float sB = projS(pb, ref, tangent);
        float tB = projS(pb, ref, bitangent);

        // Edge runs along bitangent (T) → horizontal split (divides S range); else vertical.
        boolean horizontal = Math.abs(tB - tA) >= Math.abs(sB - sA);

        float t;
        if (horizontal) {
            float rangeS = maxS - minS;
            t = (rangeS > GMRConstants.POSITION_EPSILON) ? ((sA + sB) / 2.0f - minS) / rangeS : 0.5f;
        } else {
            float rangeT = maxT - minT;
            t = (rangeT > GMRConstants.POSITION_EPSILON) ? ((tA + tB) / 2.0f - minT) / rangeT : 0.5f;
        }

        return new float[]{Math.clamp(t, 0.0f, 1.0f), horizontal ? 1.0f : 0.0f};
    }

    private static float projS(Vector3f p, Vector3f ref, Vector3f axis) {
        return (p.x - ref.x) * axis.x + (p.y - ref.y) * axis.y + (p.z - ref.z) * axis.z;
    }
}
