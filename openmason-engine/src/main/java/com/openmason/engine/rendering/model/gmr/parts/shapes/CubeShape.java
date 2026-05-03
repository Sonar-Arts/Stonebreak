package com.openmason.engine.rendering.model.gmr.parts.shapes;

import com.openmason.engine.rendering.model.ModelPart;
import org.joml.Vector3f;

/**
 * Standard 6-face cube where the assigned texture appears identically on every face.
 * <p>24 vertices (4 per face), 36 indices, 6 quad faces. Vertex order per face is
 * BL, BR, TR, TL with CCW winding when viewed from the face's outward normal.
 * Face order: Front(+Z), Back(-Z), Left(-X), Right(+X), Top(+Y), Bottom(-Y).
 */
public final class CubeShape implements PartShape {

    @Override public String displayName() { return "Cube"; }
    @Override public String description() { return "Standard 6-face cube with quad topology"; }
    @Override public String iconFilename() { return "cube.svg"; }

    @Override
    public ModelPart create(String name, Vector3f size) {
        float hw = size.x / 2.0f;
        float hh = size.y / 2.0f;
        float hd = size.z / 2.0f;

        float[] vertices = {
            // FRONT (+Z)
            -hw, -hh,  hd,   hw, -hh,  hd,   hw,  hh,  hd,  -hw,  hh,  hd,
            // BACK (-Z)
             hw, -hh, -hd,  -hw, -hh, -hd,  -hw,  hh, -hd,   hw,  hh, -hd,
            // LEFT (-X)
            -hw, -hh, -hd,  -hw, -hh,  hd,  -hw,  hh,  hd,  -hw,  hh, -hd,
            // RIGHT (+X)
             hw, -hh,  hd,   hw, -hh, -hd,   hw,  hh, -hd,   hw,  hh,  hd,
            // TOP (+Y)
            -hw,  hh,  hd,   hw,  hh,  hd,   hw,  hh, -hd,  -hw,  hh, -hd,
            // BOTTOM (-Y)
            -hw, -hh, -hd,   hw, -hh, -hd,   hw, -hh,  hd,  -hw, -hh,  hd,
        };

        float[] texCoords = new float[6 * 4 * 2];
        for (int face = 0; face < 6; face++) {
            int o = face * 8;
            texCoords[o    ] = 0.0f; texCoords[o + 1] = 1.0f;
            texCoords[o + 2] = 1.0f; texCoords[o + 3] = 1.0f;
            texCoords[o + 4] = 1.0f; texCoords[o + 5] = 0.0f;
            texCoords[o + 6] = 0.0f; texCoords[o + 7] = 0.0f;
        }

        int[] indices = new int[36];
        int idx = 0;
        for (int face = 0; face < 6; face++) {
            int base = face * 4;
            indices[idx++] = base;
            indices[idx++] = base + 1;
            indices[idx++] = base + 2;
            indices[idx++] = base + 2;
            indices[idx++] = base + 3;
            indices[idx++] = base;
        }

        return new ModelPart(name, new Vector3f(0, 0, 0), vertices, texCoords, indices, 2);
    }

    @Override
    public int[] triangleToFaceId() {
        // 12 triangles, 6 faces, 2 triangles per face
        int[] mapping = new int[12];
        for (int i = 0; i < 12; i++) mapping[i] = i / 2;
        return mapping;
    }
}
