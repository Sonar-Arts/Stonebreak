package com.openmason.main.systems.viewport.content;

import com.openmason.main.systems.rendering.model.editable.BlockModel;
import com.openmason.main.systems.viewport.state.RenderingState;
import com.openmason.main.systems.viewport.state.TransformState;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages content type switching in the viewport (blocks, items, BlockModels).
 *
 * <p>This class follows the Single Responsibility Principle by focusing solely on
 * coordinating content type transitions and state updates. It acts as a facade
 * for content switching operations.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Switch to BlockModel mode</li>
 *   <li>Switch to Block mode</li>
 *   <li>Switch to Item mode</li>
 *   <li>Coordinate state updates across transitions</li>
 *   <li>Manage BlockModel lifecycle via BlockModelLoader</li>
 * </ul>
 *
 * <h2>SOLID Principles</h2>
 * <ul>
 *   <li><b>Single Responsibility</b>: Only manages content type switching</li>
 *   <li><b>Open/Closed</b>: Extensible through new content types</li>
 *   <li><b>Liskov Substitution</b>: Content types are interchangeable</li>
 *   <li><b>Dependency Inversion</b>: Depends on abstractions (state interfaces)</li>
 * </ul>
 *
 * @see BlockModelLoader
 * @see RenderingState
 * @see TransformState
 */
public class ContentTypeManager {

    private static final Logger logger = LoggerFactory.getLogger(ContentTypeManager.class);

    // Dependencies (injected)
    private final BlockModelLoader blockModelLoader;
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
        ITEM
    }

    /**
     * Create a new ContentTypeManager.
     *
     * @param blockModelLoader The loader for BlockModel content
     * @param renderingState The rendering state to update
     * @param transformState The transform state to reset
     */
    public ContentTypeManager(BlockModelLoader blockModelLoader,
                              RenderingState renderingState,
                              TransformState transformState) {
        if (blockModelLoader == null || renderingState == null || transformState == null) {
            throw new IllegalArgumentException("Dependencies cannot be null");
        }
        this.blockModelLoader = blockModelLoader;
        this.renderingState = renderingState;
        this.transformState = transformState;
    }

    /**
     * Switch to BlockModel mode and load the specified model.
     *
     * <p>This performs:
     * <ol>
     *   <li>Load BlockModel via BlockModelLoader</li>
     *   <li>Update rendering state to BlockModel mode</li>
     *   <li>Reset transform to default position</li>
     * </ol>
     *
     * @param blockModel The BlockModel to load and display
     * @return LoadResult from BlockModelLoader
     */
    public BlockModelLoader.LoadResult switchToBlockModel(BlockModel blockModel) {
        if (blockModel == null) {
            logger.error("Cannot switch to null BlockModel");
            return BlockModelLoader.LoadResult.failure("BlockModel is null");
        }

        logger.info("Switching to BlockModel mode: {}", blockModel.getName());

        // Load BlockModel
        BlockModelLoader.LoadResult result = blockModelLoader.load(blockModel);
        if (!result.isSuccess()) {
            logger.error("Failed to load BlockModel: {}", result.getMessage());
            return result;
        }

        // Update state
        renderingState.setBlockModelMode(blockModel.getName());
        transformState.resetPosition();
        currentType = ContentType.BLOCK_MODEL;

        logger.info("Switched to BlockModel mode successfully: {}", blockModel.getName());
        return result;
    }

    /**
     * Update texture for current BlockModel without rebuilding geometry.
     *
     * <p>Use this to preserve vertex/geometry modifications while changing textures.
     *
     * @param blockModel The BlockModel with updated texture path
     * @return LoadResult from BlockModelLoader
     */
    public BlockModelLoader.LoadResult updateBlockModelTexture(BlockModel blockModel) {
        if (blockModel == null) {
            logger.warn("Cannot update texture for null BlockModel");
            return BlockModelLoader.LoadResult.failure("BlockModel is null");
        }

        if (currentType != ContentType.BLOCK_MODEL) {
            logger.warn("Not in BlockModel mode, cannot update texture");
            return BlockModelLoader.LoadResult.failure("Not in BlockModel mode");
        }

        logger.info("Updating BlockModel texture: {}", blockModel.getName());
        return blockModelLoader.updateTexture(blockModel);
    }

    /**
     * Switch to Block mode and display the specified block type.
     *
     * <p>This performs:
     * <ol>
     *   <li>Unload any BlockModel content</li>
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

        // Unload BlockModel if active
        if (currentType == ContentType.BLOCK_MODEL) {
            blockModelLoader.unload();
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
     *   <li>Unload any BlockModel content</li>
     *   <li>Update rendering state to Item mode</li>
     *   <li>Reset transform to default position</li>
     * </ol>
     *
     * @param itemType The item type to display
     */
    public void switchToItem(ItemType itemType) {
        if (itemType == null) {
            logger.warn("Cannot switch to null item type");
            return;
        }

        logger.info("Switching to Item mode: {}", itemType);

        // Unload BlockModel if active
        if (currentType == ContentType.BLOCK_MODEL) {
            blockModelLoader.unload();
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
            blockModelLoader.unload();
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
     * Get the BlockModelLoader for direct access if needed.
     *
     * @return BlockModelLoader instance
     */
    public BlockModelLoader getBlockModelLoader() {
        return blockModelLoader;
    }
}
