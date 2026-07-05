package com.openmason.engine.rendering.model.gmr.editable.ops;

import com.openmason.engine.rendering.model.gmr.editable.EditableMesh;

/**
 * Delete a face, leaving its vertices in place (holes and later re-fills are
 * a feature; orphan vertices are allowed). The caller is responsible for
 * removing the face's texture mapping ({@code FaceTextureManager}).
 */
public final class DeleteFaceOp {

    private DeleteFaceOp() {
        // Static op — no instantiation
    }

    /** @return true if the face existed and was removed */
    public static boolean apply(EditableMesh mesh, int faceId) {
        return mesh.removeFace(faceId);
    }
}
