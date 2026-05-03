package com.openmason.engine.rendering.model.gmr.parts.shapes;

import com.openmason.engine.rendering.model.ModelPart;
import org.joml.Vector3f;

/**
 * Cylinder oriented along the Y axis with capped ends.
 * <p>12 radial segments. Each side face is a flat-shaded quad with its own
 * 4 vertices for crisp UV seams; top and bottom caps are triangle fans.
 * <ul>
 *   <li>Sides: 12 quad faces (48 vertices, 24 triangles)</li>
 *   <li>Top cap: 1 face (13 vertices, 12 triangles)</li>
 *   <li>Bottom cap: 1 face (13 vertices, 12 triangles)</li>
 *   <li>Total: 74 vertices, 144 indices, 14 logical faces</li>
 * </ul>
 */
public final class CylinderShape implements PartShape {

    public static final int SEGMENTS = 12;

    @Override public String displayName() { return "Cylinder"; }
    @Override public String description() { return "Tube/column with rounded sides and capped ends"; }
    @Override public String iconFilename() { return "cylinder.svg"; }

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

        int sideCount = n * 4;
        int topCenterIdx = sideCount;
        int topRingStart = topCenterIdx + 1;
        int botCenterIdx = topRingStart + n;
        int botRingStart = botCenterIdx + 1;
        int totalVerts = sideCount + 1 + n + 1 + n;

        float[] vertices = new float[totalVerts * 3];
        float[] texCoords = new float[totalVerts * 2];

        // Side quads — own vertices per segment for full-texture UV per face
        for (int i = 0; i < n; i++) {
            float x0 = rx * cosT[i],     z0 = rz * sinT[i];
            float x1 = rx * cosT[i + 1], z1 = rz * sinT[i + 1];
            int v = i * 4;
            int o = v * 3;
            vertices[o    ] = x0; vertices[o + 1] = -hh; vertices[o + 2] = z0;
            vertices[o + 3] = x1; vertices[o + 4] = -hh; vertices[o + 5] = z1;
            vertices[o + 6] = x1; vertices[o + 7] =  hh; vertices[o + 8] = z1;
            vertices[o + 9] = x0; vertices[o +10] =  hh; vertices[o +11] = z0;
            int t = v * 2;
            texCoords[t    ] = 0.0f; texCoords[t + 1] = 1.0f;
            texCoords[t + 2] = 1.0f; texCoords[t + 3] = 1.0f;
            texCoords[t + 4] = 1.0f; texCoords[t + 5] = 0.0f;
            texCoords[t + 6] = 0.0f; texCoords[t + 7] = 0.0f;
        }

        // Top cap (polar UV)
        vertices[topCenterIdx * 3 + 1] = hh;
        texCoords[topCenterIdx * 2    ] = 0.5f;
        texCoords[topCenterIdx * 2 + 1] = 0.5f;
        for (int i = 0; i < n; i++) {
            int idx = topRingStart + i;
            vertices[idx * 3    ] = rx * cosT[i];
            vertices[idx * 3 + 1] = hh;
            vertices[idx * 3 + 2] = rz * sinT[i];
            texCoords[idx * 2    ] = 0.5f + 0.5f * cosT[i];
            texCoords[idx * 2 + 1] = 0.5f - 0.5f * sinT[i];
        }

        // Bottom cap
        vertices[botCenterIdx * 3 + 1] = -hh;
        texCoords[botCenterIdx * 2    ] = 0.5f;
        texCoords[botCenterIdx * 2 + 1] = 0.5f;
        for (int i = 0; i < n; i++) {
            int idx = botRingStart + i;
            vertices[idx * 3    ] = rx * cosT[i];
            vertices[idx * 3 + 1] = -hh;
            vertices[idx * 3 + 2] = rz * sinT[i];
            texCoords[idx * 2    ] = 0.5f + 0.5f * cosT[i];
            texCoords[idx * 2 + 1] = 0.5f + 0.5f * sinT[i];
        }

        int[] indices = new int[n * 6 + n * 3 + n * 3];
        int idx = 0;
        for (int i = 0; i < n; i++) {
            int base = i * 4;
            indices[idx++] = base;
            indices[idx++] = base + 1;
            indices[idx++] = base + 2;
            indices[idx++] = base + 2;
            indices[idx++] = base + 3;
            indices[idx++] = base;
        }
        // Top fan — CCW viewed from +Y
        for (int i = 0; i < n; i++) {
            int a = topRingStart + i;
            int b = topRingStart + ((i + 1) % n);
            indices[idx++] = topCenterIdx;
            indices[idx++] = b;
            indices[idx++] = a;
        }
        // Bottom fan — CCW viewed from -Y
        for (int i = 0; i < n; i++) {
            int a = botRingStart + i;
            int b = botRingStart + ((i + 1) % n);
            indices[idx++] = botCenterIdx;
            indices[idx++] = a;
            indices[idx++] = b;
        }

        return new ModelPart(name, new Vector3f(0, 0, 0), vertices, texCoords, indices, null);
    }

    @Override
    public int[] triangleToFaceId() {
        int n = SEGMENTS;
        int[] mapping = new int[n * 2 + n + n];
        int p = 0;
        for (int i = 0; i < n; i++) { mapping[p++] = i; mapping[p++] = i; }
        int topFaceId = n;
        int botFaceId = n + 1;
        for (int i = 0; i < n; i++) mapping[p++] = topFaceId;
        for (int i = 0; i < n; i++) mapping[p++] = botFaceId;
        return mapping;
    }
}
