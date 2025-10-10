package com.stonebreak.world.chunk.api.mightyMesh.mmsGeometry;

import com.stonebreak.blocks.BlockType;

/**
 * Mighty Mesh System - Geometry generation service interface.
 *
 * Defines the contract for generating vertex positions and normals for different
 * block types. Implementations can be registered per block type using the
 * Strategy pattern.
 *
 * Design Philosophy:
 * - Open/Closed Principle: Extend without modifying core code
 * - Interface Segregation: Focused geometry generation contract
 * - Single Responsibility: Only handles geometric data
 *
 * @since MMS 1.0
 */
public interface MmsGeometryService {

    /**
     * Generates vertex positions for a block face.
     *
     * @param face Face index (0=top, 1=bottom, 2=north, 3=south, 4=east, 5=west)
     * @param worldX World X coordinate
     * @param worldY World Y coordinate
     * @param worldZ World Z coordinate
     * @return Array of 12 floats representing 4 vertices (x,y,z each)
     */
    float[] generateFaceVertices(int face, float worldX, float worldY, float worldZ);

    /**
     * Generates normal vectors for a block face.
     *
     * @param face Face index (0=top, 1=bottom, 2=north, 3=south, 4=east, 5=west)
     * @return Array of 12 floats representing 4 normal vectors (nx,ny,nz each)
     */
    float[] generateFaceNormals(int face);
}
