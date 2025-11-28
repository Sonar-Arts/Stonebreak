package com.openmason.rendering.core;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager;
import com.stonebreak.rendering.core.API.commonBlockResources.models.BlockDefinition;
import com.openmason.block.BlockManager;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;

/**
 * Simple block renderer for Open Mason following KISS principles.
 */
public class BlockRenderer implements AutoCloseable {

    private boolean initialized = false;
    private final String debugPrefix;
    private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);

    // Statistics

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

            // Handle transparency based on render layer
            BlockDefinition.RenderLayer renderLayer = resource.getDefinition().getRenderLayer();
            boolean needsBlending = false;
            boolean disabledCulling = false;

            switch (renderLayer) {
                case CUTOUT:
                    // Alpha testing for sharp edges (leaves, flowers)
                    // Modern OpenGL: shader handles discard based on alpha < 0.5
                    // Enable blending for smooth edges
                    glEnable(GL_BLEND);
                    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

                    // IMPORTANT: Disable depth writing for cutout blocks
                    // This prevents transparent pixels from blocking other faces (critical for cross-models like flowers)
                    // Depth testing is still enabled, so solid pixels still occlude properly
                    glDepthMask(false);

                    // Disable face culling for cutout blocks (leaves, flowers need both sides)
                    glDisable(GL_CULL_FACE);
                    disabledCulling = true;
                    needsBlending = true;
                    break;

                case TRANSLUCENT:
                    // Full alpha blending for water, glass, ice, etc.
                    glEnable(GL_BLEND);
                    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                    glDepthMask(false); // Don't write to depth buffer for translucent objects

                    // Disable face culling for translucent blocks (water, glass visible from both sides)
                    glDisable(GL_CULL_FACE);
                    disabledCulling = true;
                    needsBlending = true;
                    break;

                case OPAQUE:
                default:
                    // No transparency needed - solid blocks
                    glDisable(GL_BLEND);
                    glDepthMask(true);

                    // Keep face culling enabled for opaque blocks (performance optimization)
                    glEnable(GL_CULL_FACE);
                    glCullFace(GL_BACK);
                    break;
            }

            // Bind the mesh and render
            resource.getMesh().bindAndDraw();

            // Restore OpenGL state after rendering
            if (needsBlending) {
                glDisable(GL_BLEND);
            }
            if (renderLayer == BlockDefinition.RenderLayer.TRANSLUCENT || renderLayer == BlockDefinition.RenderLayer.CUTOUT) {
                glDepthMask(true); // Restore depth writing for both translucent and cutout
            }
            if (disabledCulling) {
                glEnable(GL_CULL_FACE); // Restore face culling
            }

        } catch (Exception e) {
            System.err.println("[" + debugPrefix + "] Error rendering block resource: " + e.getMessage());
            e.printStackTrace();
        }
    }


    /**
     * Checks if the renderer is initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Cleanup resources.
     */
    @Override
    public void close() {
        if (initialized) {
            System.out.println("[" + debugPrefix + "] Shutting down BlockRenderer");
            initialized = false;
        }
    }
}
