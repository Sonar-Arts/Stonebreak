package com.openmason.main.systems.viewport.content;

import com.openmason.main.systems.rendering.model.GenericModelRenderer;
import com.openmason.main.systems.rendering.model.LegacyGeometryGenerator;
import com.openmason.main.systems.rendering.model.UVMode;
import com.openmason.main.systems.rendering.model.editable.BlockModel;
import com.openmason.main.systems.rendering.model.editable.ModelGeometry;
import com.openmason.main.systems.rendering.model.io.omo.OMOFormat;
import com.openmason.main.systems.rendering.model.miscComponents.OMTTextureLoader;
import com.openmason.main.systems.rendering.model.miscComponents.TextureLoadResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Handles loading and unloading of BlockModel content for the viewport.
 *
 * <p>This class follows the Single Responsibility Principle by focusing solely on
 * BlockModel content management, including texture loading, UV mode detection, and
 * legacy geometry generation.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Load BlockModel with texture and geometry</li>
 *   <li>Unload BlockModel and cleanup resources</li>
 *   <li>Update BlockModel texture while preserving geometry</li>
 *   <li>Auto-detect UV mode from texture dimensions</li>
 *   <li>Handle legacy geometry generation for old .OMO formats</li>
 * </ul>
 *
 * <h2>SOLID Principles</h2>
 * <ul>
 *   <li><b>Single Responsibility</b>: Only manages BlockModel content loading</li>
 *   <li><b>Open/Closed</b>: Extensible through composition, closed for modification</li>
 *   <li><b>Dependency Inversion</b>: Depends on abstractions (renderer interface)</li>
 * </ul>
 *
 * @see BlockModel
 * @see GenericModelRenderer
 * @see OMTTextureLoader
 */
public class BlockModelLoader {

    private static final Logger logger = LoggerFactory.getLogger(BlockModelLoader.class);

    // Dependencies (injected)
    private final OMTTextureLoader textureLoader;
    private final GenericModelRenderer modelRenderer;

    // Current state
    private BlockModel currentModel;
    private int currentTextureId = 0;

    /**
     * Create a new BlockModelLoader.
     *
     * @param textureLoader The texture loader for handling .omt files
     * @param modelRenderer The renderer to load geometry into
     */
    public BlockModelLoader(OMTTextureLoader textureLoader, GenericModelRenderer modelRenderer) {
        if (textureLoader == null || modelRenderer == null) {
            throw new IllegalArgumentException("Dependencies cannot be null");
        }
        this.textureLoader = textureLoader;
        this.modelRenderer = modelRenderer;
    }

    /**
     * Load a BlockModel with texture and geometry.
     *
     * <p>This performs a complete load:
     * <ol>
     *   <li>Unload existing model if any</li>
     *   <li>Load texture and auto-detect UV mode</li>
     *   <li>Load geometry (legacy generation if needed)</li>
     *   <li>Update renderer state</li>
     * </ol>
     *
     * @param blockModel The BlockModel to load
     * @return LoadResult containing success status and details
     */
    public LoadResult load(BlockModel blockModel) {
        if (blockModel == null) {
            logger.error("Cannot load null BlockModel");
            return LoadResult.failure("BlockModel is null");
        }

        logger.info("Loading BlockModel: {}", blockModel.getName());

        // Unload existing model first
        unload();
        currentModel = blockModel;

        // Load texture
        TextureLoadStatus textureStatus = loadTexture(blockModel);
        if (!textureStatus.success) {
            logger.warn("Texture load failed, continuing without texture");
        }

        // Load geometry
        GeometryLoadStatus geometryStatus = loadGeometry(blockModel);
        if (!geometryStatus.success) {
            logger.error("Geometry load failed: {}", geometryStatus.message);
            unload(); // Cleanup on failure
            return LoadResult.failure(geometryStatus.message);
        }

        logger.info("BlockModel loaded successfully: {}", blockModel.getName());
        return LoadResult.success(blockModel.getName(), textureStatus.uvMode);
    }

    /**
     * Unload current BlockModel and free resources.
     */
    public void unload() {
        if (currentModel != null) {
            logger.info("Unloading BlockModel: {}", currentModel.getName());

            if (currentTextureId > 0) {
                textureLoader.deleteTexture(currentTextureId);
                currentTextureId = 0;
            }

            currentModel = null;
        }
    }

