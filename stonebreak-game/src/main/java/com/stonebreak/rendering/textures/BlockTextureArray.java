package com.stonebreak.rendering.textures;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import com.openmason.engine.format.mesh.ParsedFaceMapping;
import com.openmason.engine.format.mesh.ParsedMaterialData;
import com.openmason.engine.format.omo.OMOReader;
import com.openmason.engine.format.sbo.SBOParseResult;
import com.openmason.engine.voxel.sbo.sboRenderer.SBOFaceConventions;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.sbo.SBOBlockBridge;
import com.stonebreak.rendering.sbo.SBOTextureExtractor;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL46;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * SBO-driven block texture array.
 *
 * <p>Replaces the legacy {@code TextureAtlas} for block rendering. Instead of
 * packing every block face into one 2D texture (which causes mip bleeding
 * across tile gutters), this builds a {@code GL_TEXTURE_2D_ARRAY} where each
 * layer is one self-contained 16x16 texture. Block faces select a layer via a
 * per-vertex layer-index attribute; tile-local UVs stay in [0,1].
 *
 * <p>Layers are sourced entirely from SBO files at startup. Identical face
 * textures are de-duplicated by decoded-pixel content, so a uniform block
 * collapses its six faces to a single layer.
 *
 * <p>{@code WATER} is the one non-SBO block: it keeps a dedicated STATIC layer
 * (excluded from de-duplication) used only by UI block icons and FastLOD
 * distant sea sheets — in-world water renders through the dedicated
 * WaterRenderer with a fully procedural shader. Layer 0 is a magenta error
 * texture for unresolved faces.
 */
public class BlockTextureArray {

    private static final Logger logger = LoggerFactory.getLogger(BlockTextureArray.class);

    /** Edge length in pixels of every texture-array layer. */
    public static final int TILE = 16;
    private static final int TILE_PIXELS = TILE * TILE;
    private static final int TILE_BYTES = TILE_PIXELS * 4;

    private final int textureId;
    private final int layerCount;
    private final int errorLayer;
    private final int waterLayer;

    /** Per-block 6-element face→layer table (MMS face order: top,bottom,N,S,E,W). */
    private final Map<BlockType, int[]> blockFaceLayers = new IdentityHashMap<>();
    /** Per-state face-layer overrides. {@code (blockType, stateName) → 6 layer indices}.
     *  Missing entries fall back to {@link #blockFaceLayers}. */
    private final Map<BlockType, Map<String, int[]>> stateBlockFaceLayers = new IdentityHashMap<>();

    /** Decoded ARGB pixels of every layer, retained for UI icon creation. */
    private final List<int[]> layerPixels;

