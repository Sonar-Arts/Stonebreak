package com.stonebreak.rendering.models.blocks;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemType;

/**
 * Utility class for calculating texture coordinates for block drops.
 */
public class TextureCoordinateCalculator {
    
    /**
     * Calculate UV coordinates for all faces of a block or item.
     * Returns a 6x8 array: [face][u_tl, v_tl, u_bl, v_bl, u_br, v_br, u_tr, v_tr]
     */
    public static float[][] calculateFaceUVs(Item item) {
        float[][] faceUVs = new float[6][8];
        
        if (item instanceof BlockType blockType) {
            calculateBlockFaceUVs(blockType, faceUVs);
        } else if (item instanceof ItemType itemType) {
            calculateItemFaceUVs(itemType, faceUVs);
        } else {
            calculateDefaultFaceUVs(faceUVs);
        }
        
        return faceUVs;
    }
    
    /**
     * Calculate UV coordinates for a 2D sprite item.
     */
    public static float[] calculateSpriteUVs(ItemType itemType) {
        com.stonebreak.core.Game game = com.stonebreak.core.Game.getInstance();
        if (game != null && game.getTextureAtlas() != null) {
            return game.getTextureAtlas().getTextureCoordinatesForItem(itemType.getId());
        }
        return new float[]{0.0f, 0.0f, 1.0f, 1.0f}; // Default fallback
    }
    
    private static void calculateBlockFaceUVs(BlockType blockType, float[][] faceUVs) {
        com.stonebreak.core.Game game = com.stonebreak.core.Game.getInstance();
        if (game != null && game.getTextureAtlas() != null) {
            // Use modern atlas system
            for (int faceValue = 0; faceValue < 6; faceValue++) {
                BlockType.Face faceEnum = BlockType.Face.values()[faceValue];
                float[] uvCoords = game.getTextureAtlas().getBlockFaceUVs(blockType, faceEnum);
                convertToFaceVertices(uvCoords, faceUVs[faceValue]);
            }
        } else {
            // Fallback to legacy system
            for (int faceValue = 0; faceValue < 6; faceValue++) {
                BlockType.Face faceEnum = BlockType.Face.values()[faceValue];
                float[] texCoords = blockType.getTextureCoords(faceEnum);
                convertLegacyCoords(texCoords, faceUVs[faceValue]);
            }
        }
    }
    
    private static void calculateItemFaceUVs(ItemType itemType, float[][] faceUVs) {
        com.stonebreak.core.Game game = com.stonebreak.core.Game.getInstance();
        if (game != null && game.getTextureAtlas() != null) {
            // Use modern atlas system - same texture for all faces
            float[] uvCoords = game.getTextureAtlas().getTextureCoordinatesForItem(itemType.getId());
            for (int faceValue = 0; faceValue < 6; faceValue++) {
                convertToFaceVertices(uvCoords, faceUVs[faceValue]);
            }
        } else {
            // Fallback to legacy system
            float texX = itemType.getAtlasX() / 16.0f;
            float texY = itemType.getAtlasY() / 16.0f;
            float texSize = 1.0f / 16.0f;
            float[] legacyCoords = {texX, texY};
            
            for (int faceValue = 0; faceValue < 6; faceValue++) {
                convertLegacyCoords(legacyCoords, faceUVs[faceValue]);
            }
        }
    }
    
    private static void calculateDefaultFaceUVs(float[][] faceUVs) {
        com.stonebreak.core.Game game = com.stonebreak.core.Game.getInstance();
        if (game != null && game.getTextureAtlas() != null) {
            float[] uvCoords = {0.0f, 0.0f, 1.0f, 1.0f}; // Full atlas as fallback
            for (int faceValue = 0; faceValue < 6; faceValue++) {
                convertToFaceVertices(uvCoords, faceUVs[faceValue]);
            }
        } else {
            // Ultimate fallback
            for (int faceValue = 0; faceValue < 6; faceValue++) {
                System.arraycopy(new float[]{0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f}, 
                               0, faceUVs[faceValue], 0, 8);
            }
        }
    }
    
    /**
     * Convert [u1, v1, u2, v2] to face vertices: [u_tl, v_tl, u_bl, v_bl, u_br, v_br, u_tr, v_tr]
     */
    private static void convertToFaceVertices(float[] uvCoords, float[] faceUV) {
        faceUV[0] = uvCoords[0]; // u_topLeft
        faceUV[1] = uvCoords[1]; // v_topLeft
        faceUV[2] = uvCoords[0]; // u_bottomLeft
        faceUV[3] = uvCoords[3]; // v_bottomLeft
        faceUV[4] = uvCoords[2]; // u_bottomRight
        faceUV[5] = uvCoords[3]; // v_bottomRight
        faceUV[6] = uvCoords[2]; // u_topRight
        faceUV[7] = uvCoords[1]; // v_topRight
    }
    
    /**
     * Convert legacy texture coordinates to face vertices.
     */
    private static void convertLegacyCoords(float[] texCoords, float[] faceUV) {
        float texX = texCoords[0];
        float texY = texCoords[1];
        float texSize = 1.0f / 16.0f;
        
        faceUV[0] = texX;
        faceUV[1] = texY;
        faceUV[2] = texX;
        faceUV[3] = texY + texSize;
        faceUV[4] = texX + texSize;
        faceUV[5] = texY + texSize;
        faceUV[6] = texX + texSize;
        faceUV[7] = texY;
    }
    
    /**
     * Determine if an item should use alpha testing.
     */
    public static boolean shouldUseAlphaTesting(Item item) {
        if (item instanceof BlockType blockType) {
            return blockType.isTransparent() && blockType != BlockType.WATER && blockType != BlockType.AIR;
        }
        return false; // Items typically don't need alpha testing
    }
}