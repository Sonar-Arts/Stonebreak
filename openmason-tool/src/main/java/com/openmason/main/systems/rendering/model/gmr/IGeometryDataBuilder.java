package com.openmason.main.systems.rendering.model.gmr;

/**
 * Interface for constructing interleaved vertex data for GPU upload.
 * Combines position and texture coordinate data into a single buffer.
 */
public interface IGeometryDataBuilder {

    /**
     * Build interleaved vertex data (position + texCoord).
     * Format per vertex: [x, y, z, u, v]
     *
     * @param vertices Vertex positions (x,y,z interleaved)
     * @param texCoords Texture coordinates (u,v interleaved)
     * @return Interleaved data array, or empty array if vertices is null
     */
    float[] buildInterleavedData(float[] vertices, float[] texCoords);

    /**
     * Get the stride in bytes (position 3 floats + texCoord 2 floats = 5 * 4 = 20 bytes).
     *
     * @return Stride in bytes
     */
    int getStride();

    /**
     * Get the number of floats per vertex (position 3 + texCoord 2 = 5).
     *
     * @return Floats per vertex
     */
    int getFloatsPerVertex();

    /**
     * Get the position attribute offset in bytes (always 0).
     *
     * @return Position offset in bytes
     */
    int getPositionOffset();

    /**
     * Get the texture coordinate attribute offset in bytes (3 * 4 = 12).
     *
     * @return TexCoord offset in bytes
     */
    int getTexCoordOffset();

    /**
     * Get the number of position components (always 3).
     *
     * @return Position components
     */
    int getPositionComponents();

    /**
     * Get the number of texture coordinate components (always 2).
     *
     * @return TexCoord components
     */
    int getTexCoordComponents();
}
