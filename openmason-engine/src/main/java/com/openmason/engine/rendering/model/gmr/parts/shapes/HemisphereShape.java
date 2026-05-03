package com.openmason.engine.rendering.model.gmr.parts.shapes;

import com.openmason.engine.rendering.model.ModelPart;
import org.joml.Vector3f;

/**
 * Half-sphere dome with a flat circular base at y = 0.
 * <p>12 slices × 4 stacks for the dome (occupying +Y), plus a fanned base disc
 * facing -Y. The dome's apex sits at y = size.y; the flat base sits at y = 0.
 * <ul>
 *   <li>Dome: 13×5 = 65 vertices, 48 quad faces (96 triangles)</li>
 *   <li>Base: 13 vertices (12 ring + 1 center), 1 face (12 triangles)</li>
 *   <li>Total: 78 vertices, 324 indices, 49 logical faces</li>
 * </ul>
 */
public final class HemisphereShape implements PartShape {

    public static final int SLICES = 12;
    public static final int STACKS = 4;

    @Override public String displayName() { return "Hemisphere"; }
    @Override public String description() { return "Half-sphere dome with a flat circular base"; }
    @Override public String iconFilename() { return "hemisphere.svg"; }

    @Override
    public ModelPart create(String name, Vector3f size) {
        int slices = SLICES;
        int stacks = STACKS;
        float rx = size.x / 2.0f;
        float ry = size.y;
        float rz = size.z / 2.0f;

        int domeVertCount = (slices + 1) * (stacks + 1);
        int baseRingStart = domeVertCount;
        int baseCenterIdx = baseRingStart + slices;
        int totalVerts = domeVertCount + slices + 1;

        float[] vertices = new float[totalVerts * 3];
        float[] texCoords = new float[totalVerts * 2];

        // Dome: phi 0 (top) → PI/2 (equator/base)
        for (int i = 0; i <= stacks; i++) {
            double phi = (Math.PI / 2.0) * i / stacks;
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

        // Base ring + center (polar UV)
        for (int j = 0; j < slices; j++) {
            double theta = 2.0 * Math.PI * j / slices;
            float c = (float) Math.cos(theta);
            float s = (float) Math.sin(theta);
            int idx = baseRingStart + j;
            vertices[idx * 3    ] = rx * c;
            vertices[idx * 3 + 1] = 0;
            vertices[idx * 3 + 2] = rz * s;
            texCoords[idx * 2    ] = 0.5f + 0.5f * c;
            texCoords[idx * 2 + 1] = 0.5f + 0.5f * s;
        }
        texCoords[baseCenterIdx * 2    ] = 0.5f;
        texCoords[baseCenterIdx * 2 + 1] = 0.5f;
        // baseCenter position is (0,0,0) — already zero-initialized

        int[] indices = new int[stacks * slices * 6 + slices * 3];
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
        // Base fan — CCW viewed from -Y
        for (int j = 0; j < slices; j++) {
            int a = baseRingStart + j;
            int b = baseRingStart + ((j + 1) % slices);
            indices[idx++] = baseCenterIdx;
            indices[idx++] = a;
            indices[idx++] = b;
        }

        return new ModelPart(name, new Vector3f(0, 0, 0), vertices, texCoords, indices, null);
    }

    @Override
    public int[] triangleToFaceId() {
        int domeTris = STACKS * SLICES * 2;
        int baseTris = SLICES;
        int[] mapping = new int[domeTris + baseTris];
        for (int i = 0; i < domeTris; i++) mapping[i] = i / 2;
        int baseFaceId = STACKS * SLICES;
        for (int i = 0; i < baseTris; i++) mapping[domeTris + i] = baseFaceId;
        return mapping;
    }
}
