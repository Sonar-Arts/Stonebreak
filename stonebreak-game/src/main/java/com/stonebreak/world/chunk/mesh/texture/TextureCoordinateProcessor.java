package com.stonebreak.world.chunk.mesh.texture;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.rendering.core.API.commonBlockResources.models.BlockDefinition;
import com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager;
import com.stonebreak.rendering.core.API.commonBlockResources.texturing.TextureResourceManager;

/**
 * Service responsible for processing texture coordinates for blocks.
 * Follows Single Responsibility Principle by handling only texture coordinate generation.
 */
public class TextureCoordinateProcessor {
    
    /**
     * Maps internal face index to CBR face string.
     * 0: up, 1: down, 2: south(+Z), 3: north(-Z), 4: east(+X), 5: west(-X)
     */
    private static String mapFaceIndexToName(int faceIndex) {
        return switch (faceIndex) {
            case 0 -> "top";
            case 1 -> "bottom";
            case 2 -> "south"; // +Z
            case 3 -> "north"; // -Z
            case 4 -> "east";  // +X
            case 5 -> "west";  // -X
            default -> "up";
        };
    }

    /**
     * Some atlas lookups may fail and return a full-atlas span (0..1,0..1) or
     * otherwise invalid values. Detect and allow a fallback to direct atlas lookup.
     */
    private static boolean isSuspiciousAtlasSpan(float[] uv) {
        if (uv == null || uv.length < 4) return true;
        // Outside [0,1] range
        if (uv[0] < 0f || uv[1] < 0f || uv[2] > 1f || uv[3] > 1f) return true;
        float du = Math.abs(uv[2] - uv[0]);
        float dv = Math.abs(uv[3] - uv[1]);
        // Extremely large region (likely fallback or error texture)
        return du >= 0.95f || dv >= 0.95f || du <= 0.0f || dv <= 0.0f;
    }
    