    /**
     * Update texture for current BlockModel without rebuilding geometry.
     *
     * <p>Use this when changing textures to preserve vertex/geometry modifications.
     * UV coordinates are updated to match the new texture type while preserving
     * vertex positions.
     *
     * @param blockModel The BlockModel with updated texture path
     * @return LoadResult containing success status
     */
    public LoadResult updateTexture(BlockModel blockModel) {
        if (blockModel == null) {
            logger.warn("Cannot update texture for null BlockModel");
            return LoadResult.failure("BlockModel is null");
        }

        logger.info("Updating BlockModel texture (preserving geometry): {}", blockModel.getName());

        // Delete old texture
        if (currentTextureId > 0) {
            textureLoader.deleteTexture(currentTextureId);
            currentTextureId = 0;
        }

        // Update reference
        currentModel = blockModel;

        // Load new texture and update UV coordinates
        Path texturePath = blockModel.getTexturePath();
        if (texturePath != null && Files.exists(texturePath)) {
            TextureLoadResult result = textureLoader.loadTextureComposite(texturePath);

            if (result.isSuccess()) {
                // Auto-detect UV mode and update UVs without rebuilding vertex positions
                UVMode detectedMode = UVMode.detectFromDimensions(result.getWidth(), result.getHeight());
                modelRenderer.setUVMode(detectedMode);
                logger.info("Updated UV mode to {} for texture {}x{} (geometry preserved)",
                    detectedMode, result.getWidth(), result.getHeight());

                currentTextureId = result.getTextureId();
                modelRenderer.setTexture(result.getTextureId());
                logger.info("Updated BlockModel texture: {}", texturePath.getFileName());

                return LoadResult.success(blockModel.getName(), detectedMode);
            } else {
                logger.error("Failed to load texture from: {}", texturePath);
                modelRenderer.setTexture(0);
                return LoadResult.failure("Texture load failed: " + texturePath);
            }
        } else {
            logger.info("BlockModel texture cleared or path invalid: {}", texturePath);
            modelRenderer.setTexture(0);
            return LoadResult.success(blockModel.getName(), null);
        }
    }

    /**
     * Get the currently loaded BlockModel.
     *
     * @return Current BlockModel, or null if none loaded
     */
    public BlockModel getCurrentModel() {
        return currentModel;
    }

    /**
     * Get the current texture ID.
     *
     * @return OpenGL texture ID, or 0 if no texture
     */
    public int getCurrentTextureId() {
        return currentTextureId;
    }

    // ========== Private Helper Methods ==========

    /**
     * Load texture from BlockModel path.
     */
    private TextureLoadStatus loadTexture(BlockModel blockModel) {
        Path texturePath = blockModel.getTexturePath();

        if (texturePath == null || !Files.exists(texturePath)) {
            logger.warn("BlockModel has no valid texture path: {}", texturePath);
            return new TextureLoadStatus(false, null, "No valid texture path");
        }

        TextureLoadResult result = textureLoader.loadTextureComposite(texturePath);
        if (!result.isSuccess()) {
            logger.error("Failed to load texture from: {}", texturePath);
            return new TextureLoadStatus(false, null, "Texture load failed");
        }

        // Auto-detect UV mode based on texture dimensions
        UVMode detectedMode = UVMode.detectFromDimensions(result.getWidth(), result.getHeight());
        modelRenderer.setUVMode(detectedMode);
        logger.info("Auto-detected UV mode: {} for texture {}x{}",
            detectedMode, result.getWidth(), result.getHeight());

        currentTextureId = result.getTextureId();
        modelRenderer.setTexture(result.getTextureId());
        logger.info("Loaded BlockModel texture: {}", result);

        return new TextureLoadStatus(true, detectedMode, "Success");
    }

    /**
     * Load geometry from BlockModel.
     */
    private GeometryLoadStatus loadGeometry(BlockModel blockModel) {
        ModelGeometry geometry = blockModel.getGeometry();

        if (geometry == null) {
            logger.warn("BlockModel has no geometry - model will be invisible");
            return new GeometryLoadStatus(false, "No geometry provided");
        }

        try {
            // LEGACY: Generate mesh from dimensions for old BlockModel format
            // TODO: Modern models should provide topology via .omo files, not dimensions
            @SuppressWarnings("deprecation")
            OMOFormat.MeshData meshData = LegacyGeometryGenerator.generateLegacyBoxMesh(
                geometry.getWidth(),
                geometry.getHeight(),
                geometry.getDepth(),
                geometry.getX(),
                geometry.getY(),
                geometry.getZ(),
                UVMode.CUBE_NET // Legacy default
            );

            modelRenderer.loadMeshData(meshData);
            logger.info("Loaded legacy BlockModel geometry: {}x{}x{} at ({}, {}, {})",
                geometry.getWidth(), geometry.getHeight(), geometry.getDepth(),
                geometry.getX(), geometry.getY(), geometry.getZ());

            return new GeometryLoadStatus(true, "Success");
        } catch (Exception e) {
            logger.error("Failed to load geometry into renderer", e);
            return new GeometryLoadStatus(false, "Geometry load exception: " + e.getMessage());
        }
    }

    // ========== Result Classes ==========

    /**
     * Result of a BlockModel load operation.
     */
    public static class LoadResult {
        private final boolean success;
        private final String message;
        private final UVMode uvMode;

        private LoadResult(boolean success, String message, UVMode uvMode) {
            this.success = success;
            this.message = message;
            this.uvMode = uvMode;
        }

        public static LoadResult success(String modelName, UVMode uvMode) {
            return new LoadResult(true, "Loaded: " + modelName, uvMode);
        }

        public static LoadResult failure(String reason) {
            return new LoadResult(false, reason, null);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public UVMode getUVMode() {
            return uvMode;
        }
    }

    /**
     * Internal status for texture loading.
     */
    private static class TextureLoadStatus {
        final boolean success;
        final UVMode uvMode;
        final String message;

        TextureLoadStatus(boolean success, UVMode uvMode, String message) {
            this.success = success;
            this.message = message;
            this.uvMode = uvMode;
        }
    }

    /**
     * Internal status for geometry loading.
     */
    private static class GeometryLoadStatus {
        final boolean success;
        final String message;

        GeometryLoadStatus(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