    /**
     * Builds the block texture array from all SBO-backed blocks.
     *
     * @param bridge the initialised SBO block bridge (may be {@code null} —
     *               then every block resolves to the error layer)
     */
    public BlockTextureArray(SBOBlockBridge bridge) {
        SBOTextureExtractor extractor = new SBOTextureExtractor();

        // Distinct layers accumulate here; index in this list == layer index.
        List<int[]> layers = new ArrayList<>();
        // Content de-duplication: pixel-hash → list of candidate layer indices.
        Map<Integer, List<Integer>> dedup = new HashMap<>();

        // Layer 0: magenta/black error texture.
        this.errorLayer = layers.size();
        layers.add(makeErrorTile());

        // Resolve every block's six faces.
        for (BlockType block : BlockType.values()) {
            if (block == BlockType.AIR) {
                continue;
            }
            int[] faceLayers = new int[6];
            if (block == BlockType.WATER) {
                Arrays.fill(faceLayers, -1); // patched to waterLayer below
                blockFaceLayers.put(block, faceLayers);
                continue;
            }
            BufferedImage[] faces = resolveFaceImages(block, bridge, extractor);
            for (int f = 0; f < 6; f++) {
                int[] px = (faces[f] != null) ? toTile(faces[f]) : null;
                if (px == null) {
                    faceLayers[f] = errorLayer;
                } else {
                    faceLayers[f] = internLayer(px, layers, dedup);
                }
            }
            blockFaceLayers.put(block, faceLayers);

            // SBO 1.3+ state variants: for each named state (e.g. "Lit"), the
            // variant carries its own materials list inside its embedded OMO.
            // Resolve its faces with the variant's mappings and intern them as
            // additional layers so state-aware emit can pick them up.
            SBOParseResult sbo = (bridge != null) ? bridge.getSBODefinition(block) : null;
            if (sbo != null && sbo.hasStates() && !sbo.stateOmoData().isEmpty()) {
                Map<String, int[]> perStateLayers = new HashMap<>();
                for (Map.Entry<String, OMOReader.ReadResult> e : sbo.stateOmoData().entrySet()) {
                    String stateName = e.getKey();
                    OMOReader.ReadResult variant = e.getValue();
                    if (variant == null) continue;
                    BufferedImage[] variantFaces = resolveVariantFaceImages(variant, faces);
                    int[] variantLayers = new int[6];
                    boolean anyDifferent = false;
                    for (int f = 0; f < 6; f++) {
                        BufferedImage img = variantFaces[f];
                        int[] px = (img != null) ? toTile(img) : null;
                        if (px == null) {
                            variantLayers[f] = faceLayers[f]; // fall back to base
                        } else {
                            int layer = internLayer(px, layers, dedup);
                            variantLayers[f] = layer;
                            if (layer != faceLayers[f]) anyDifferent = true;
                        }
                    }
                    if (anyDifferent) {
                        perStateLayers.put(stateName, variantLayers);
                    }
                }
                if (!perStateLayers.isEmpty()) {
                    stateBlockFaceLayers.put(block, perStateLayers);
                    logger.info("Registered {} state-variant texture set(s) for {}",
                            perStateLayers.size(), block.name());
                }
            }
        }

        // Dedicated mutable WATER layer (never de-duplicated).
        this.waterLayer = layers.size();
        layers.add(generateWaterTile(0.0f));
        int[] waterFaces = blockFaceLayers.get(BlockType.WATER);
        if (waterFaces != null) {
            Arrays.fill(waterFaces, waterLayer);
        }

        this.layerCount = layers.size();
        this.layerPixels = layers;

        int maxLayers = GL11.glGetInteger(GL30.GL_MAX_ARRAY_TEXTURE_LAYERS);
        if (layerCount > maxLayers) {
            throw new IllegalStateException("BlockTextureArray needs " + layerCount
                    + " layers but GL_MAX_ARRAY_TEXTURE_LAYERS is " + maxLayers);
        }

        this.textureId = uploadArray(layers);

        GpuMemoryTracker.getInstance().track(GpuMemoryTracker.Category.TEXTURE_ATLAS,
                (long) layerCount * TILE_BYTES * 4L / 3L); // base + ~1/3 mipmaps

        logger.info("BlockTextureArray built: {} layers (error={}, water={}), GL id {} (max layers {})",
                layerCount, errorLayer, waterLayer, textureId, maxLayers);
    }

    // ------------------------------------------------------------------
    // Layer resolution
    // ------------------------------------------------------------------

