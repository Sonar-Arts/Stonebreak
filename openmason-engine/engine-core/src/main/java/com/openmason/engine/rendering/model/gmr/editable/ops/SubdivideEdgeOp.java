package com.openmason.engine.rendering.model.gmr.editable.ops;

import com.openmason.engine.rendering.model.gmr.editable.EditableFace;
import com.openmason.engine.rendering.model.gmr.editable.EditableMesh;
import org.joml.Vector3f;

import java.util.List;

/**
 * Insert a vertex on the edge (vA, vB) at parameter {@code t} and splice it
 * into the loop of every face bordering that edge.
 *
 * <p>Exactly ONE shared vertex is created — no coincident duplicates, no
 * post-hoc welding, and no collinear-normal workarounds: the new vertex sits
 * legitimately on its faces' loops and Newell/ear-clipping handle the
 * collinear triple at render derivation.
 *
 * <p>UVs need no updating: face UV regions are unchanged and corner UVs are
 * re-projected from regions on the next render-mesh build.
 */
public final class SubdivideEdgeOp {

    /**
     * @param newVertexId     Id of the inserted vertex
     * @param affectedFaceIds Faces whose loops now include the new vertex
     */
    public record Result(int newVertexId, int[] affectedFaceIds) {
    }

    private SubdivideEdgeOp() {
        // Static op — no instantiation
    }

    /**
     * @param mesh Mesh to mutate
     * @param vA   First edge endpoint (vertex id)
     * @param vB   Second edge endpoint (vertex id)
     * @param t    Position parameter along A→B, clamped to (0..1)
     * @return Result, or {@code null} if (vA, vB) is not an edge of any face
     */
    public static Result apply(EditableMesh mesh, int vA, int vB, float t) {
        if (vA == vB || !mesh.isValidVertex(vA) || !mesh.isValidVertex(vB)) {
            return null;
        }

        List<EditableFace> facesOnEdge = mesh.facesWithEdge(vA, vB);
        if (facesOnEdge.isEmpty()) {
            return null;
        }

        float clamped = Math.clamp(t, 0.001f, 0.999f);
        Vector3f position = mesh.position(vA).lerp(mesh.position(vB), clamped);
        int newVertex = mesh.addVertex(position);

        int[] affected = new int[facesOnEdge.size()];
        for (int f = 0; f < facesOnEdge.size(); f++) {
            EditableFace face = facesOnEdge.get(f);
            int[] loop = face.loop();
            int pairIndex = face.adjacentPairIndex(vA, vB);

            int[] newLoop = new int[loop.length + 1];
            // Insert the new vertex between positions pairIndex and pairIndex+1.
            for (int i = 0; i <= pairIndex; i++) {
                newLoop[i] = loop[i];
            }
            newLoop[pairIndex + 1] = newVertex;
            for (int i = pairIndex + 1; i < loop.length; i++) {
                newLoop[i + 1] = loop[i];
            }

            // Authored UVs stay valid: the new corner interpolates the pair's
            // UVs at the split parameter (orientation-aware).
            float[] newUVs = null;
            float[] uvs = face.cornerUVs();
            if (uvs != null) {
                float tLocal = loop[pairIndex] == vA ? clamped : 1.0f - clamped;
                int i0 = pairIndex;
                int i1 = (pairIndex + 1) % loop.length;
                float u = uvs[i0 * 2]     + (uvs[i1 * 2]     - uvs[i0 * 2])     * tLocal;
                float v = uvs[i0 * 2 + 1] + (uvs[i1 * 2 + 1] - uvs[i0 * 2 + 1]) * tLocal;

                newUVs = new float[(loop.length + 1) * 2];
                System.arraycopy(uvs, 0, newUVs, 0, (pairIndex + 1) * 2);
                newUVs[(pairIndex + 1) * 2]     = u;
                newUVs[(pairIndex + 1) * 2 + 1] = v;
                System.arraycopy(uvs, (pairIndex + 1) * 2, newUVs, (pairIndex + 2) * 2,
                    (loop.length - pairIndex - 1) * 2);
            }

            mesh.replaceFaceLoop(face.faceId(), newLoop, newUVs);
            affected[f] = face.faceId();
        }

        return new Result(newVertex, affected);
    }
}
