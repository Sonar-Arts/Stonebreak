package com.openmason.engine.rendering.model.gmr.parts.shapes;

import com.openmason.engine.rendering.model.ModelPart;
import org.joml.Vector3f;

/**
 * UV sphere built from latitude/longitude quads.
 * <p>12 longitudinal slices × 8 latitudinal stacks. Seam vertices are duplicated
 * so UVs read 0..1 cleanly. Polar rows produce thin degenerate quads (standard).
 * <ul>
 *   <li>Total: 117 vertices, 96 quad faces, 192 triangles, 576 indices</li>
 * </ul>
 * Sized by {@code size.x/y/z} as ellipsoid diameters along each axis.
 */
public final class SphereShape implements PartShape {

    public static final int SLICES = 12;
    public static final int STACKS = 8;

    @Override public String displayName() { return "Sphere"; }
    @Override public String description() { return "UV sphere built from latitude/longitude quads"; }
    @Override public String iconFilename() { return "sphere.svg"; }

    @Override
    public ModelPart create(String name, Vector3f size) {
        int slices = SLICES;
        int stacks = STACKS;
        float rx = size.x / 2.0f;
        float ry = size.y / 2.0f;
        float rz = size.z / 2.0f;

        int vertCount = (slices + 1) * (stacks + 1);
        float[] vertices = new float[vertCount * 3];
        float[] texCoords = new float[vertCount * 2];

        for (int i = 0; i <= stacks; i++) {
            double phi = Math.PI * i / stacks;
            float sinPhi = (float) Math.sin(phi);
            float cosPhi = (float) Math.cos(phi);
            for (int j = 0; j <= slices; j++) {
                double theta = 2.0 * Math.PI * j / slices;
                float sinTheta = (float) Math.sin(theta);
                float cosTheta = (float) Math.cos(theta);

                int idx = i * (slices + 1) + j;
                vertices[idx * 3    ] = rx * sinPhi * cosTheta;
                vertices[idx * 3 + 1] = ry * cosPhi;
                vertices[idx * 3 + 2] = rz * sinPhi * sinTheta;
                texCoords[idx * 2    ] = (float) j / slices;
                texCoords[idx * 2 + 1] = (float) i / stacks;
            }
        }

        int[] indices = new int[stacks * slices * 6];
        int idx = 0;
        for (int i = 0; i < stacks; i++) {
            for (int j = 0; j < slices; j++) {
                int row0 = i * (slices + 1) + j;
                int row1 = (i + 1) * (slices + 1) + j;
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
        int total = STACKS * SLICES * 2;
        int[] mapping = new int[total];
        for (int i = 0; i < total; i++) mapping[i] = i / 2;
        return mapping;
    }
}
