package com.openmason.engine.rendering.model.gmr.parts.shapes;

import com.openmason.engine.rendering.model.ModelPart;
import org.joml.Vector3f;

/**
 * Dual-faced flat pane on the XY plane.
 * <p>8 vertices (4 per face) with a thin Z offset to prevent z-fighting.
 * 12 indices, 2 logical faces (Front +Z, Back -Z). Same texture on both sides.
 */
public final class PaneShape implements PartShape {

    @Override public String displayName() { return "Pane"; }
    @Override public String description() { return "Flat single-face quad for thin surfaces like leaves or signs"; }
    @Override public String iconFilename() { return "pane.svg"; }

    @Override
    public ModelPart create(String name, Vector3f size) {
        float hw = size.x / 2.0f;
        float hh = size.y / 2.0f;
        float hd = 0.005f; // thin offset to prevent z-fighting

        float[] vertices = {
            // Face 0: Front (+Z)
            -hw, -hh,  hd,
             hw, -hh,  hd,
             hw,  hh,  hd,
            -hw,  hh,  hd,
            // Face 1: Back (-Z)
             hw, -hh, -hd,
            -hw, -hh, -hd,
            -hw,  hh, -hd,
             hw,  hh, -hd,
        };

        float[] texCoords = {
            0.0f, 1.0f,   1.0f, 1.0f,   1.0f, 0.0f,   0.0f, 0.0f,
            0.0f, 1.0f,   1.0f, 1.0f,   1.0f, 0.0f,   0.0f, 0.0f,
        };

        int[] indices = {
            0, 1, 2,   2, 3, 0,
            4, 5, 6,   6, 7, 4,
        };

        return new ModelPart(name, new Vector3f(0, 0, 0), vertices, texCoords, indices, null);
    }

    @Override
    public int[] triangleToFaceId() {
        return new int[]{0, 0, 1, 1};
    }
}
