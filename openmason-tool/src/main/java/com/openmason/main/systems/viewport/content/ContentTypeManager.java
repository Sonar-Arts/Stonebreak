package com.openmason.main.systems.viewport.content;

import com.openmason.main.systems.rendering.model.editable.BlockModel;
import com.openmason.main.systems.viewport.state.RenderingState;
import com.openmason.main.systems.viewport.state.TransformState;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages content type switching in the viewport (blocks, items, models).
 *
 * <p>This class follows the Single Responsibility Principle by focusing solely on
 * coordinating content type transitions and state updates. It acts as a facade
 * for content switching operations.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Switch to Model mode</li>
 *   <li>Switch to Block mode</li>
 *   <li>Switch to Item mode</li>
 *   <li>Coordinate state updates across transitions</li>
 *   <li>Manage model lifecycle via ModelContentLoader</li>
 * </ul>
 *
 * @see ModelContentLoader
 * @see RenderingState
 * @see TransformState
 */
public class ContentTypeManager {

    private static final Logger logger = LoggerFactory.getLogger(ContentTypeManager.class);

    // Dependencies (injected)
    private final ModelContentLoader modelContentLoader;
    private final RenderingState renderingState;
    private final TransformState transformState;

    // Current content type
    private ContentType currentType = ContentType.NONE;

    /**
     * Enumeration of content types that can be displayed in the viewport.
     */
    public enum ContentType {
        NONE,
        BLOCK_MODEL,
        BLOCK,
        ITEM,
        SBT_TEXTURE
    }

    /**
     * Create a new ContentTypeManager.
     *
     * @param modelContentLoader The loader for model content
     * @param renderingState The rendering state to update
     * @param transformState The transform state to reset
     */
    public ContentTypeManager(ModelContentLoader modelContentLoader,
                              RenderingState renderingState,
                              TransformState transformState) {
        if (modelContentLoader == null || renderingState == null || transformState == null) {
            throw new IllegalArgumentException("Dependencies cannot be null");
        }
        this.modelContentLoader = modelContentLoader;
        this.renderingState = renderingState;
        this.transformState = transformState;
    }

    /**
     * Switch to Model mode and load the specified model.
     *
     * <p>This performs:
     * <ol>
     *   <li>Load model via ModelContentLoader</li>
     *   <li>Update rendering state to model mode</li>
     *   <li>Reset transform to default position</li>
     * </ol>
     *
     * @param blockModel The model to load and display
     * @return LoadResult from ModelContentLoader
     */
    public ModelContentLoader.LoadResult switchToModel(BlockModel blockModel) {
        if (blockModel == null) {
            logger.error("Cannot switch to null model");
            return ModelContentLoader.LoadResult.failure("Model is null");
        }

        logger.info("Switching to Model mode: {}", blockModel.getName());

        // Load model
        ModelContentLoader.LoadResult result = modelContentLoader.load(blockModel);
        if (!result.isSuccess()) {
            logger.error("Failed to load model: {}", result.getMessage());
            return result;
        }

        // Update state
        renderingState.setBlockModelMode(blockModel.getName());
        transformState.resetPosition();
        currentType = ContentType.BLOCK_MODEL;

        logger.info("Switched to Model mode successfully: {}", blockModel.getName());
        return result;
    }

    /**
     * Update texture for current model without rebuilding geometry.
     *
     * <p>Use this to preserve vertex/geometry modifications while changing textures.
     *
     * @param blockModel The model with updated texture path
     * @return LoadResult from ModelContentLoader
     */
    public ModelContentLoader.LoadResult updateModelTexture(BlockModel blockModel) {
        if (blockModel == null) {
            logger.warn("Cannot update texture for null model");
            return ModelContentLoader.LoadResult.failure("Model is null");
        }

        if (currentType != ContentType.BLOCK_MODEL) {
            logger.warn("Not in Model mode, cannot update texture");
            return ModelContentLoader.LoadResult.failure("Not in Model mode");
        }

        logger.info("Updating model texture: {}", blockModel.getName());
        return modelContentLoader.updateTexture(blockModel);
    }

    /**
     * Switch to Block mode and display the specified block type.
     *
     * <p>This performs:
     * <ol>
     *   <li>Unload any model content</li>
     *   <li>Update rendering state to Block mode</li>
     *   <li>Reset transform to default position</li>
     * </ol>
     *
     * @param blockType The block type to display
     */
    public void switchToBlock(BlockType blockType) {
        if (blockType == null) {
            logger.warn("Cannot switch to null block type");
            return;
        }

        logger.info("Switching to Block mode: {}", blockType);

        // Unload model if active
        if (currentType == ContentType.BLOCK_MODEL) {
            modelContentLoader.unload();
        }

        // Update state
        renderingState.setBlockMode(blockType);
        transformState.resetPosition();
        currentType = ContentType.BLOCK;

        logger.info("Switched to Block mode: {}", blockType);
    }

    /**
     * Switch to Item mode and display the specified item type.
     *
     * <p>This performs:
     * <ol>
     *   <li>Unload any model content</li>
     *   <li>Update rendering state to Item mode</li>
     *   <li>Reset transform to default position</li>
     * </ol>
     *
     * @param itemType The item type to display
     */
    /**
     * Switch to SBT texture mode, displaying a voxelized representation of
     * the .sbt file at {@code sbtPath}. Mirrors {@link #switchToItem}.
     */
    public void switchToSBT(java.nio.file.Path sbtPath) {
        if (sbtPath == null) {
            logger.warn("Cannot switch to null SBT path");
            return;
        }
        logger.info("Switching to SBT mode: {}", sbtPath);
        if (currentType == ContentType.BLOCK_MODEL) {
            modelContentLoader.unload();
        }
        renderingState.setSBTMode(sbtPath);
        transformState.resetPosition();
        currentType = ContentType.SBT_TEXTURE;
    }

    public void switchToItem(ItemType itemType) {
        if (itemType == null) {
            logger.warn("Cannot switch to null item type");
            return;
        }

        logger.info("Switching to Item mode: {}", itemType);

        // Unload model if active
        if (currentType == ContentType.BLOCK_MODEL) {
            modelContentLoader.unload();
        }

        // Update state
        renderingState.setItemMode(itemType);
        transformState.resetPosition();
        currentType = ContentType.ITEM;

        logger.info("Switched to Item mode: {}", itemType);
    }

    /**
     * Set texture variant for current content.
     *
     * <p>This is a pass-through to the rendering state for convenience.
     *
     * @param variant The texture variant name
     */
    public void setTextureVariant(String variant) {
        renderingState.setCurrentTextureVariant(variant);
        logger.debug("Set texture variant: {}", variant);
    }

    /**
     * Unload all content and return to empty state.
     */
    public void unloadAll() {
        logger.info("Unloading all content");

        if (currentType == ContentType.BLOCK_MODEL) {
            modelContentLoader.unload();
        }

        currentType = ContentType.NONE;
        transformState.resetPosition();
    }

    /**
     * Get the current content type being displayed.
     *
     * @return Current ContentType
     */
    public ContentType getCurrentType() {
        return currentType;
    }

    /**
     * Get the ModelContentLoader for direct access if needed.
     *
     * @return ModelContentLoader instance
     */
    public ModelContentLoader getModelContentLoader() {
        return modelContentLoader;
    }
}
