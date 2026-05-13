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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
    private DynamicSlotAllocator allocator;

    public SBOTextureIntegrator(TextureAtlas textureAtlas, SBOBlockBridge bridge) {
        this.textureAtlas = textureAtlas;
        this.bridge = bridge;
    }

    private DynamicSlotAllocator allocator() {
        if (allocator == null) {
            allocator = new DynamicSlotAllocator(textureAtlas.getAtlasMetadata());
        }
        return allocator;
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

        // Cross-plane blocks (flowers, saplings) typically map only the two
        // visible plane faces (GMR 0/1 — atlas north/south) and leave the
        // remaining atlas faces (top/bottom/east/west) without an explicit
        // mapping or separate default texture. Hand/inventory renderers often
        // read `rose_top`/etc. for their UVs, so if we don't overlay every
        // atlas slot they'll show the pre-SBO pixels. When no explicit default
        // exists, prefer a material that's actually referenced by a face
        // mapping — those are the visual sprites by definition. An arbitrary
        // HashMap-iteration fallback can pick placeholder materials (tiny
        // utility textures with distinct materialIds) and splash them across
        // the unmapped atlas slots, which ends up looking like a different
        // block entirely when sampled by CROSS geometry that reads `top`.
        if (defaultTexture == null && !materialTextures.isEmpty()) {
            for (ParsedFaceMapping mapping : sbo.faceMappings()) {
                BufferedImage mapped = materialTextures.get(mapping.materialId());
                if (mapped != null) {
                    defaultTexture = mapped;
                    break;
                }
            }
            // Last-resort fallback: any available material, even if unmapped.
            if (defaultTexture == null) {
                defaultTexture = materialTextures.values().iterator().next();
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
            if (entry == null) {
                // No pre-baked atlas slot for this block (new SBO drop-in).
                // Allocate a fresh slot from the spare region of the atlas
                // and register it so future lookups resolve correctly.
                entry = allocateAtlasEntry(blockType, faceName);
                if (entry == null) continue;
            }

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

    /**
     * Allocate a fresh atlas slot for an SBO block that has no pre-baked
     * entry in {@code atlas_metadata.json}. The slot is registered under
     * {@code "<blockname>_<face>"} so subsequent {@link
     * AtlasMetadata#findBlockTexture} calls resolve it without falling back
     * to the error texture.
     *
     * <p>Returns {@code null} if the atlas is full or has no metadata.
     */
    private AtlasMetadata.TextureEntry allocateAtlasEntry(BlockType blockType, String face) {
        AtlasMetadata metadata = textureAtlas.getAtlasMetadata();
        if (metadata == null) return null;
        String blockName = getBlockTextureName(blockType);
        if (blockName == null) return null;

        int[] xy = allocator().allocate();
        if (xy == null) {
            logger.warn("Atlas full — cannot allocate slot for SBO block {} face {}", blockType, face);
            return null;
        }

        int tileSize = metadata.getTextureSize();
        AtlasMetadata.TextureEntry entry = new AtlasMetadata.TextureEntry(
                xy[0], xy[1], tileSize, tileSize, "block_cube");
        metadata.registerRuntimeTexture(blockName + "_" + face, entry);
        logger.info("Allocated atlas slot for {} face {} at ({}, {})",
                blockType, face, xy[0], xy[1]);
        return entry;
    }

    /**
     * Resolve the atlas key prefix for a block type. The atlas is inconsistent
     * on the {@code _block} suffix — {@code dirt_block_*}/{@code grass_block_*}
     * use it, while {@code stone_*}, {@code cobblestone_*}, {@code gravel},
     * {@code coal_ore_*}, {@code sandstone_*}, etc. do not. {@link AtlasMetadata
     * #findBlockTexture} will try {@code "<name>_<face>"} then fall back to the
     * bare {@code "<name>"} for uniform blocks like gravel, so we just need to
     * hand it the base name that actually exists.
     */
    private String getBlockTextureName(BlockType blockType) {
        if (blockType == BlockType.DIRT) return "dirt_block";
        if (blockType == BlockType.GRASS) return "grass_block";
        if (blockType == BlockType.WOOD_PLANKS) return "wood_planks_custom";
        if (blockType == BlockType.PINE_WOOD_PLANKS) return "pine_wood_planks_custom";
        if (blockType == BlockType.ELM_WOOD_PLANKS) return "elm_wood_planks_custom";
        if (blockType == BlockType.WORKBENCH) return "workbench_custom";
        return blockType.name().toLowerCase();
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

    /**
     * Scans the atlas for occupied tile-grid cells once at construction,
     * then doles out free cells in row-major order. Atlas tiles are
     * assumed to be aligned to {@code textureSize} (16px in the stock
     * atlas). Off-grid entries are conservatively rounded to the
     * surrounding tile so they're never overwritten.
     */
    private static final class DynamicSlotAllocator {

        private final int tileSize;
        private final int cols;
        private final int rows;
        private final Set<Long> occupied;
        private int cursor; // linear (row * cols + col) of next slot to try

        DynamicSlotAllocator(AtlasMetadata metadata) {
            if (metadata == null) {
                this.tileSize = 16;
                this.cols = 0;
                this.rows = 0;
                this.occupied = new HashSet<>();
                this.cursor = 0;
                return;
            }
            this.tileSize = Math.max(1, metadata.getTextureSize());
            int[] dims = metadata.getAtlasDimensions();
            this.cols = Math.max(1, dims[0] / tileSize);
            this.rows = Math.max(1, dims[1] / tileSize);
            this.occupied = new HashSet<>();
            this.cursor = 0;

            if (metadata.getTextures() != null) {
                for (AtlasMetadata.TextureEntry e : metadata.getTextures().values()) {
                    int colStart = e.getX() / tileSize;
                    int rowStart = e.getY() / tileSize;
                    int colEnd = (e.getX() + Math.max(1, e.getWidth()) - 1) / tileSize;
                    int rowEnd = (e.getY() + Math.max(1, e.getHeight()) - 1) / tileSize;
                    for (int r = rowStart; r <= rowEnd && r < rows; r++) {
                        for (int c = colStart; c <= colEnd && c < cols; c++) {
                            occupied.add(key(c, r));
                        }
                    }
                }
            }
        }

        /**
         * @return pixel coordinates {@code [x, y]} of a freshly reserved
         *         tile, or {@code null} if the atlas has no free slots.
         */
        int[] allocate() {
            int total = cols * rows;
            while (cursor < total) {
                int c = cursor % cols;
                int r = cursor / cols;
                cursor++;
                long k = key(c, r);
                if (!occupied.contains(k)) {
                    occupied.add(k);
                    return new int[]{c * tileSize, r * tileSize};
                }
            }
            return null;
        }

        private static long key(int col, int row) {
            return (((long) row) << 32) | (col & 0xFFFFFFFFL);
        }
    }
}
