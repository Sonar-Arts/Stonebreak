package com.openmason.engine.rendering.model.gmr.parts.shapes;

import com.openmason.engine.rendering.model.ModelPart;
import org.joml.Vector3f;

/**
 * Single-sided flat quad on the XY plane facing +Z.
 * <p>4 vertices, 6 indices, 1 logical face. Ideal for decals or sprite-style UI.
 */
public final class SpriteShape implements PartShape {

    @Override public String displayName() { return "Sprite"; }
    @Override public String description() { return "Single-sided flat quad with one universal texture face"; }
    @Override public String iconFilename() { return "sprite.svg"; }

    @Override
    public ModelPart create(String name, Vector3f size) {
        float hw = size.x / 2.0f;
        float hh = size.y / 2.0f;

        float[] vertices = {
            -hw, -hh, 0.0f,
             hw, -hh, 0.0f,
             hw,  hh, 0.0f,
            -hw,  hh, 0.0f,
        };

        float[] texCoords = {
            0.0f, 1.0f,
            1.0f, 1.0f,
            1.0f, 0.0f,
            0.0f, 0.0f,
        };

        int[] indices = { 0, 1, 2, 2, 3, 0 };

        return new ModelPart(name, new Vector3f(0, 0, 0), vertices, texCoords, indices, null);
    }

    @Override
    public int[] triangleToFaceId() {
        return new int[]{0, 0};
    }
}
