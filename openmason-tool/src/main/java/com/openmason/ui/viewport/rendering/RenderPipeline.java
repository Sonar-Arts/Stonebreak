package com.openmason.ui.viewport.rendering;

import com.openmason.rendering.BlockRenderer;
import com.openmason.rendering.ItemRenderer;
import com.openmason.rendering.ModelRenderer;
import com.openmason.rendering.TextureAtlas;
import com.openmason.ui.viewport.resources.ViewportResourceManager;
import com.openmason.ui.viewport.shaders.ShaderManager;
import com.openmason.ui.viewport.shaders.ShaderProgram;
import com.openmason.ui.viewport.shaders.ShaderType;
import com.openmason.ui.viewport.state.RenderingMode;
import com.openmason.ui.viewport.state.RenderingState;
import com.openmason.ui.viewport.state.TransformState;
import com.openmason.ui.viewport.state.ViewportState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL11.*;

/**
 * Orchestrates the main rendering pipeline for the viewport.
 * Coordinates all render passes: grid → content → gizmo.
 * Follows Single Responsibility Principle - only handles render coordination.
 */
public class RenderPipeline {

    private static final Logger logger = LoggerFactory.getLogger(RenderPipeline.class);

    private final RenderContext context;
    private final ViewportResourceManager resources;
    private final ShaderManager shaderManager;

    // Specialized renderers
    private final GridRenderer gridRenderer;
    private final TestCubeRenderer testCubeRenderer;

    // External renderers (models, blocks, items)
    private final ModelRenderer modelRenderer;
    private final BlockRenderer blockRenderer;
    private final ItemRenderer itemRenderer;
    private final TextureAtlas textureAtlas;

    // Diagnostic throttling
    private long lastDiagnosticLogTime = 0;
    private static final long DIAGNOSTIC_LOG_INTERVAL_MS = 2000;

    /**
     * Create render pipeline with all required dependencies.
     */
    public RenderPipeline(RenderContext context, ViewportResourceManager resources, ShaderManager shaderManager,
                         ModelRenderer modelRenderer, BlockRenderer blockRenderer, ItemRenderer itemRenderer,
                         TextureAtlas textureAtlas) {
        this.context = context;
        this.resources = resources;
        this.shaderManager = shaderManager;
        this.gridRenderer = new GridRenderer();
        this.testCubeRenderer = new TestCubeRenderer();
        this.modelRenderer = modelRenderer;
        this.blockRenderer = blockRenderer;
        this.itemRenderer = itemRenderer;
        this.textureAtlas = textureAtlas;
    }

