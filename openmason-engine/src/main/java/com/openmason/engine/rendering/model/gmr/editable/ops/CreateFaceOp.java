package com.openmason.engine.rendering.model.gmr.editable.ops;

import com.openmason.engine.rendering.model.gmr.editable.EditableMesh;

/**
 * Create a face from existing vertices; the given order IS the winding
 * (selection order in the tool, unchanged UX). Concave selections render
 * correctly because triangulation happens at render derivation via
 * {@code PolygonTriangulator} — the legacy fan-triangulation hazard is gone.
 */
public final class CreateFaceOp {

    private CreateFaceOp() {
        // Static op — no instantiation
    }

    /**
     * @param mesh      Mesh to mutate
     * @param vertexIds Ordered vertex ids (≥ 3, no repeats, all valid)
     * @return the new face id, or -1 if the loop is invalid
     */
    public static int apply(EditableMesh mesh, int[] vertexIds) {
        if (vertexIds == null || vertexIds.length < 3) {
            return -1;
        }
        for (int v : vertexIds) {
            if (!mesh.isValidVertex(v)) {
                return -1;
            }
        }
        try {
            return mesh.addFace(vertexIds);
        } catch (IllegalArgumentException e) {
            return -1; // repeated vertex ids
        }
    }
}
