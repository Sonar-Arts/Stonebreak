package com.openmason.engine.rendering.model.gmr.parts.shapes;

import com.openmason.engine.rendering.model.ModelPart;
import org.joml.Vector3f;

/**
 * Torus (ring) lying on the XZ plane with its axis along Y.
 * <p>16 major segments around the ring × 8 minor segments around the tube.
 * Seam vertices are duplicated for clean UVs (0..1 in both directions).
 * <ul>
 *   <li>Total: 17×9 = 153 vertices, 128 quad faces, 256 triangles, 768 indices</li>
 * </ul>
 * Sized by {@code size.x} (overall X diameter), {@code size.y} (tube thickness),
 * {@code size.z} (overall Z diameter — independently scaled for elliptical rings).
 * Major radius = (size.x - size.y) / 2; minor radius = size.y / 2.
 */
public final class TorusShape implements PartShape {

    public static final int MAJOR = 16;
    public static final int MINOR = 8;

    @Override public String displayName() { return "Torus"; }
    @Override public String description() { return "Ring shape with major and minor radii"; }
    @Override public String iconFilename() { return "torus.svg"; }

    @Override
    public ModelPart create(String name, Vector3f size) {
        int major = MAJOR;
        int minor = MINOR;
        float r = size.y / 2.0f;
        float Rx = (size.x / 2.0f) - r;
        float Rz = (size.z / 2.0f) - r;

        int vertCount = (major + 1) * (minor + 1);
        float[] vertices = new float[vertCount * 3];
        float[] texCoords = new float[vertCount * 2];

        for (int i = 0; i <= major; i++) {
            double u = 2.0 * Math.PI * i / major;
            float cu = (float) Math.cos(u);
            float su = (float) Math.sin(u);
            for (int j = 0; j <= minor; j++) {
                double v = 2.0 * Math.PI * j / minor;
                float cv = (float) Math.cos(v);
                float sv = (float) Math.sin(v);

                int idx = i * (minor + 1) + j;
                vertices[idx * 3    ] = (Rx + r * cv) * cu;
                vertices[idx * 3 + 1] = r * sv;
                vertices[idx * 3 + 2] = (Rz + r * cv) * su;
                texCoords[idx * 2    ] = (float) i / major;
                texCoords[idx * 2 + 1] = (float) j / minor;
            }
        }

        int[] indices = new int[major * minor * 6];
        int idx = 0;
        for (int i = 0; i < major; i++) {
            for (int j = 0; j < minor; j++) {
                int row0 = i * (minor + 1) + j;
                int row1 = (i + 1) * (minor + 1) + j;
                indices[idx++] = row0;
                indices[idx++] = row1;
                indices[idx++] = row1 + 1;
                indices[idx++] = row1 + 1;
                indices[idx++] = row0 + 1;
                indices[idx++] = row0;
            }
        }

        return new ModelPart(name, new Vector3f(0, 0, 0), vertices, texCoords, indices, 2);
    }

    @Override
    public int[] triangleToFaceId() {
        int total = MAJOR * MINOR * 2;
        int[] mapping = new int[total];
        for (int i = 0; i < total; i++) mapping[i] = i / 2;
        return mapping;
    }
}
