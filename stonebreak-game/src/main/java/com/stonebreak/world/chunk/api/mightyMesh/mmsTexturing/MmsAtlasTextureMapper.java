package com.stonebreak.world.chunk.api.mightyMesh.mmsTexturing;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsBufferLayout;

/**
 * Mighty Mesh System - Texture atlas-based texture coordinate mapper.
 *
 * Generates texture coordinates by resolving block types to texture atlas positions.
 * Integrates with the existing TextureAtlas system.
 *
 * Design Philosophy:
 * - DRY: Reuses existing TextureAtlas infrastructure
 * - KISS: Simple delegation to atlas lookups
 * - Performance: Cached atlas coordinates
 *
 * @since MMS 1.0
 */
public class MmsAtlasTextureMapper implements MmsTextureMapper {

    private final TextureAtlas textureAtlas;

    // Water flag constants
    private static final float WATER_FLAG_EPSILON = 0.0001f;

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
    }

    @Override
    public float[] generateFaceTextureCoordinates(BlockType blockType, int face) {
        validateFaceIndex(face);

        float[] texCoords = new float[MmsBufferLayout.TEXTURE_SIZE * MmsBufferLayout.VERTICES_PER_QUAD];

        // Get atlas coordinates for this block/face combination
        TextureAtlas.TextureRegion region = getTextureRegion(blockType, face);

        // Generate quad texture coordinates (counter-clockwise winding)
        int idx = 0;

        // Vertex 0: Bottom-left
        texCoords[idx++] = region.u1;
        texCoords[idx++] = region.v2;

        // Vertex 1: Bottom-right
        texCoords[idx++] = region.u2;
        texCoords[idx++] = region.v2;

        // Vertex 2: Top-right
        texCoords[idx++] = region.u2;
        texCoords[idx++] = region.v1;

        // Vertex 3: Top-left
        texCoords[idx++] = region.u1;
        texCoords[idx++] = region.v1;

        return texCoords;
    }

    @Override
    public float[] generateCrossTextureCoordinates(BlockType blockType) {
        if (!isCrossBlock(blockType)) {
            throw new IllegalArgumentException("Block type is not a cross-section block: " + blockType);
        }

        float[] texCoords = new float[MmsBufferLayout.TEXTURE_SIZE * MmsBufferLayout.VERTICES_PER_CROSS];

        // Get atlas coordinates for cross block
        TextureAtlas.TextureRegion region = getTextureRegion(blockType, 0); // Cross blocks use single texture

        int idx = 0;

        // All 4 quads (2 planes * 2 sides) use the same texture coordinates
        for (int quad = 0; quad < 4; quad++) {
            // Vertex 0: Bottom-left
            texCoords[idx++] = region.u1;
            texCoords[idx++] = region.v2;

            // Vertex 1: Bottom-right
            texCoords[idx++] = region.u2;
            texCoords[idx++] = region.v2;

            // Vertex 2: Top-right
            texCoords[idx++] = region.u2;
            texCoords[idx++] = region.v1;

            // Vertex 3: Top-left
            texCoords[idx++] = region.u1;
            texCoords[idx++] = region.v1;
        }

        return texCoords;
    }

    @Override
    public float[] generateWaterFlags(int face, float[] waterCornerHeights) {
        float[] flags = new float[MmsBufferLayout.VERTICES_PER_QUAD];

        if (waterCornerHeights == null || waterCornerHeights.length != 4) {
            // No water surface variation, use base flags
            for (int i = 0; i < MmsBufferLayout.VERTICES_PER_QUAD; i++) {
                flags[i] = WATER_FLAG_EPSILON;
            }
            return flags;
        }

        // Apply water corner heights based on face
        switch (face) {
            case 0: // Top face - apply all corner heights
                for (int i = 0; i < MmsBufferLayout.VERTICES_PER_QUAD; i++) {
                    flags[i] = encodeWaterHeight(waterCornerHeights[i]);
                }
                break;

            case 2: // North face (-Z)
                flags[0] = WATER_FLAG_EPSILON;
                flags[1] = encodeWaterHeight(waterCornerHeights[3]);
                flags[2] = encodeWaterHeight(waterCornerHeights[2]);
                flags[3] = WATER_FLAG_EPSILON;
                break;

            case 3: // South face (+Z)
                flags[0] = WATER_FLAG_EPSILON;
                flags[1] = WATER_FLAG_EPSILON;
                flags[2] = encodeWaterHeight(waterCornerHeights[1]);
                flags[3] = encodeWaterHeight(waterCornerHeights[0]);
                break;

            case 4: // East face (+X)
                flags[0] = WATER_FLAG_EPSILON;
                flags[1] = WATER_FLAG_EPSILON;
                flags[2] = encodeWaterHeight(waterCornerHeights[2]);
                flags[3] = encodeWaterHeight(waterCornerHeights[1]);
                break;

            case 5: // West face (-X)
                flags[0] = WATER_FLAG_EPSILON;
                flags[1] = encodeWaterHeight(waterCornerHeights[0]);
                flags[2] = encodeWaterHeight(waterCornerHeights[3]);
                flags[3] = WATER_FLAG_EPSILON;
                break;

            default: // Bottom face or invalid
                for (int i = 0; i < MmsBufferLayout.VERTICES_PER_QUAD; i++) {
                    flags[i] = WATER_FLAG_EPSILON;
                }
                break;
        }

        return flags;
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
     * Gets the texture region from the atlas for a given block and face.
     *
     * @param blockType Type of block
     * @param face Face index (0-5)
     * @return Texture region with UV coordinates
     */
    private TextureAtlas.TextureRegion getTextureRegion(BlockType blockType, int face) {
        // Delegate to the texture atlas to resolve coordinates
        // The atlas handles directional blocks (grass, logs) automatically
        return textureAtlas.getTextureCoordinates(blockType, face);
    }

    /**
     * Encodes a water height value for shader usage.
     *
     * @param height Water height (0.0 to 1.0)
     * @return Encoded height with minimum epsilon
     */
    private float encodeWaterHeight(float height) {
        return height > WATER_FLAG_EPSILON ? height : WATER_FLAG_EPSILON;
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
     * Gets the texture atlas instance.
     *
     * @return Texture atlas
     */
    public TextureAtlas getTextureAtlas() {
        return textureAtlas;
    }
}