    /**
     * Adds texture coordinates and flags for a face
     */
    public void addTextureCoordinatesAndFlags(BlockType blockType, int face, float worldX, float worldY, float worldZ, 
                                            float blockHeight, float[] textureArray, int textureIndex, 
                                            float[] isWaterFlags, float[] isAlphaTestedFlags, int flagIndex) {
        // Resolve base face texture coordinates via CBR (fallback to legacy if not initialized)
        float texX, texY, texSize;
        float[] uvCoords = null;
        if (CBRResourceManager.isInitialized()) {
            try {
                CBRResourceManager cbr = CBRResourceManager.getInstance();
                TextureResourceManager textureMgr = cbr.getTextureManager();
                BlockDefinition def = cbr.getBlockRegistry().getDefinition(blockType.ordinal()).orElse(null);
                if (def != null) {
                    String faceName = mapFaceIndexToName(face);
                    TextureResourceManager.TextureCoordinates coords = textureMgr.resolveBlockFaceTexture(def, faceName);
                    if (coords != null) {
                        uvCoords = coords.toArray();
                    }
                } else {
                    // Fallback to legacy mapping through CBR texture manager
                    TextureResourceManager.TextureCoordinates coords = textureMgr.resolveBlockType(blockType);
                    if (coords != null) {
                        uvCoords = coords.toArray();
                    }
                }
            } catch (Exception e) {
                // CBR path failed, will fallback to legacy below
            }
        }
        if (uvCoords == null || isSuspiciousAtlasSpan(uvCoords)) {
            // Fallback to modern TextureAtlas directly if available
            Game game = Game.getInstance();
            if (game != null && game.getTextureAtlas() != null) {
                uvCoords = game.getTextureAtlas().getBlockFaceUVs(blockType, BlockType.Face.values()[face]);
            }
            // If still suspicious after atlas lookup, fallback to legacy grid
            if (uvCoords == null || isSuspiciousAtlasSpan(uvCoords)) {
                float[] texCoords = blockType.getTextureCoords(BlockType.Face.values()[face]);
                float legacyU = texCoords[0] / 16.0f;
                float legacyV = texCoords[1] / 16.0f;
                float legacySize = 1.0f / 16.0f;
                uvCoords = new float[] { legacyU, legacyV, legacyU + legacySize, legacyV + legacySize };
            }
        }
        // Derive base tile origin and span
        texX = uvCoords[0];
        texY = uvCoords[1];
        texSize = uvCoords[2] - uvCoords[0];
        
        float u_topLeft, v_topLeft, u_bottomLeft, v_bottomLeft;
        float u_bottomRight, v_bottomRight, u_topRight, v_topRight;
        
        if (blockType == BlockType.WATER) {
            // Special water texture handling
            addWaterTextureCoordinates(face, worldX, worldY, worldZ, blockHeight, texX, texY, texSize, 
                                     textureArray, textureIndex);
        } else {
            // For non-water blocks, use texture coordinates from atlas
            if (uvCoords != null) {
                // Use the modern atlas UV coordinates directly [u1, v1, u2, v2]
                u_topLeft = uvCoords[0];
                v_topLeft = uvCoords[1];
                u_bottomLeft = uvCoords[0];
                v_bottomLeft = uvCoords[3];
                u_bottomRight = uvCoords[2];
                v_bottomRight = uvCoords[3];
                u_topRight = uvCoords[2];
                v_topRight = uvCoords[1];
            } else {
                // Fallback to legacy calculated coordinates
                u_topLeft = texX;
                v_topLeft = texY;
                u_bottomLeft = texX;
                v_bottomLeft = texY + texSize;
                u_bottomRight = texX + texSize;
                v_bottomRight = texY + texSize;
                u_topRight = texX + texSize;
                v_topRight = texY;
            }
            
            // Apply UV mapping based on face orientation
            applyUVMapping(face, u_topLeft, v_topLeft, u_bottomLeft, v_bottomLeft, u_bottomRight, v_bottomRight, 
                         u_topRight, v_topRight, textureArray, textureIndex);
        }

        // Add isWater flag for each of the 4 vertices of this face
        float isWaterValue = 0.0f;
        if (blockType == BlockType.WATER) {
            isWaterValue = Math.max(0.0f, Math.min(0.875f, blockHeight));
        }
        isWaterFlags[flagIndex] = isWaterValue;
        isWaterFlags[flagIndex + 1] = isWaterValue;
        isWaterFlags[flagIndex + 2] = isWaterValue;
        isWaterFlags[flagIndex + 3] = isWaterValue;
        
        // Add isAlphaTested flag for each of the 4 vertices of this face
        float isAlphaTestedValue;
        if (CBRResourceManager.isInitialized()) {
            try {
                CBRResourceManager cbr = CBRResourceManager.getInstance();
                BlockDefinition def = cbr.getBlockRegistry().getDefinition(blockType.ordinal()).orElse(null);
                if (def != null) {
                    isAlphaTestedValue = (def.getRenderLayer() == BlockDefinition.RenderLayer.CUTOUT) ? 1.0f : 0.0f;
                } else {
                    isAlphaTestedValue = (blockType.isTransparent() && blockType != BlockType.WATER && blockType != BlockType.AIR) ? 1.0f : 0.0f;
                }
            } catch (Exception e) {
                isAlphaTestedValue = (blockType.isTransparent() && blockType != BlockType.WATER && blockType != BlockType.AIR) ? 1.0f : 0.0f;
            }
        } else {
            isAlphaTestedValue = (blockType.isTransparent() && blockType != BlockType.WATER && blockType != BlockType.AIR) ? 1.0f : 0.0f;
        }
        isAlphaTestedFlags[flagIndex] = isAlphaTestedValue;
        isAlphaTestedFlags[flagIndex + 1] = isAlphaTestedValue;
        isAlphaTestedFlags[flagIndex + 2] = isAlphaTestedValue;
        isAlphaTestedFlags[flagIndex + 3] = isAlphaTestedValue;
    }

