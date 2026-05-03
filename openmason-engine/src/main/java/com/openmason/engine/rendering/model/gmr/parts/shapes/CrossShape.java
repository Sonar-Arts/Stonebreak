package com.openmason.engine.rendering.model.gmr.parts.shapes;

import com.openmason.engine.rendering.model.ModelPart;
import org.joml.Vector3f;

/**
 * Foliage-style cross: two perpendicular double-faced panes forming an X.
 * Each pane is dual-faced (front + back) so the texture is visible from either
 * side. Panes are oriented at 45° so corners reach to ±size.x/2 along X and
 * ±size.z/2 along Z.
 * <ul>
 *   <li>Total: 16 vertices, 24 indices, 4 logical faces</li>
 * </ul>
 */
public final class CrossShape implements PartShape {

    @Override public String displayName() { return "Cross"; }
    @Override public String description() { return "Two perpendicular double-faced panes forming an X (foliage-style)"; }
    @Override public String iconFilename() { return "cross.svg"; }

    @Override
    public ModelPart create(String name, Vector3f size) {
        float hw = size.x / 2.0f;
        float hh = size.y / 2.0f;
        float hd = size.z / 2.0f;

        float[] vertices = {
            // Face 0: Pane 1 front
            -hw, -hh, -hd,
             hw, -hh,  hd,
             hw,  hh,  hd,
            -hw,  hh, -hd,
            // Face 1: Pane 1 back
             hw, -hh,  hd,
            -hw, -hh, -hd,
            -hw,  hh, -hd,
             hw,  hh,  hd,
            // Face 2: Pane 2 front
            -hw, -hh,  hd,
             hw, -hh, -hd,
             hw,  hh, -hd,
            -hw,  hh,  hd,
            // Face 3: Pane 2 back
             hw, -hh, -hd,
            -hw, -hh,  hd,
            -hw,  hh,  hd,
             hw,  hh, -hd,
        };

        float[] texCoords = new float[4 * 4 * 2];
        for (int face = 0; face < 4; face++) {
            int o = face * 8;
            texCoords[o    ] = 0.0f; texCoords[o + 1] = 1.0f;
            texCoords[o + 2] = 1.0f; texCoords[o + 3] = 1.0f;
            texCoords[o + 4] = 1.0f; texCoords[o + 5] = 0.0f;
            texCoords[o + 6] = 0.0f; texCoords[o + 7] = 0.0f;
        }

        int[] indices = new int[24];
        int idx = 0;
        for (int face = 0; face < 4; face++) {
            int base = face * 4;
            indices[idx++] = base;
            indices[idx++] = base + 1;
            indices[idx++] = base + 2;
            indices[idx++] = base + 2;
            indices[idx++] = base + 3;
            indices[idx++] = base;
        }

        return new ModelPart(name, new Vector3f(0, 0, 0), vertices, texCoords, indices, null);
    }

    @Override
    public int[] triangleToFaceId() {
        return new int[]{0, 0, 1, 1, 2, 2, 3, 3};
    }
}
