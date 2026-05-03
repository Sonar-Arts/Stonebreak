package com.openmason.engine.rendering.model.gmr.parts.shapes;

import com.openmason.engine.rendering.model.ModelPart;
import org.joml.Vector3f;

/**
 * Triangular prism with a sloped top. The slope rises from front (-Z, low) to
 * back (+Z, high).
 * <ul>
 *   <li>Face 0: Bottom quad (4 verts, 2 tris)</li>
 *   <li>Face 1: Back vertical quad (4 verts, 2 tris)</li>
 *   <li>Face 2: Slope quad (4 verts, 2 tris)</li>
 *   <li>Face 3: Left triangle side (3 verts, 1 tri)</li>
 *   <li>Face 4: Right triangle side (3 verts, 1 tri)</li>
 *   <li>Total: 18 vertices, 24 indices, 5 logical faces</li>
 * </ul>
 */
public final class WedgeShape implements PartShape {

    @Override public String displayName() { return "Wedge"; }
    @Override public String description() { return "Triangular prism with a sloped top"; }
    @Override public String iconFilename() { return "wedge.svg"; }

    @Override
    public ModelPart create(String name, Vector3f size) {
        float hw = size.x / 2.0f;
        float hh = size.y / 2.0f;
        float hd = size.z / 2.0f;

        float[] vertices = {
            // Face 0: Bottom (CCW viewed from -Y)
            -hw, -hh,  hd,
             hw, -hh,  hd,
             hw, -hh, -hd,
            -hw, -hh, -hd,

            // Face 1: Back (CCW viewed from +Z)
             hw, -hh,  hd,
            -hw, -hh,  hd,
            -hw,  hh,  hd,
             hw,  hh,  hd,

            // Face 2: Slope (CCW viewed from outward up-and-forward normal)
            -hw, -hh, -hd,
             hw, -hh, -hd,
             hw,  hh,  hd,
            -hw,  hh,  hd,

            // Face 3: Left side triangle (CCW viewed from -X)
            -hw, -hh, -hd,
            -hw, -hh,  hd,
            -hw,  hh,  hd,

            // Face 4: Right side triangle (CCW viewed from +X)
             hw, -hh,  hd,
             hw, -hh, -hd,
             hw,  hh,  hd,
        };

        float[] texCoords = {
            0.0f, 1.0f,   1.0f, 1.0f,   1.0f, 0.0f,   0.0f, 0.0f,
            0.0f, 1.0f,   1.0f, 1.0f,   1.0f, 0.0f,   0.0f, 0.0f,
            0.0f, 1.0f,   1.0f, 1.0f,   1.0f, 0.0f,   0.0f, 0.0f,
            0.0f, 1.0f,   1.0f, 1.0f,   1.0f, 0.0f,
            0.0f, 1.0f,   1.0f, 1.0f,   1.0f, 0.0f,
        };

        int[] indices = {
            0, 1, 2,   2, 3, 0,
            4, 5, 6,   6, 7, 4,
            8, 9, 10,  10, 11, 8,
            12, 13, 14,
            15, 16, 17,
        };

        return new ModelPart(name, new Vector3f(0, 0, 0), vertices, texCoords, indices, null);
    }

    @Override
    public int[] triangleToFaceId() {
        // 8 triangles, 5 faces: 2/2/2/1/1
        return new int[]{0, 0, 1, 1, 2, 2, 3, 4};
    }
}
