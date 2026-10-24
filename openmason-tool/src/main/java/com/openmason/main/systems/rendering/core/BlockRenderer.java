package com.openmason.main.systems.rendering.core;

import com.openmason.engine.rendering.blockdisplay.DisplayMesh;
import com.openmason.engine.voxel.RenderLayer;
import com.stonebreak.blocks.BlockType;
import com.openmason.main.systems.rendering.model.block.BlockManager;
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
            DisplayMesh mesh = BlockManager.getInstance().getMesh(blockType);
            renderMesh(mesh, BlockManager.getRenderLayer(blockType), shaderProgram, mvpLocation, modelLocation,
                       vpMatrix, modelMatrix, textureLocation, useTextureLocation);

        } catch (Exception e) {
            System.err.println("[" + debugPrefix + "] Error rendering block " + blockType + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Draws a block preview mesh in the given render layer.
     *
     * @param mesh The block's display mesh
     * @param renderLayer Selects blending, depth writes and culling
     * @param shaderProgram The shader program to use
     * @param mvpLocation Location of MVP matrix uniform
     * @param modelLocation Location of model matrix uniform
     * @param vpMatrix View-projection matrix from camera
     * @param modelMatrix Model transformation matrix
     * @param textureLocation Location of texture sampler uniform
     * @param useTextureLocation Location of useTexture flag uniform
     */
    public void renderMesh(DisplayMesh mesh, RenderLayer renderLayer,
                           int shaderProgram,
                           int mvpLocation, int modelLocation,
                           float[] vpMatrix, Matrix4f modelMatrix,
                           int textureLocation, int useTextureLocation) {
        if (!initialized) {
            throw new IllegalStateException("BlockRenderer not initialized");
        }

        if (mesh == null) {
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

            // Texturing disabled — the Open Mason block preview renders
            // untextured pending a texture-array migration of this renderer.
            if (useTextureLocation != -1) {
                glUniform1i(useTextureLocation, 0);
            }

            // Handle transparency based on render layer
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
            mesh.bindAndDraw();

            // Restore OpenGL state after rendering
            if (needsBlending) {
                glDisable(GL_BLEND);
            }
            if (renderLayer == RenderLayer.TRANSLUCENT || renderLayer == RenderLayer.CUTOUT) {
                glDepthMask(true); // Restore depth writing for both translucent and cutout
            }
            if (disabledCulling) {
                glEnable(GL_CULL_FACE); // Restore face culling
            }

        } catch (Exception e) {
            System.err.println("[" + debugPrefix + "] Error rendering block mesh: " + e.getMessage());
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
