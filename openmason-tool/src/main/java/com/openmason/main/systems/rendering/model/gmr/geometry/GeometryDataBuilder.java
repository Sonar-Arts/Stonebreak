package com.openmason.main.systems.rendering.model.gmr.geometry;

/**
 * Implementation of IGeometryDataBuilder.
 * Constructs interleaved vertex data for GPU upload.
 * Combines position (3 floats) and texture coordinates (2 floats) into a single buffer.
 */
public class GeometryDataBuilder implements IGeometryDataBuilder {

    private static final int POSITION_COMPONENTS = 3;
    private static final int TEXCOORD_COMPONENTS = 2;
    private static final int FLOATS_PER_VERTEX = POSITION_COMPONENTS + TEXCOORD_COMPONENTS; // 5
    private static final int STRIDE = FLOATS_PER_VERTEX * Float.BYTES; // 20 bytes
    private static final int POSITION_OFFSET = 0;
    private static final int TEXCOORD_OFFSET = POSITION_COMPONENTS * Float.BYTES; // 12 bytes

    @Override
    public float[] buildInterleavedData(float[] vertices, float[] texCoords) {
        if (vertices == null) {
            return new float[0];
        }

        int vertexCount = vertices.length / POSITION_COMPONENTS;
        float[] interleaved = new float[vertexCount * FLOATS_PER_VERTEX];

        for (int i = 0; i < vertexCount; i++) {
            int srcPos = i * POSITION_COMPONENTS;
            int srcTex = i * TEXCOORD_COMPONENTS;
            int dst = i * FLOATS_PER_VERTEX;

            // Position (x, y, z)
            interleaved[dst] = vertices[srcPos];
            interleaved[dst + 1] = vertices[srcPos + 1];
            interleaved[dst + 2] = vertices[srcPos + 2];

            // TexCoord (u, v)
            if (texCoords != null && srcTex + 1 < texCoords.length) {
                interleaved[dst + 3] = texCoords[srcTex];
                interleaved[dst + 4] = texCoords[srcTex + 1];
            } else {
                interleaved[dst + 3] = 0.0f;
                interleaved[dst + 4] = 0.0f;
            }
        }

        return interleaved;
    }

    @Override
    public int getStride() {
        return STRIDE;
    }

    @Override
    public int getFloatsPerVertex() {
        return FLOATS_PER_VERTEX;
    }

    @Override
    public int getPositionOffset() {
        return POSITION_OFFSET;
    }

    @Override
    public int getTexCoordOffset() {
        return TEXCOORD_OFFSET;
    }

    @Override
    public int getPositionComponents() {
        return POSITION_COMPONENTS;
    }

    @Override
    public int getTexCoordComponents() {
        return TEXCOORD_COMPONENTS;
    }
}