    /**
     * Resolve the six face images of an SBO block, mirroring the per-face
     * material lookup the legacy {@code SBOTextureIntegrator} performed.
     */
    private BufferedImage[] resolveFaceImages(BlockType block, SBOBlockBridge bridge,
                                              SBOTextureExtractor extractor) {
        BufferedImage[] faces = new BufferedImage[6];
        if (bridge == null || !bridge.isSBOBlock(block)) {
            return faces;
        }
        SBOParseResult sbo = bridge.getSBODefinition(block);
        if (sbo == null) {
            return faces;
        }

        Map<Integer, BufferedImage> materialTextures = extractor.extractMaterialTexturesByMaterialId(sbo);
        BufferedImage defaultTexture = extractor.extractDefaultTexture(sbo);

        if (materialTextures.isEmpty() && defaultTexture == null) {
            List<BufferedImage> flat = extractor.extractMaterialTextures(sbo);
            if (!flat.isEmpty()) {
                defaultTexture = flat.getFirst();
            }
        }
        // Prefer a face-mapped material as the default for cross-plane blocks.
        if (defaultTexture == null && !materialTextures.isEmpty()) {
            for (ParsedFaceMapping mapping : sbo.faceMappings()) {
                BufferedImage mapped = materialTextures.get(mapping.materialId());
                if (mapped != null) {
                    defaultTexture = mapped;
                    break;
                }
            }
            if (defaultTexture == null) {
                defaultTexture = materialTextures.values().iterator().next();
            }
        }

        Map<Integer, Integer> faceToMaterialId = new HashMap<>();
        for (ParsedFaceMapping mapping : sbo.faceMappings()) {
            faceToMaterialId.put(mapping.faceId(), mapping.materialId());
        }

        for (int mmsFace = 0; mmsFace < 6; mmsFace++) {
            String faceName = SBOFaceConventions.mmsToAtlasName(mmsFace);
            int gmrFaceId = SBOFaceConventions.atlasNameToGmr(faceName);
            BufferedImage faceTexture = null;
            if (gmrFaceId >= 0) {
                Integer materialId = faceToMaterialId.get(gmrFaceId);
                if (materialId != null) {
                    faceTexture = materialTextures.get(materialId);
                }
            }
            if (faceTexture == null) {
                faceTexture = defaultTexture;
            }
            faces[mmsFace] = faceTexture;
        }
        return faces;
    }

    /**
     * Resolve the six face images for an SBO state variant. The variant's
     * embedded OMO carries its own materials list and faceMappings — we use
     * those to look up per-face textures. Faces not remapped by the variant
     * fall back to {@code baseFaces}.
     */
    private BufferedImage[] resolveVariantFaceImages(OMOReader.ReadResult variant,
                                                     BufferedImage[] baseFaces) {
        BufferedImage[] faces = new BufferedImage[6];

        Map<Integer, BufferedImage> materialTextures = new HashMap<>();
        if (variant.materials() != null) {
            for (ParsedMaterialData mat : variant.materials()) {
                byte[] png = mat.texturePng();
                if (png == null || png.length == 0) continue;
                try {
                    BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
                    if (img != null) materialTextures.put(mat.materialId(), img);
                } catch (java.io.IOException ignored) {
                    // Skip undecodable material — variant face falls back to base.
                }
            }
        }

        Map<Integer, Integer> faceToMaterialId = new HashMap<>();
        if (variant.faceMappings() != null) {
            for (ParsedFaceMapping mapping : variant.faceMappings()) {
                faceToMaterialId.put(mapping.faceId(), mapping.materialId());
            }
        }

        for (int mmsFace = 0; mmsFace < 6; mmsFace++) {
            String faceName = SBOFaceConventions.mmsToAtlasName(mmsFace);
            int gmrFaceId = SBOFaceConventions.atlasNameToGmr(faceName);
            BufferedImage faceTexture = null;
            if (gmrFaceId >= 0) {
                Integer materialId = faceToMaterialId.get(gmrFaceId);
                if (materialId != null) {
                    faceTexture = materialTextures.get(materialId);
                }
            }
            if (faceTexture == null) {
                faceTexture = baseFaces[mmsFace]; // variant didn't override this face
            }
            faces[mmsFace] = faceTexture;
        }
        return faces;
    }

    /** Intern a tile by pixel content, returning an existing or fresh layer index. */
    private static int internLayer(int[] px, List<int[]> layers, Map<Integer, List<Integer>> dedup) {
        int hash = Arrays.hashCode(px);
        List<Integer> candidates = dedup.computeIfAbsent(hash, k -> new ArrayList<>());
        for (int layer : candidates) {
            if (Arrays.equals(layers.get(layer), px)) {
                return layer;
            }
        }
        int layer = layers.size();
        layers.add(px);
        candidates.add(layer);
        return layer;
    }

