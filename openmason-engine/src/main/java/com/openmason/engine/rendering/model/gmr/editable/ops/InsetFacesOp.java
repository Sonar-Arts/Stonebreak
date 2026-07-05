package com.openmason.engine.rendering.model.gmr.editable.ops;

import com.openmason.engine.rendering.model.gmr.GMRConstants;
import com.openmason.engine.rendering.model.gmr.editable.EditableFace;
import com.openmason.engine.rendering.model.gmr.editable.EditableMesh;
import com.openmason.engine.rendering.model.gmr.editable.PolygonTriangulator;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Blender-style individual face inset: for each selected face, create a
 * smaller inner face connected to the original boundary by a ring of border
 * quads.
 *
 * <p>Inner corners are offset along the inward angle bisector by
 * {@code amount / cos(halfAngle)} so the inset has EVEN thickness on every
 * edge — a plain centroid-lerp looks visibly wrong on elongated faces.
 *
 * <p>The ORIGINAL face id keeps the inner cap (its material and UV region
 * follow the face the user keeps editing, matching Blender); the border quads
 * are new faces — the caller assigns their material/mapping. Authored corner
 * UVs are dropped from the cap (its shape changed; UVs re-project).
 */
public final class InsetFacesOp {

    /**
     * @param faceId        The inset face (keeps the inner cap)
     * @param borderFaceIds New border-quad ids, one per boundary edge
     * @param innerVertexIds New inner-loop vertex ids (cap loop order)
     */
    public record FaceResult(int faceId, int[] borderFaceIds, int[] innerVertexIds) {
    }

    private InsetFacesOp() {
        // Static op — no instantiation
    }

    /**
     * @param mesh    Mesh to mutate
     * @param faceIds Faces to inset (each individually)
     * @param amount  Inset thickness in model units (> 0)
     * @return Per-face results (skips unknown face ids); empty if none applied
     */
    public static List<FaceResult> apply(EditableMesh mesh, int[] faceIds, float amount) {
        List<FaceResult> results = new ArrayList<>();
        if (faceIds == null || amount <= 0.0f) {
            return results;
        }

        for (int faceId : faceIds) {
            EditableFace face = mesh.face(faceId);
            if (face == null) {
                continue;
            }

            int n = face.loopLength();
            int[] loop = face.loop();
            Vector3f[] positions = new Vector3f[n];
            for (int i = 0; i < n; i++) {
                positions[i] = mesh.position(loop[i]);
            }

            Vector3f normal = PolygonTriangulator.newellNormal(positions);
            if (normal.lengthSquared() < GMRConstants.DEGENERATE_NORMAL_EPSILON_SQ) {
                continue; // degenerate face — nothing sensible to inset
            }
            normal.normalize();

            // Inner corner per boundary corner: inward bisector, even thickness.
            int[] inner = new int[n];
            for (int i = 0; i < n; i++) {
                Vector3f prev = positions[(i + n - 1) % n];
                Vector3f cur = positions[i];
                Vector3f next = positions[(i + 1) % n];

                Vector3f edgeIn = new Vector3f(cur).sub(prev);
                Vector3f edgeOut = new Vector3f(next).sub(cur);

                // In-plane inward edge normals: N × e points inside a CCW loop.
                Vector3f nIn = new Vector3f(normal).cross(edgeIn);
                Vector3f nOut = new Vector3f(normal).cross(edgeOut);
                if (nIn.lengthSquared() < GMRConstants.DEGENERATE_NORMAL_EPSILON_SQ
                        || nOut.lengthSquared() < GMRConstants.DEGENERATE_NORMAL_EPSILON_SQ) {
                    inner[i] = mesh.addVertex(cur); // zero-length edge — no offset
                    continue;
                }
                nIn.normalize();
                nOut.normalize();

                Vector3f bisector = new Vector3f(nIn).add(nOut);
                float bisectorLen = bisector.length();
                if (bisectorLen < GMRConstants.DEGENERATE_NORMAL_EPSILON) {
                    inner[i] = mesh.addVertex(cur); // 180° reversal — no stable direction
                    continue;
                }
                bisector.div(bisectorLen);

                // Even thickness: distance to each adjacent edge equals amount.
                float cosHalf = bisector.dot(nIn);
                float distance = cosHalf > GMRConstants.DEGENERATE_NORMAL_EPSILON
                    ? amount / cosHalf : amount;

                inner[i] = mesh.addVertex(new Vector3f(bisector).mul(distance).add(cur));
            }

            // The original face id keeps the inner cap.
            mesh.replaceFaceLoop(faceId, inner);

            // Border quads (outer edge → inner edge), wound with the face normal.
            int[] borderIds = new int[n];
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                borderIds[i] = mesh.addFace(new int[]{loop[i], loop[j], inner[j], inner[i]});
            }

            results.add(new FaceResult(faceId, borderIds, inner));
        }

        return results;
    }
}