    /**
     * Execute complete render pipeline.
     */
    public void render(ViewportState viewportState, RenderingState renderingState, TransformState transformState) {
        try {
            // Update camera animation
            context.getCamera().update(0.016f); // Assuming ~60fps (16ms frame time)
            context.getCamera().updateMatrices(); // Force matrix update

            // Update render context
            context.update(viewportState.getWidth(), viewportState.getHeight(), viewportState.isWireframeMode());

            // Bind framebuffer
            resources.getFramebuffer().bind();

            // Clear background
            glClearColor(0.2f, 0.2f, 0.3f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            // Enable depth testing
            glEnable(GL_DEPTH_TEST);

            // Apply wireframe mode
            if (viewportState.isWireframeMode()) {
                glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
            } else {
                glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
            }

            // PASS 1: Render grid (if enabled)
            if (viewportState.isShowGrid()) {
                renderGrid();
            }

            // PASS 2: Render content (model/block/item)
            renderContent(renderingState, transformState, viewportState.isWireframeMode());

            // Restore polygon mode after content rendering
            if (viewportState.isWireframeMode()) {
                glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
            }

            // Unbind framebuffer
            resources.getFramebuffer().unbind();

        } catch (Exception e) {
            logger.error("Error in render pipeline", e);
        }
    }

    /**
     * Render grid pass.
     */
    private void renderGrid() {
        try {
            ShaderProgram basicShader = shaderManager.getShaderProgram(ShaderType.BASIC);
            gridRenderer.render(resources.getGrid(), basicShader, context);
        } catch (Exception e) {
            logger.error("Error rendering grid", e);
        }
    }

    /**
     * Render content pass (model, block, item, or test cube).
     */
    private void renderContent(RenderingState renderingState, TransformState transformState, boolean wireframeMode) {
        // Diagnostic logging (throttled)
        boolean shouldLog = shouldLogDiagnostics();

        if (shouldLog) {
            logger.trace("Render content - mode: {}, state: {}", renderingState.getMode(), renderingState.getStateDescription());
        }

        // Render based on mode
        if (renderingState.isBlockReady()) {
            renderBlock(renderingState, transformState);
        } else if (renderingState.isItemReady()) {
            renderItem(renderingState, transformState);
        } else if (renderingState.isModelReady()) {
            if (prepareModelIfNeeded(renderingState)) {
                renderModel(renderingState, transformState, wireframeMode);
            } else {
                // Model not ready, use fallback
                renderTestCube(transformState);
            }
        } else {
            // No content to render, show test cube
            renderTestCube(transformState);
        }
    }

    /**
     * Prepare model for rendering if not already prepared.
     */
    private boolean prepareModelIfNeeded(RenderingState renderingState) {
        if (modelRenderer.isModelPrepared(renderingState.getCurrentModel())) {
            return true;
        }

        try {
            logger.debug("Preparing model for rendering: {}", renderingState.getCurrentModelName());
            boolean prepared = modelRenderer.prepareModel(renderingState.getCurrentModel());
            if (!prepared) {
                logger.error("Failed to prepare model: {}", renderingState.getCurrentModelName());
                modelRenderer.logDiagnosticInfo();
            }
            return prepared;
        } catch (Exception e) {
            logger.error("Exception preparing model: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Render model content.
     */
    private void renderModel(RenderingState renderingState, TransformState transformState, boolean wireframeMode) {
        try {
            ShaderProgram matrixShader = shaderManager.getShaderProgram(ShaderType.MATRIX);
            float[] vpArray = context.getViewProjectionArray();

            // In wireframe mode, pass null textureAtlas to disable textures
            TextureAtlas atlas = wireframeMode ? null : textureAtlas;

            modelRenderer.renderModel(
                renderingState.getCurrentModel(),
                renderingState.getCurrentTextureVariant(),
                matrixShader.getProgramId(),
                matrixShader.getMvpMatrixLocation(),
                matrixShader.getModelMatrixLocation(),
                vpArray,
                transformState.getTransformMatrix(),
                atlas,
                matrixShader.getTextureLocation(),
                matrixShader.getUseTextureLocation(),
                matrixShader.getColorLocation()
            );

            logger.trace("Model rendered successfully");

        } catch (Exception e) {
            logger.error("Error rendering model", e);
            renderTestCube(transformState); // Fallback
        }
    }

    /**
     * Render block content.
     */
    private void renderBlock(RenderingState renderingState, TransformState transformState) {
        try {
            ShaderProgram matrixShader = shaderManager.getShaderProgram(ShaderType.MATRIX);
            float[] vpArray = context.getViewProjectionArray();

            blockRenderer.renderBlock(
                renderingState.getSelectedBlock(),
                matrixShader.getProgramId(),
                matrixShader.getMvpMatrixLocation(),
                matrixShader.getModelMatrixLocation(),
                vpArray,
                transformState.getTransformMatrix(),
                matrixShader.getTextureLocation(),
                matrixShader.getUseTextureLocation()
            );

            logger.trace("Block rendered: {}", renderingState.getSelectedBlock().name());

        } catch (Exception e) {
            logger.error("Error rendering block", e);
            renderTestCube(transformState); // Fallback
        }
    }

    /**
     * Render item content.
     */
    private void renderItem(RenderingState renderingState, TransformState transformState) {
        try {
            ShaderProgram matrixShader = shaderManager.getShaderProgram(ShaderType.MATRIX);
            float[] vpArray = context.getViewProjectionArray();

            itemRenderer.renderItem(
                renderingState.getSelectedItem(),
                matrixShader.getProgramId(),
                matrixShader.getMvpMatrixLocation(),
                matrixShader.getModelMatrixLocation(),
                vpArray,
                transformState.getTransformMatrix(),
                matrixShader.getTextureLocation(),
                matrixShader.getUseTextureLocation()
            );

            logger.trace("Item rendered: {}", renderingState.getSelectedItem().name());

        } catch (Exception e) {
            logger.error("Error rendering item", e);
            renderTestCube(transformState); // Fallback
        }
    }

    /**
     * Render test cube (fallback).
     */
    private void renderTestCube(TransformState transformState) {
        try {
            ShaderProgram basicShader = shaderManager.getShaderProgram(ShaderType.BASIC);
            testCubeRenderer.render(resources.getTestCube(), basicShader, context, transformState);
            logger.trace("Test cube rendered (fallback)");
        } catch (Exception e) {
            logger.error("Error rendering test cube", e);
        }
    }

    /**
     * Check if diagnostic logging should occur (throttled).
     */
    private boolean shouldLogDiagnostics() {
        long currentTime = System.currentTimeMillis();
        if ((currentTime - lastDiagnosticLogTime) >= DIAGNOSTIC_LOG_INTERVAL_MS) {
            lastDiagnosticLogTime = currentTime;
            return true;
        }
        return false;
    }
}
