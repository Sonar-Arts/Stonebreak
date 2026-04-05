package com.openmason.engine.voxel.mms.mmsTexturing;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.ITextureCoordProvider;
import com.openmason.engine.voxel.mms.mmsCore.MmsBufferLayout;

/**
 * Mighty Mesh System - Texture atlas-based texture coordinate mapper.
 *
 * Generates texture coordinates by resolving block types to texture atlas positions.
 * Uses ITextureCoordProvider for face-specific texture coordinate lookups.
 *
 * Design Philosophy:
 * - KISS: Direct delegation to texture provider for face-specific UVs
 * - Performance: Cached atlas coordinates via provider
 * - Correctness: Proper per-face texture coordinate resolution
 *
 * Note: CBR integration was removed because CBR's resolveBlockType() method
 * does not support face-specific texture coordinate lookups, which caused
 * all block faces to receive identical texture coordinates.
 *
 * @since MMS 1.0
 */
public class MmsAtlasTextureMapper implements MmsTextureMapper {

    private final ITextureCoordProvider provider;

    /**
     * Creates a texture mapper using the provided texture coordinate provider.
     *
     * @param provider Texture coordinate provider for UV lookups
     */
    public MmsAtlasTextureMapper(ITextureCoordProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("Texture coordinate provider cannot be null");
        }
        this.provider = provider;
        System.out.println("[MmsAtlasTextureMapper] Initialized with ITextureCoordProvider for face-specific coordinate lookups");
    }

    @Override
    public float[] generateFaceTextureCoordinates(IBlockType blockType, int face) {
        validateFaceIndex(face);

        float[] texCoords = new float[MmsBufferLayout.TEXTURE_SIZE * MmsBufferLayout.VERTICES_PER_QUAD];

        // Get atlas coordinates for this block/face combination
        float[] uvs = getBlockFaceUVs(blockType, face);

        // Generate quad texture coordinates (counter-clockwise winding)
        // Note: uvs format is [u1, v1, u2, v2] where v1 is top, v2 is bottom
        int idx = 0;

        // Vertex 0: Bottom-left (world space) -> (u1, v2)
        texCoords[idx++] = uvs[0]; // u1
        texCoords[idx++] = uvs[3]; // v2

        // Vertex 1: Bottom-right (world space) -> (u2, v2)
        texCoords[idx++] = uvs[2]; // u2
        texCoords[idx++] = uvs[3]; // v2

        // Vertex 2: Top-right (world space) -> (u2, v1)
        texCoords[idx++] = uvs[2]; // u2
        texCoords[idx++] = uvs[1]; // v1

        // Vertex 3: Top-left (world space) -> (u1, v1)
        texCoords[idx++] = uvs[0]; // u1
        texCoords[idx++] = uvs[1]; // v1

        return texCoords;
    }

    @Override
    public float[] generateCrossTextureCoordinates(IBlockType blockType) {
        float[] texCoords = new float[MmsBufferLayout.TEXTURE_SIZE * MmsBufferLayout.VERTICES_PER_CROSS];

        // Get atlas coordinates for cross block (use face 0 for cross blocks)
        float[] uvs = getBlockFaceUVs(blockType, 0);

        int idx = 0;

        // 2 planes (8 vertices total), each plane uses the same texture coordinates
        // Index winding handles double-sided rendering (no need for separate back face textures)
        for (int plane = 0; plane < 2; plane++) {
            // Vertex 0: Bottom-left
            texCoords[idx++] = uvs[0]; // u1
            texCoords[idx++] = uvs[3]; // v2

            // Vertex 1: Bottom-right
            texCoords[idx++] = uvs[2]; // u2
            texCoords[idx++] = uvs[3]; // v2

            // Vertex 2: Top-right
            texCoords[idx++] = uvs[2]; // u2
            texCoords[idx++] = uvs[1]; // v1

            // Vertex 3: Top-left
            texCoords[idx++] = uvs[0]; // u1
            texCoords[idx++] = uvs[1]; // v1
        }

        return texCoords;
    }

    @Override
    public float[] generateAlphaFlags(IBlockType blockType) {
        float[] flags = new float[MmsBufferLayout.VERTICES_PER_QUAD];
        float flagValue = requiresAlphaTesting(blockType) ? 1.0f : 0.0f;

        for (int i = 0; i < MmsBufferLayout.VERTICES_PER_QUAD; i++) {
            flags[i] = flagValue;
        }

        return flags;
    }

    /**
     * Generates alpha flags for cross-section blocks (8 vertices).
     *
     * @param blockType Type of block
     * @return Array of 8 alpha flags
     */
    public float[] generateCrossAlphaFlags(IBlockType blockType) {
        float[] flags = new float[MmsBufferLayout.VERTICES_PER_CROSS];
        float flagValue = requiresAlphaTesting(blockType) ? 1.0f : 0.0f;

        for (int i = 0; i < MmsBufferLayout.VERTICES_PER_CROSS; i++) {
            flags[i] = flagValue;
        }

        return flags;
    }

    /**
     * Gets face-specific texture coordinates from the texture coordinate provider.
     *
     * This method correctly handles blocks with different textures per face
     * (e.g., grass blocks with green top, dirt sides, and dirt bottom).
     *
     * @param blockType Type of block
     * @param faceIndex Face index (0-5: TOP, BOTTOM, NORTH, SOUTH, EAST, WEST)
     * @return UV array [u1, v1, u2, v2]
     * @throws IllegalStateException if provider returns null coordinates
     */
    private float[] getBlockFaceUVs(IBlockType blockType, int faceIndex) {
        // Get face-specific texture coordinates from provider
        float[] result = provider.getBlockFaceUVs(blockType, faceIndex);

        // Validate result
        if (result == null) {
            throw new IllegalStateException(
                String.format("ITextureCoordProvider returned null UVs for %s face index %d",
                    blockType.getName(), faceIndex)
            );
        }

        // Validate UV coordinate ranges
        if (result.length != 4) {
            throw new IllegalStateException(
                String.format("Invalid UV array length for %s face index %d: expected 4, got %d",
                    blockType.getName(), faceIndex, result.length)
            );
        }

        return result;
    }

    /**
     * Validates a face index.
     *
     * @param face Face index to validate
     * @throws IllegalArgumentException if face index is invalid
     */
    private void validateFaceIndex(int face) {
        if (face < 0 || face >= 6) {
            throw new IllegalArgumentException("Face index must be 0-5, got: " + face);
        }
    }

    /**
     * Checks if a block type requires alpha testing.
     *
     * @param blockType Type of block
     * @return true if requires alpha testing
     */
    @Override
    public boolean requiresAlphaTesting(IBlockType blockType) {
        // Use transparency from the IBlockType contract as a heuristic
        // Game-specific implementations can override this behavior
        return blockType.isTransparent();
    }

    /**
     * Gets the texture coordinate provider instance.
     *
     * @return Texture coordinate provider
     */
    public ITextureCoordProvider getTextureCoordProvider() {
        return provider;
    }
}
