package com.openmason.engine.rendering.model.gmr.editable.ops;

import com.openmason.engine.rendering.model.gmr.GMRConstants;
import com.openmason.engine.rendering.model.gmr.editable.EditableFace;
import com.openmason.engine.rendering.model.gmr.editable.EditableMesh;
import com.openmason.engine.rendering.model.gmr.editable.PolygonTriangulator;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Blender-style individual face extrude: duplicate each selected face's loop,
 * translate the duplicates along the face normal, keep the ORIGINAL face id
 * on the moved cap, and connect old and new boundaries with a ring of side
 * quads (new faces — the caller assigns their material/mapping).
 *
 * <p>The cap keeps its authored corner UVs (same shape, just moved). Region
 * extrude of adjacent faces (dissolving shared interior edges) is
 * deliberately not implemented — Open Mason models are predominantly
 * independent quads, and per-face extrude is the v1 the plan calls for.
 */
public final class ExtrudeFacesOp {

    /**
     * @param faceId       The extruded face (keeps the moved cap)
     * @param sideFaceIds  New side-quad ids, one per boundary edge
     * @param capVertexIds New cap vertex ids (cap loop order)
     */
    public record FaceResult(int faceId, int[] sideFaceIds, int[] capVertexIds) {
    }

    private ExtrudeFacesOp() {
        // Static op — no instantiation
    }

    /**
     * @param mesh    Mesh to mutate
     * @param faceIds Faces to extrude (each individually)
     * @param offset  Distance along each face's outward normal (may be negative)
     * @return Per-face results (skips unknown/degenerate faces); empty if none applied
     */
    public static List<FaceResult> apply(EditableMesh mesh, int[] faceIds, float offset) {
        List<FaceResult> results = new ArrayList<>();
        if (faceIds == null) {
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
                continue; // degenerate face — no meaningful normal to extrude along
            }
            normal.normalize().mul(offset);

            // Duplicate the loop, translated along the normal.
            int[] cap = new int[n];
            for (int i = 0; i < n; i++) {
                cap[i] = mesh.addVertex(new Vector3f(positions[i]).add(normal));
            }

            // The original face id keeps the moved cap; same shape → authored
            // UVs stay valid.
            mesh.replaceFaceLoop(faceId, cap, face.cornerUVs());

            // Side quads (old edge → new edge), wound outward.
            int[] sideIds = new int[n];
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                sideIds[i] = mesh.addFace(new int[]{loop[i], loop[j], cap[j], cap[i]});
            }

            results.add(new FaceResult(faceId, sideIds, cap));
        }

        return results;
    }
}