    /** Decode a {@link BufferedImage} into a 16x16 ARGB int array, scaling if needed. */
    private static int[] toTile(BufferedImage img) {
        BufferedImage scaled = img;
        if (img.getWidth() != TILE || img.getHeight() != TILE) {
            scaled = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
            var g = scaled.createGraphics();
            g.drawImage(img, 0, 0, TILE, TILE, null);
            g.dispose();
        }
        int[] px = new int[TILE_PIXELS];
        scaled.getRGB(0, 0, TILE, TILE, px, 0, TILE);
        return px;
    }

    private static int[] makeErrorTile() {
        int[] px = new int[TILE_PIXELS];
        for (int y = 0; y < TILE; y++) {
            for (int x = 0; x < TILE; x++) {
                boolean magenta = ((x / 4) + (y / 4)) % 2 == 0;
                px[y * TILE + x] = magenta ? 0xFFFF00FF : 0xFF000000;
            }
        }
        return px;
    }

    // ------------------------------------------------------------------
    // GPU upload
    // ------------------------------------------------------------------

    private int uploadArray(List<int[]> layers) {
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, id);

        ByteBuffer data = BufferUtils.createByteBuffer(layers.size() * TILE_BYTES);
        for (int[] layer : layers) {
            putTile(data, layer);
        }
        data.flip();

