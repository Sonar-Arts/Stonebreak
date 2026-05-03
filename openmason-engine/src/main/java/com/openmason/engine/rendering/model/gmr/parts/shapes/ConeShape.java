package com.openmason.engine.rendering.model.gmr.parts.shapes;

import com.openmason.engine.rendering.model.ModelPart;
import org.joml.Vector3f;

/**
 * Cone oriented along the Y axis with apex at +Y.
 * <p>12 radial segments. Each side triangle has its own 3 vertices for flat
 * shading; base is a triangle fan.
 * <ul>
 *   <li>Sides: 12 triangle faces (36 vertices, 12 triangles)</li>
 *   <li>Base: 1 face (13 vertices, 12 triangles)</li>
 *   <li>Total: 49 vertices, 72 indices, 13 logical faces</li>
 * </ul>
 */
public final class ConeShape implements PartShape {

    public static final int SEGMENTS = 12;

    @Override public String displayName() { return "Cone"; }
    @Override public String description() { return "Single-apex circular base with triangular sides"; }
    @Override public String iconFilename() { return "cone.svg"; }

    @Override
    public ModelPart create(String name, Vector3f size) {
        int n = SEGMENTS;
        float rx = size.x / 2.0f;
        float rz = size.z / 2.0f;
        float hh = size.y / 2.0f;

        float[] sinT = new float[n + 1];
        float[] cosT = new float[n + 1];
        for (int i = 0; i <= n; i++) {
            double a = (2.0 * Math.PI * i) / n;
            sinT[i] = (float) Math.sin(a);
            cosT[i] = (float) Math.cos(a);
        }

        int sideCount = n * 3;
        int baseCenterIdx = sideCount;
        int baseRingStart = baseCenterIdx + 1;
        int totalVerts = sideCount + 1 + n;

        float[] vertices = new float[totalVerts * 3];
        float[] texCoords = new float[totalVerts * 2];

        // Side triangles — (apex, base[i], base[i+1]) for CCW viewed from outside
        for (int i = 0; i < n; i++) {
            int v = i * 3;
            int o = v * 3;
            vertices[o    ] = 0;              vertices[o + 1] =  hh; vertices[o + 2] = 0;
            vertices[o + 3] = rx * cosT[i];   vertices[o + 4] = -hh; vertices[o + 5] = rz * sinT[i];
            vertices[o + 6] = rx * cosT[i+1]; vertices[o + 7] = -hh; vertices[o + 8] = rz * sinT[i+1];
            int t = v * 2;
            texCoords[t    ] = 0.5f; texCoords[t + 1] = 0.0f;
            texCoords[t + 2] = 0.0f; texCoords[t + 3] = 1.0f;
            texCoords[t + 4] = 1.0f; texCoords[t + 5] = 1.0f;
        }

        // Base center + ring (polar UV)
        vertices[baseCenterIdx * 3 + 1] = -hh;
        texCoords[baseCenterIdx * 2    ] = 0.5f;
        texCoords[baseCenterIdx * 2 + 1] = 0.5f;
        for (int i = 0; i < n; i++) {
            int idx = baseRingStart + i;
            vertices[idx * 3    ] = rx * cosT[i];
            vertices[idx * 3 + 1] = -hh;
            vertices[idx * 3 + 2] = rz * sinT[i];
            texCoords[idx * 2    ] = 0.5f + 0.5f * cosT[i];
            texCoords[idx * 2 + 1] = 0.5f + 0.5f * sinT[i];
        }

        int[] indices = new int[n * 3 + n * 3];
        int idx = 0;
        for (int i = 0; i < n; i++) {
            int base = i * 3;
            indices[idx++] = base;
            indices[idx++] = base + 1;
            indices[idx++] = base + 2;
        }
        for (int i = 0; i < n; i++) {
            int a = baseRingStart + i;
            int b = baseRingStart + ((i + 1) % n);
            indices[idx++] = baseCenterIdx;
            indices[idx++] = a;
            indices[idx++] = b;
        }

        return new ModelPart(name, new Vector3f(0, 0, 0), vertices, texCoords, indices, null);
    }

    @Override
    public int[] triangleToFaceId() {
        int n = SEGMENTS;
        int[] mapping = new int[n + n];
        int p = 0;
        for (int i = 0; i < n; i++) mapping[p++] = i;     // sides: face 0..n-1
        int baseFaceId = n;
        for (int i = 0; i < n; i++) mapping[p++] = baseFaceId;
        return mapping;
    }
}
