package com.openmason.engine.rendering.model.gmr.editable.ops;

import com.openmason.engine.rendering.model.gmr.editable.EditableFace;
import com.openmason.engine.rendering.model.gmr.editable.EditableMesh;
import com.openmason.engine.rendering.model.gmr.editable.PolygonTriangulator;
import org.joml.Vector3f;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Scale the vertices of the selected faces about a pivot (Blender S in face
 * mode). Pure position edit — no topology change. Vertices shared with
 * unselected faces move too, exactly as Blender's face-mode scale behaves.
 */
public final class ScaleFacesOp {

    /** @param movedVertexIds Vertices whose positions changed */
    public record Result(int[] movedVertexIds) {
    }

    private ScaleFacesOp() {
        // Static op — no instantiation
    }

    /**
     * @param mesh    Mesh to mutate
     * @param faceIds Faces whose vertices scale
     * @param factor  Scale factor (1 = no-op)
     * @param pivot   Scale origin, or {@code null} for the area-weighted
     *                centroid of the selected faces
     * @return Result, or {@code null} if no valid faces were given
     */
    public static Result apply(EditableMesh mesh, int[] faceIds, float factor, Vector3f pivot) {
        Set<Integer> vertexIds = new LinkedHashSet<>();
        Vector3f weightedCentroid = new Vector3f();
        float totalArea = 0.0f;

        for (int faceId : faceIds != null ? faceIds : new int[0]) {
            EditableFace face = mesh.face(faceId);
            if (face == null) {
                continue;
            }
            int n = face.loopLength();
            Vector3f[] loop = new Vector3f[n];
            Vector3f centroid = new Vector3f();
            for (int i = 0; i < n; i++) {
                vertexIds.add(face.vertexAt(i));
                loop[i] = mesh.position(face.vertexAt(i));
                centroid.add(loop[i]);
            }
            centroid.div(n);
            float area = PolygonTriangulator.newellNormal(loop).length() * 0.5f;
            weightedCentroid.add(centroid.mul(area));
            totalArea += area;
        }

        if (vertexIds.isEmpty()) {
            return null;
        }

        Vector3f origin;
        if (pivot != null) {
            origin = new Vector3f(pivot);
        } else if (totalArea > 1e-12f) {
            origin = weightedCentroid.div(totalArea);
        } else {
            // All-degenerate selection: plain vertex average.
            origin = new Vector3f();
            for (int v : vertexIds) {
                origin.add(mesh.position(v));
            }
            origin.div(vertexIds.size());
        }

        int[] moved = new int[vertexIds.size()];
        int i = 0;
        for (int v : vertexIds) {
            Vector3f p = mesh.position(v).sub(origin).mul(factor).add(origin);
            mesh.setPosition(v, p);
            moved[i++] = v;
        }

        return new Result(moved);
    }
}