    /**
     * Adds special water texture coordinates with seamless tiling
     */
    private void addWaterTextureCoordinates(int face, float worldX, float worldY, float worldZ, float blockHeight, 
                                          float texX, float texY, float texSize, float[] textureArray, int textureIndex) {
        // For water blocks, use continuous world-space texture coordinates for completely seamless tiling
        // This makes the texture flow continuously across all water blocks without any visible boundaries
        float textureScale = 0.0625f; // Very small scale for large-area seamless appearance
        
        float u_topLeft, v_topLeft, u_bottomLeft, v_bottomLeft;
        float u_bottomRight, v_bottomRight, u_topRight, v_topRight;
        
        switch (face) {
            case 0 -> { // Top face - use X,Z world coordinates
                u_topLeft = worldX * textureScale;
                v_topLeft = worldZ * textureScale;
                u_bottomLeft = worldX * textureScale;
                v_bottomLeft = (worldZ + 1) * textureScale;
                u_bottomRight = (worldX + 1) * textureScale;
                v_bottomRight = (worldZ + 1) * textureScale;
                u_topRight = (worldX + 1) * textureScale;
                v_topRight = worldZ * textureScale;
            }
            case 1 -> { // Bottom face - use X,Z world coordinates
                u_topLeft = worldX * textureScale;
                v_topLeft = worldZ * textureScale;
                u_bottomLeft = worldX * textureScale;
                v_bottomLeft = (worldZ + 1) * textureScale;
                u_bottomRight = (worldX + 1) * textureScale;
                v_bottomRight = (worldZ + 1) * textureScale;
                u_topRight = (worldX + 1) * textureScale;
                v_topRight = worldZ * textureScale;
            }
            case 2, 3 -> { // Front/Back faces - use X,Y world coordinates
                u_topLeft = worldX * textureScale;
                v_topLeft = (worldY + blockHeight) * textureScale;
                u_bottomLeft = worldX * textureScale;
                v_bottomLeft = worldY * textureScale;
                u_bottomRight = (worldX + 1) * textureScale;
                v_bottomRight = worldY * textureScale;
                u_topRight = (worldX + 1) * textureScale;
                v_topRight = (worldY + blockHeight) * textureScale;
            }
            case 4, 5 -> { // Left/Right faces - use Z,Y world coordinates  
                u_topLeft = worldZ * textureScale;
                v_topLeft = (worldY + blockHeight) * textureScale;
                u_bottomLeft = worldZ * textureScale;
                v_bottomLeft = worldY * textureScale;
                u_bottomRight = (worldZ + 1) * textureScale;
                v_bottomRight = worldY * textureScale;
                u_topRight = (worldZ + 1) * textureScale;
                v_topRight = (worldY + blockHeight) * textureScale;
            }
            default -> {
                // Fallback to regular texture coordinates
                u_topLeft = texX;
                v_topLeft = texY;
                u_bottomLeft = texX;
                v_bottomLeft = texY + texSize;
                u_bottomRight = texX + texSize;
                v_bottomRight = texY + texSize;
                u_topRight = texX + texSize;
                v_topRight = texY;
            }
        }
        
        // Add subtle pseudo-random offset to break up any remaining grid patterns
        // This creates more natural-looking water texture variation
        float noiseScale = 0.03f;
        float noiseU = (float)(Math.sin(worldX * 0.1f + worldZ * 0.17f) * noiseScale);
        float noiseV = (float)(Math.cos(worldX * 0.13f + worldZ * 0.11f) * noiseScale);
        
        // Apply noise offset
        u_topLeft += noiseU;
        v_topLeft += noiseV;
        u_bottomLeft += noiseU;
        v_bottomLeft += noiseV;
        u_bottomRight += noiseU;
        v_bottomRight += noiseV;
        u_topRight += noiseU;
        v_topRight += noiseV;
        
        // Apply fractional part to keep texture coordinates within 0-1 range while maintaining continuity
        u_topLeft = u_topLeft % 1.0f;
        v_topLeft = v_topLeft % 1.0f;
        u_bottomLeft = u_bottomLeft % 1.0f;
        v_bottomLeft = v_bottomLeft % 1.0f;
        u_bottomRight = u_bottomRight % 1.0f;
        v_bottomRight = v_bottomRight % 1.0f;
        u_topRight = u_topRight % 1.0f;
        v_topRight = v_topRight % 1.0f;
        
        // Handle negative values properly for seamless wrapping
        if (u_topLeft < 0) u_topLeft += 1.0f;
        if (v_topLeft < 0) v_topLeft += 1.0f;
        if (u_bottomLeft < 0) u_bottomLeft += 1.0f;
        if (v_bottomLeft < 0) v_bottomLeft += 1.0f;
        if (u_bottomRight < 0) u_bottomRight += 1.0f;
        if (v_bottomRight < 0) v_bottomRight += 1.0f;
        if (u_topRight < 0) u_topRight += 1.0f;
        if (v_topRight < 0) v_topRight += 1.0f;
        
        // Scale to fit within the water texture's atlas coordinates
        u_topLeft = texX + u_topLeft * texSize;
        v_topLeft = texY + v_topLeft * texSize;
        u_bottomLeft = texX + u_bottomLeft * texSize;
        v_bottomLeft = texY + v_bottomLeft * texSize;
        u_bottomRight = texX + u_bottomRight * texSize;
        v_bottomRight = texY + v_bottomRight * texSize;
        u_topRight = texX + u_topRight * texSize;
        v_topRight = texY + v_topRight * texSize;
        
        // Apply UV mapping based on face orientation
        applyUVMapping(face, u_topLeft, v_topLeft, u_bottomLeft, v_bottomLeft, u_bottomRight, v_bottomRight, 
                     u_topRight, v_topRight, textureArray, textureIndex);
    }

