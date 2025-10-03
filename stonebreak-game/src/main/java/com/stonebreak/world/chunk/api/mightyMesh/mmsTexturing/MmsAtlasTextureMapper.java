package com.stonebreak.world.chunk.api.mightyMesh.mmsTexturing;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsBufferLayout;

/**
 * Mighty Mesh System - Texture atlas-based texture coordinate mapper.
 *
 * Generates texture coordinates by resolving block types to texture atlas positions.
 * Uses TextureAtlas directly for face-specific texture coordinate lookups.
 *
 * Design Philosophy:
 * - KISS: Direct delegation to TextureAtlas for face-specific UVs
 * - Performance: Cached atlas coordinates via TextureAtlas
 * - Correctness: Proper per-face texture coordinate resolution
 *
 * Note: CBR integration was removed because CBR's resolveBlockType() method
 * does not support face-specific texture coordinate lookups, which caused
 * all block faces to receive identical texture coordinates.
 *
 * @since MMS 1.0
 */
public class MmsAtlasTextureMapper implements MmsTextureMapper {

    private final TextureAtlas textureAtlas;

    /**
     * Creates a texture mapper using the provided texture atlas.
     *
     * @param textureAtlas Texture atlas for coordinate lookups
     */
    public MmsAtlasTextureMapper(TextureAtlas textureAtlas) {
        if (textureAtlas == null) {
            throw new IllegalArgumentException("Texture atlas cannot be null");
        }
        this.textureAtlas = textureAtlas;
        System.out.println("[MmsAtlasTextureMapper] Initialized with TextureAtlas for face-specific coordinate lookups");
    }

    @Override
    public float[] generateFaceTextureCoordinates(BlockType blockType, int face) {
        validateFaceIndex(face);

        float[] texCoords = new float[MmsBufferLayout.TEXTURE_SIZE * MmsBufferLayout.VERTICES_PER_QUAD];

        // Get atlas coordinates for this block/face combination
        float[] uvs = getBlockFaceUVs(blockType, face);

        // Generate quad texture coordinates (counter-clockwise winding)
        // Note: uvs format is [u1, v1, u2, v2] where v1 is top, v2 is bottom
        int idx = 0;

        // Vertex 0: Bottom-left (world space) → (u1, v2)
        texCoords[idx++] = uvs[0]; // u1
        texCoords[idx++] = uvs[3]; // v2

        // Vertex 1: Bottom-right (world space) → (u2, v2)
        texCoords[idx++] = uvs[2]; // u2
        texCoords[idx++] = uvs[3]; // v2

        // Vertex 2: Top-right (world space) → (u2, v1)
        texCoords[idx++] = uvs[2]; // u2
        texCoords[idx++] = uvs[1]; // v1

        // Vertex 3: Top-left (world space) → (u1, v1)
        texCoords[idx++] = uvs[0]; // u1
        texCoords[idx++] = uvs[1]; // v1

        return texCoords;
    }

    @Override
    public float[] generateCrossTextureCoordinates(BlockType blockType) {
        if (!isCrossBlock(blockType)) {
            throw new IllegalArgumentException("Block type is not a cross-section block: " + blockType);
        }

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
    public float[] generateAlphaFlags(BlockType blockType) {
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
    public float[] generateCrossAlphaFlags(BlockType blockType) {
        float[] flags = new float[MmsBufferLayout.VERTICES_PER_CROSS];
        float flagValue = requiresAlphaTesting(blockType) ? 1.0f : 0.0f;

        for (int i = 0; i < MmsBufferLayout.VERTICES_PER_CROSS; i++) {
            flags[i] = flagValue;
        }

        return flags;
    }

    /**
     * Gets face-specific texture coordinates from the texture atlas.
     *
     * This method correctly handles blocks with different textures per face
     * (e.g., grass blocks with green top, dirt sides, and dirt bottom).
     *
     * @param blockType Type of block
     * @param face Face index (0-5: TOP, BOTTOM, NORTH, SOUTH, EAST, WEST)
     * @return UV array [u1, v1, u2, v2]
     * @throws IllegalStateException if TextureAtlas returns null coordinates
     */
    private float[] getBlockFaceUVs(BlockType blockType, int face) {
        // Convert face index to BlockType.Face enum for TextureAtlas lookup
        BlockType.Face faceEnum = convertFaceIndexToEnum(face);

        // Get face-specific texture coordinates from atlas
        float[] result = textureAtlas.getBlockFaceUVs(blockType, faceEnum);

        // Validate result
        if (result == null) {
            throw new IllegalStateException(
                String.format("TextureAtlas returned null UVs for %s face %s (index %d)",
                    blockType, faceEnum, face)
            );
        }

        // Validate UV coordinate ranges
        if (result.length != 4) {
            throw new IllegalStateException(
                String.format("Invalid UV array length for %s face %s: expected 4, got %d",
                    blockType, faceEnum, result.length)
            );
        }

        return result;
    }

    /**
     * Converts a face index (0-5) to BlockType.Face enum.
     *
     * @param faceIndex Face index (0=TOP, 1=BOTTOM, 2=NORTH, 3=SOUTH, 4=EAST, 5=WEST)
     * @return Corresponding BlockType.Face enum value
     */
    private BlockType.Face convertFaceIndexToEnum(int faceIndex) {
        switch (faceIndex) {
            case 0: return BlockType.Face.TOP;
            case 1: return BlockType.Face.BOTTOM;
            case 2: return BlockType.Face.SIDE_NORTH;
            case 3: return BlockType.Face.SIDE_SOUTH;
            case 4: return BlockType.Face.SIDE_EAST;
            case 5: return BlockType.Face.SIDE_WEST;
            default:
                throw new IllegalArgumentException("Invalid face index: " + faceIndex);
        }
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
     * Checks if a block type is a cross-section block.
     *
     * @param blockType Type of block
     * @return true if cross-section block
     */
    private boolean isCrossBlock(BlockType blockType) {
        return blockType == BlockType.ROSE || blockType == BlockType.DANDELION;
    }

    /**
     * Checks if a block type requires alpha testing.
     *
     * @param blockType Type of block
     * @return true if requires alpha testing
     */
    @Override
    public boolean requiresAlphaTesting(BlockType blockType) {
        // Cross blocks (flowers) and leaves need alpha testing
        return isCrossBlock(blockType) ||
               blockType == BlockType.LEAVES ||
               blockType == BlockType.PINE_LEAVES ||
               blockType == BlockType.ELM_LEAVES;
    }

    /**
     * Gets the texture atlas instance.
     *
     * @return Texture atlas
     */
    public TextureAtlas getTextureAtlas() {
        return textureAtlas;
    }
}
