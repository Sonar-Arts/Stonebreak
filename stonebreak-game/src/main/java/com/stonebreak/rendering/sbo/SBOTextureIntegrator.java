package com.stonebreak.rendering.sbo;

import com.openmason.engine.format.mesh.ParsedFaceMapping;
import com.openmason.engine.format.sbo.SBOParseResult;
import com.openmason.engine.voxel.sbo.sboRenderer.SBOFaceConventions;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.textures.atlas.AtlasMetadata;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Integrates SBO textures into the game's texture atlas at runtime.
 *
 * <p>For each SBO block, extracts the material texture and overlays it
 * onto the existing atlas using {@code glTexSubImage2D} at the position
 * where the legacy block texture lives. This avoids atlas rebuild.
 *
 * <p>For uniform blocks (like dirt), the same texture is overlaid onto
 * all face positions. For directional blocks, per-face textures are used.
 */
public class SBOTextureIntegrator {

    private static final Logger logger = LoggerFactory.getLogger(SBOTextureIntegrator.class);

    private final TextureAtlas textureAtlas;
    private final SBOBlockBridge bridge;
    private final SBOTextureExtractor extractor = new SBOTextureExtractor();

    public SBOTextureIntegrator(TextureAtlas textureAtlas, SBOBlockBridge bridge) {
        this.textureAtlas = textureAtlas;
        this.bridge = bridge;
    }

    /**
     * Integrate all SBO block textures into the atlas.
     * Call this after the atlas is loaded but before chunks are built.
     *
     * @return number of blocks successfully integrated
     */
    public int integrateAll() {
        int integrated = 0;
        int atlasTextureId = textureAtlas.getTextureId();

        if (atlasTextureId == 0) {
            logger.error("Cannot integrate SBO textures: atlas texture ID is 0");
            return 0;
        }

        for (BlockType blockType : BlockType.values()) {
            if (!bridge.isSBOBlock(blockType)) continue;

            SBOParseResult sbo = bridge.getSBODefinition(blockType);
            if (integrateBlock(blockType, sbo, atlasTextureId)) {
                integrated++;
            }
        }

        logger.info("SBO texture integration: {} blocks updated in atlas", integrated);
        return integrated;
    }

    private boolean integrateBlock(BlockType blockType, SBOParseResult sbo, int atlasTextureId) {
        // Extract material textures keyed by materialId
        Map<Integer, BufferedImage> materialTextures = extractor.extractMaterialTexturesByMaterialId(sbo);

        // Extract default texture as fallback
        BufferedImage defaultTexture = extractor.extractDefaultTexture(sbo);

        // If no material textures and no default, try the flat list (legacy SBOs)
        if (materialTextures.isEmpty() && defaultTexture == null) {
            var flatList = extractor.extractMaterialTextures(sbo);
            if (!flatList.isEmpty()) {
                defaultTexture = flatList.getFirst();
            }
        }

        if (materialTextures.isEmpty() && defaultTexture == null) {
            logger.warn("No texture found in SBO for block {}", blockType);
            return false;
        }

        // Build faceId → materialId lookup from face mappings
        Map<Integer, Integer> faceToMaterialId = new HashMap<>();
        for (ParsedFaceMapping mapping : sbo.faceMappings()) {
            faceToMaterialId.put(mapping.faceId(), mapping.materialId());
        }

        // For each face, find the correct texture via face mapping → material
        String[] faces = {"top", "bottom", "north", "south", "east", "west"};
        boolean anySuccess = false;

        for (int atlasIdx = 0; atlasIdx < faces.length; atlasIdx++) {
            String faceName = faces[atlasIdx];
            AtlasMetadata.TextureEntry entry = findAtlasEntry(blockType, faceName);
            if (entry == null) continue;

            // Determine the GMR face ID that corresponds to this atlas face name
            int gmrFaceId = SBOFaceConventions.atlasNameToGmr(faceName);

            // Look up which material this face uses
            BufferedImage faceTexture = null;
            if (gmrFaceId >= 0) {
                Integer materialId = faceToMaterialId.get(gmrFaceId);
                if (materialId != null) {
                    faceTexture = materialTextures.get(materialId);
                }
            }

            // Fall back to default texture if no per-face mapping or material
            if (faceTexture == null) {
                faceTexture = defaultTexture;
            }

            if (faceTexture == null) continue;

            // Scale texture to atlas tile size if needed
            BufferedImage tileTexture = scaleTo(faceTexture, entry.getWidth(), entry.getHeight());

            // Upload to atlas GPU texture at the entry's position
            overlayOnAtlas(atlasTextureId, tileTexture, entry.getX(), entry.getY());
            anySuccess = true;
        }

        if (anySuccess) {
            int uniqueMaterials = faceToMaterialId.values().stream().collect(
                    java.util.stream.Collectors.toSet()).size();
            logger.info("Integrated SBO texture for {} ({}) — {} face mappings, {} unique materials",
                    blockType, sbo.getObjectName(), faceToMaterialId.size(), uniqueMaterials);
        }
        return anySuccess;
    }

    private AtlasMetadata.TextureEntry findAtlasEntry(BlockType blockType, String face) {
        AtlasMetadata metadata = textureAtlas.getAtlasMetadata();
        if (metadata == null) return null;

        String blockName = getBlockTextureName(blockType);
        if (blockName == null) return null;

        return metadata.findBlockTexture(blockName, face);
    }

    private String getBlockTextureName(BlockType blockType) {
        return switch (blockType) {
            case DIRT -> "dirt_block";
            case STONE -> "stone_block";
            case GRASS -> "grass_block";
            case SAND -> "sand_block";
            case GRAVEL -> "gravel_block";
            case COBBLESTONE -> "cobblestone_block";
            default -> blockType.name().toLowerCase() + "_block";
        };
    }

    private BufferedImage scaleTo(BufferedImage source, int width, int height) {
        if (source.getWidth() == width && source.getHeight() == height) {
            return source;
        }
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var g = scaled.createGraphics();
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return scaled;
    }

    private void overlayOnAtlas(int atlasTextureId, BufferedImage tile, int x, int y) {
        int w = tile.getWidth();
        int h = tile.getHeight();

        // Convert BufferedImage to RGBA ByteBuffer
        ByteBuffer buffer = ByteBuffer.allocateDirect(w * h * 4);
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int argb = tile.getRGB(px, py);
                buffer.put((byte) ((argb >> 16) & 0xFF)); // R
                buffer.put((byte) ((argb >> 8) & 0xFF));  // G
                buffer.put((byte) (argb & 0xFF));          // B
                buffer.put((byte) ((argb >> 24) & 0xFF)); // A
            }
        }
        buffer.flip();

        // Upload to atlas at the correct position
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlasTextureId);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, w, h,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        logger.debug("Overlaid SBO texture at atlas position ({}, {}) size {}x{}", x, y, w, h);
    }
}
