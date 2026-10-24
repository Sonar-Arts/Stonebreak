package com.openmason.main.systems.rendering.model.block;

import com.openmason.engine.rendering.blockdisplay.BlockDisplayMeshes;
import com.openmason.engine.rendering.blockdisplay.DisplayMesh;
import com.openmason.engine.voxel.RenderLayer;
import com.stonebreak.blocks.BlockType;

/**
 * Display geometry for the viewport's Stonebreak block preview: a cube, or
 * crossed planes for flowers, drawn untextured in the block's render layer.
 */
public final class BlockManager {

    private static volatile BlockManager instance;

    private final BlockDisplayMeshes displayMeshes;

    private BlockManager() {
        this.displayMeshes = new BlockDisplayMeshes();
    }

    /**
     * Builds the preview meshes once. Needs a current GL context.
     */
    public static synchronized void initialize() {
        if (instance == null) {
            instance = new BlockManager();
        }
    }

    public static boolean isInitialized() {
        return instance != null;
    }

    /**
     * @throws IllegalStateException if {@link #initialize()} has not run
     */
    public static BlockManager getInstance() {
        BlockManager current = instance;
        if (current == null) {
            throw new IllegalStateException("BlockManager not initialized. Call initialize() first.");
        }
        return current;
    }

    /** Releases the preview meshes. Call on the GL thread. */
    public static synchronized void shutdown() {
        if (instance != null) {
            instance.displayMeshes.close();
            instance = null;
        }
    }

    /** The shared preview mesh for a block; owned by the manager, do not close it. */
    public DisplayMesh getMesh(BlockType blockType) {
        return displayMeshes.getShape(blockType.isFlower()
                ? BlockDisplayMeshes.Shape.CROSS
                : BlockDisplayMeshes.Shape.CUBE);
    }

    /**
     * The block's render layer from its SBO. Crossed planes are never drawn
     * opaque (their empty texels would show), so a flower is at least CUTOUT.
     */
    public static RenderLayer getRenderLayer(BlockType blockType) {
        RenderLayer layer = blockType.getRenderLayer();
        if (layer == RenderLayer.OPAQUE && blockType.isFlower()) {
            return RenderLayer.CUTOUT;
        }
        return layer;
    }
}