    /**
     * Applies UV mapping based on face orientation
     */
    private void applyUVMapping(int face, float u_topLeft, float v_topLeft, float u_bottomLeft, float v_bottomLeft,
                               float u_bottomRight, float v_bottomRight, float u_topRight, float v_topRight,
                               float[] textureArray, int textureIndex) {
        switch (face) {
            case 0, 1 -> { // Top or Bottom face
                textureArray[textureIndex++] = u_topLeft; textureArray[textureIndex++] = v_topLeft;         // V0
                textureArray[textureIndex++] = u_bottomLeft; textureArray[textureIndex++] = v_bottomLeft;   // V1
                textureArray[textureIndex++] = u_bottomRight; textureArray[textureIndex++] = v_bottomRight; // V2
                textureArray[textureIndex++] = u_topRight; textureArray[textureIndex++] = v_topRight;       // V3
            }
            case 2, 5 -> { // Front (+Z) or Left (-X)
                // Vertices for these faces are ordered: BL, TL, TR, BR
                // Desired UVs: BottomLeft, TopLeft, TopRight, BottomRight
                textureArray[textureIndex++] = u_bottomLeft; textureArray[textureIndex++] = v_bottomLeft;   // For V0 (BL of face)
                textureArray[textureIndex++] = u_topLeft; textureArray[textureIndex++] = v_topLeft;         // For V1 (TL of face)
                textureArray[textureIndex++] = u_topRight; textureArray[textureIndex++] = v_topRight;       // For V2 (TR of face)
                textureArray[textureIndex++] = u_bottomRight; textureArray[textureIndex++] = v_bottomRight; // For V3 (BR of face)
            }
            case 3 -> { // Back (-Z)
                // Vertices for this face are ordered: BR_face, BL_face, TL_face, TR_face
                // Desired UVs: BottomRight, BottomLeft, TopLeft, TopRight
                textureArray[textureIndex++] = u_bottomRight; textureArray[textureIndex++] = v_bottomRight; // For V0 (BR of face)
                textureArray[textureIndex++] = u_bottomLeft; textureArray[textureIndex++] = v_bottomLeft;   // For V1 (BL of face)
                textureArray[textureIndex++] = u_topLeft; textureArray[textureIndex++] = v_topLeft;         // For V2 (TL of face)
                textureArray[textureIndex++] = u_topRight; textureArray[textureIndex++] = v_topRight;       // For V3 (TR of face)
            }
            case 4 -> { // Right (+X)
                // Vertices for this face are ordered: BL_face, BR_face, TR_face, TL_face
                // Desired UVs: BottomLeft, BottomRight, TopRight, TopLeft
                textureArray[textureIndex++] = u_bottomLeft; textureArray[textureIndex++] = v_bottomLeft;   // For V0 (BL of face)
                textureArray[textureIndex++] = u_bottomRight; textureArray[textureIndex++] = v_bottomRight; // For V1 (BR of face)
                textureArray[textureIndex++] = u_topRight; textureArray[textureIndex++] = v_topRight;       // For V2 (TR of face)
                textureArray[textureIndex++] = u_topLeft; textureArray[textureIndex++] = v_topLeft;         // For V3 (TL of face)
            }
        }
    }
}