        GL12.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RGBA8,
                TILE, TILE, layers.size(), 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data);

        // Mipmaps + anisotropic filtering: crisp NEAREST magnification, smooth
        // minification — distant terrain stops shimmering, no atlas bleed.
        GL30.glGenerateMipmap(GL30.GL_TEXTURE_2D_ARRAY);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL14.GL_NEAREST_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        try {
            float maxAniso = GL11.glGetFloat(GL46.GL_MAX_TEXTURE_MAX_ANISOTROPY);
            GL11.glTexParameterf(GL30.GL_TEXTURE_2D_ARRAY, GL46.GL_TEXTURE_MAX_ANISOTROPY,
                    Math.min(8.0f, maxAniso));
        } catch (Throwable t) {
            logger.warn("Anisotropic filtering unavailable: {}", t.getMessage());
        }

        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
        return id;
    }

    private static void putTile(ByteBuffer buf, int[] argb) {
        for (int p : argb) {
            buf.put((byte) ((p >> 16) & 0xFF)); // R
            buf.put((byte) ((p >> 8) & 0xFF));  // G
            buf.put((byte) (p & 0xFF));         // B
            buf.put((byte) ((p >> 24) & 0xFF)); // A
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** GL texture id of the {@code GL_TEXTURE_2D_ARRAY}. */
    public int getTextureId() {
        return textureId;
    }

    /** Number of layers in the array. */
    public int getLayerCount() {
        return layerCount;
    }

    /** Binds the texture array to the currently active texture unit. */
    public void bind() {
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, textureId);
    }

    /**
     * Layer index for a block face.
     *
     * @param block the block type
     * @param face  MMS face index (0=top,1=bottom,2=north,3=south,4=east,5=west)
     */
    public int getBlockFaceLayer(BlockType block, int face) {
        int[] faces = (block == null) ? null : blockFaceLayers.get(block);
        if (faces == null || face < 0 || face >= 6) {
            return errorLayer;
        }
        return faces[face];
    }

    /**
     * Layer index for a block face with a named state-variant override.
     * Falls back to the base block layer when no variant exists for this
     * state, when the state name is null, or when the face index is invalid.
     */
    public int getBlockFaceLayer(BlockType block, String stateName, int face) {
        if (stateName != null && block != null) {
            Map<String, int[]> byState = stateBlockFaceLayers.get(block);
            if (byState != null) {
                int[] variantFaces = byState.get(stateName);
                if (variantFaces != null && face >= 0 && face < 6) {
                    return variantFaces[face];
                }
            }
        }
        return getBlockFaceLayer(block, face);
    }

    /** Layer index of the animated water texture. */
    public int getWaterLayer() {
        return waterLayer;
    }

    /** Layer index of the magenta error texture. */
    public int getErrorLayer() {
        return errorLayer;
    }

    /**
     * Returns the decoded 16x16 ARGB pixels of one layer, for UI icon
     * rendering (the Skija UI backend builds images from this).
     *
     * @return a 256-element ARGB array, or {@code null} for an invalid layer
     */
    public int[] getLayerPixels(int layer) {
        if (layer < 0 || layer >= layerCount) {
            return null;
        }
        return layerPixels.get(layer);
    }

    /** Releases the GL texture. */
    public void cleanup() {
        if (textureId != 0) {
            GL11.glDeleteTextures(textureId);
            GpuMemoryTracker.getInstance().untrack(GpuMemoryTracker.Category.TEXTURE_ATLAS,
                    (long) layerCount * TILE_BYTES * 4L / 3L);
        }
    }

    // ------------------------------------------------------------------
    // Water tile generation (ported from the legacy TextureAtlas)
    // ------------------------------------------------------------------

    private static int[] generateWaterTile(float time) {
        ByteBuffer buf = BufferUtils.createByteBuffer(TILE_BYTES);
        fillWaterBuffer(time, buf);
        int[] px = new int[TILE_PIXELS];
        buf.rewind();
        for (int i = 0; i < TILE_PIXELS; i++) {
            int r = buf.get() & 0xFF;
            int g = buf.get() & 0xFF;
            int b = buf.get() & 0xFF;
            int a = buf.get() & 0xFF;
            px[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return px;
    }

    /** Fills an RGBA buffer with one animated, seamlessly-tiling water frame. */
    private static void fillWaterBuffer(float time, ByteBuffer buffer) {
        buffer.clear();
        final float WAVE_SPEED = 1.5f;
        final float WAVE_AMPLITUDE = 0.15f;
        final float PI2 = (float) (Math.PI * 2.0);
        final float f1 = PI2 * 2.0f;
        final float f2 = PI2 * 4.0f;
        final float f3 = PI2 * 8.0f;

        for (int y = 0; y < TILE; y++) {
            for (int x = 0; x < TILE; x++) {
                float nx = x / (float) TILE;
                float ny = y / (float) TILE;

                float primary = (float) (Math.sin(nx * f1 + time * WAVE_SPEED)
                        * Math.cos(ny * f1 * 0.8f + time * WAVE_SPEED * 0.9f));
                float secondary = (float) (Math.sin(nx * f2 + ny * f1 * 1.5f + time * WAVE_SPEED * 2.0f) * 0.3f);
                float tertiary = (float) (Math.sin(nx * f3 + time * 3.0f)
                        * Math.cos(ny * f3 * 0.75f + time * 2.5f) * 0.1f);

                float waveHeight = (primary + secondary + tertiary) * WAVE_AMPLITUDE;
                float foam = Math.max(0, waveHeight * 2.0f);
                float brightness = 0.8f + waveHeight * 0.5f;
                float depth = 0.7f + waveHeight * 0.3f;
                float caustic = (float) (Math.sin(nx * f3 * 1.5f + time * 2.0f)
                        * Math.cos(ny * f3 * 1.2f + time * 1.8f) * 0.15f);

                buffer.put((byte) (40 + foam * 60 + caustic * 10));
                buffer.put((byte) (110 + depth * 40 + brightness * 20 + caustic * 15));
                buffer.put((byte) (200 + depth * 30 + brightness * 15));
                buffer.put((byte) (150 + waveHeight * 40 + foam * 30));
            }
        }
        buffer.flip();
    }
}
