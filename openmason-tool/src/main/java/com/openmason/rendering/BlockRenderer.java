package com.openmason.rendering;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager;
import com.openmason.block.BlockManager;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL20.*;

/**
 * Simple block renderer for Open Mason following KISS principles.
 * Leverages CBR API's built-in rendering capabilities instead of recreating
 * buffer management logic.
 *
 * Design Principles:
 * - KISS: Simple delegation to CBR API
 * - YAGNI: Only implements basic rendering
 * - DRY: Reuses CBR rendering, no duplicate code
 */
public class BlockRenderer implements AutoCloseable {

    private boolean initialized = false;
    private final String debugPrefix;
    private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);

    // Statistics
    private long totalRenderCalls = 0;
    private BlockType currentBlock = null;

    /**
     * Creates a new BlockRenderer.
     *
     * @param debugPrefix Prefix for debug logging
     */
    public BlockRenderer(String debugPrefix) {
        this.debugPrefix = debugPrefix != null ? debugPrefix : "BlockRenderer";
    }

    /**
     * Initialize the renderer. Must be called before rendering.
     */
    public void initialize() {
        if (initialized) {
            return;
        }

        if (!BlockManager.isInitialized()) {
            BlockManager.initialize();
        }

        initialized = true;
        System.out.println("[" + debugPrefix + "] BlockRenderer initialized");
    }

    /**
     * Renders a block using its BlockType.
     * This is the main rendering method.
     *
     * @param blockType The block type to render
     * @param shaderProgram The shader program to use
     * @param mvpLocation Location of MVP matrix uniform
     * @param modelLocation Location of model matrix uniform
     * @param vpMatrix View-projection matrix from camera
     * @param modelMatrix Model transformation matrix
     * @param textureLocation Location of texture sampler uniform
     * @param useTextureLocation Location of useTexture flag uniform
     */
    public void renderBlock(BlockType blockType, int shaderProgram,
                           int mvpLocation, int modelLocation,
                           float[] vpMatrix, Matrix4f modelMatrix,
                           int textureLocation, int useTextureLocation) {
        if (!initialized) {
            throw new IllegalStateException("BlockRenderer not initialized");
        }

        if (blockType == null) {
            System.err.println("[" + debugPrefix + "] Cannot render null block type");
            return;
        }

        try {
            // Get block resource from BlockManager
            BlockManager manager = BlockManager.getInstance();
            CBRResourceManager.BlockRenderResource resource = manager.getBlockResource(blockType);

            if (resource == null) {
                System.err.println("[" + debugPrefix + "] No resource found for block: " + blockType);
                return;
            }

            // Get the texture atlas from BlockManager
            com.stonebreak.rendering.textures.TextureAtlas blockAtlas = manager.getTextureAtlas();

            // Render the block resource with proper shader setup
            renderBlockResource(resource, shaderProgram, mvpLocation, modelLocation,
                              vpMatrix, modelMatrix, blockAtlas, textureLocation, useTextureLocation);

            currentBlock = blockType;
            totalRenderCalls++;

        } catch (Exception e) {
            System.err.println("[" + debugPrefix + "] Error rendering block " + blockType + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Renders a block using a CBR BlockRenderResource directly.
     *
     * @param resource The block render resource
     * @param shaderProgram The shader program to use
     * @param mvpLocation Location of MVP matrix uniform
     * @param modelLocation Location of model matrix uniform
     * @param vpMatrix View-projection matrix from camera
     * @param modelMatrix Model transformation matrix
     * @param textureAtlas The texture atlas to bind
     * @param textureLocation Location of texture sampler uniform
     * @param useTextureLocation Location of useTexture flag uniform
     */
    public void renderBlockResource(CBRResourceManager.BlockRenderResource resource,
                                    int shaderProgram,
                                    int mvpLocation, int modelLocation,
                                    float[] vpMatrix, Matrix4f modelMatrix,
                                    com.stonebreak.rendering.textures.TextureAtlas textureAtlas,
                                    int textureLocation, int useTextureLocation) {
        if (!initialized) {
            throw new IllegalStateException("BlockRenderer not initialized");
        }

        if (resource == null) {
            return;
        }

        try {
            // Use the shader program
            glUseProgram(shaderProgram);

            // Set view-projection matrix uniform
            if (mvpLocation != -1 && vpMatrix != null) {
                glUniformMatrix4fv(mvpLocation, false, vpMatrix);
            }

            // Set model transformation matrix uniform
            if (modelLocation != -1 && modelMatrix != null) {
                modelMatrix.get(matrixBuffer);
                glUniformMatrix4fv(modelLocation, false, matrixBuffer);
            }

            // Bind texture atlas and set texture uniforms
            if (textureAtlas != null) {
                // Bind the texture atlas
                textureAtlas.bind();

                // Set texture sampler uniform (texture unit 0)
                if (textureLocation != -1) {
                    glUniform1i(textureLocation, 0);
                }

                // Enable texturing
                if (useTextureLocation != -1) {
                    glUniform1i(useTextureLocation, 1);
                }
            } else {
                // Disable texturing if no atlas provided
                if (useTextureLocation != -1) {
                    glUniform1i(useTextureLocation, 0);
                }
            }

            // Bind the mesh and render
            resource.getMesh().bindAndDraw();

            totalRenderCalls++;

        } catch (Exception e) {
            System.err.println("[" + debugPrefix + "] Error rendering block resource: " + e.getMessage());
            e.printStackTrace();
        }
    }


    /**
     * Gets the currently rendered block type.
     *
     * @return The current block type or null
     */
    public BlockType getCurrentBlock() {
        return currentBlock;
    }

    /**
     * Gets the total number of render calls.
     *
     * @return Number of render calls
     */
    public long getTotalRenderCalls() {
        return totalRenderCalls;
    }

    /**
     * Resets statistics.
     */
    public void resetStatistics() {
        totalRenderCalls = 0;
    }

    /**
     * Checks if the renderer is initialized.
     *
     * @return true if initialized
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Gets rendering statistics as a string.
     *
     * @return Statistics string
     */
    public String getStatistics() {
        return String.format("[%s] Stats: %d render calls, current block: %s",
            debugPrefix, totalRenderCalls,
            currentBlock != null ? currentBlock.name() : "none");
    }

    /**
     * Validates that a block can be rendered.
     *
     * @param blockType The block type to validate
     * @return true if the block can be rendered
     */
    public boolean canRender(BlockType blockType) {
        if (!initialized || blockType == null) {
            return false;
        }

        try {
            BlockManager manager = BlockManager.getInstance();
            return manager.validateBlock(blockType);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Cleanup resources.
     */
    @Override
    public void close() {
        if (initialized) {
            System.out.println("[" + debugPrefix + "] Shutting down BlockRenderer");
            initialized = false;
            currentBlock = null;
        }
    }

    /**
     * Static utility to create and initialize a BlockRenderer.
     *
     * @param debugPrefix Debug prefix
     * @return Initialized BlockRenderer
     */
    public static BlockRenderer createAndInitialize(String debugPrefix) {
        BlockRenderer renderer = new BlockRenderer(debugPrefix);
        renderer.initialize();
        return renderer;
    }
}
