package com.openmason.ui.viewport.rendering;

import com.openmason.deprecated.LegacyCowTextureAtlas;
import com.openmason.rendering.core.BlockRenderer;
import com.openmason.rendering.core.ItemRenderer;
import com.openmason.deprecated.LegacyCowModelRenderer;
import com.openmason.ui.viewport.gizmo.GizmoRenderer;
import com.openmason.ui.viewport.resources.ViewportResourceManager;
import com.openmason.ui.viewport.shaders.ShaderManager;
import com.openmason.ui.viewport.shaders.ShaderProgram;
import com.openmason.ui.viewport.shaders.ShaderType;
import com.openmason.ui.viewport.state.RenderingState;
import com.openmason.ui.viewport.state.TransformState;
import com.openmason.ui.viewport.state.ViewportState;
import com.stonebreak.model.ModelDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

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
    private final InfiniteGridRenderer infiniteGridRenderer;
    private final VertexRenderer vertexRenderer;
    private final EdgeRenderer edgeRenderer;

    // External renderers (models, blocks, items)
    private final LegacyCowModelRenderer legacyCowModelRenderer;
    private final BlockRenderer blockRenderer;
    private final ItemRenderer itemRenderer;
    private final LegacyCowTextureAtlas legacyCowTextureAtlas;

    // BlockModel renderer (.OMO editable models)
    private final com.openmason.rendering.blockmodel.BlockModelRenderer blockModelRenderer;

    // Gizmo renderer
    private final GizmoRenderer gizmoRenderer;

    // Diagnostic throttling
    private long lastDiagnosticLogTime = 0;
    private static final long DIAGNOSTIC_LOG_INTERVAL_MS = 2000;

    /**
     * Create render pipeline with all required dependencies.
     */
    public RenderPipeline(RenderContext context, ViewportResourceManager resources, ShaderManager shaderManager,
                          LegacyCowModelRenderer legacyCowModelRenderer, BlockRenderer blockRenderer, ItemRenderer itemRenderer,
                          LegacyCowTextureAtlas legacyCowTextureAtlas, com.openmason.rendering.blockmodel.BlockModelRenderer blockModelRenderer,
                          GizmoRenderer gizmoRenderer) {
        this.context = context;
        this.resources = resources;
        this.shaderManager = shaderManager;
        this.infiniteGridRenderer = new InfiniteGridRenderer();
        this.vertexRenderer = new VertexRenderer();
        this.edgeRenderer = new EdgeRenderer();
        this.legacyCowModelRenderer = legacyCowModelRenderer;
        this.blockRenderer = blockRenderer;
        this.itemRenderer = itemRenderer;
        this.legacyCowTextureAtlas = legacyCowTextureAtlas;
        this.blockModelRenderer = blockModelRenderer;
        this.gizmoRenderer = gizmoRenderer;
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

            // Configure pipeline-level OpenGL state (set once per frame)
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LESS);
            glDepthMask(true);
            glDisable(GL_CULL_FACE);  // Show all cube faces
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

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

            // PASS 3: Render mesh (vertices + edges, debug overlay, Blender-style)
            if (viewportState.isShowVertices()) {
                renderVertices(renderingState, transformState);
                renderEdges(renderingState, transformState);
            }

            // PASS 4: Render gizmo (after content, always in fill mode)
            if (gizmoRenderer != null && gizmoRenderer.isInitialized()) {
                renderGizmo(viewportState);
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
            // Initialize infinite grid renderer if needed
            if (!infiniteGridRenderer.isInitialized()) {
                infiniteGridRenderer.initialize();
            }

            // Use infinite grid shader
            ShaderProgram infiniteGridShader = shaderManager.getShaderProgram(ShaderType.INFINITE_GRID);
            infiniteGridRenderer.render(infiniteGridShader, context);
        } catch (Exception e) {
            logger.error("Error rendering infinite grid", e);
        }
    }

    /**
     * Render content pass (model, block, item, or block model).
     */
    private void renderContent(RenderingState renderingState, TransformState transformState, boolean wireframeMode) {
        // Diagnostic logging (throttled)
        boolean shouldLog = shouldLogDiagnostics();

        if (shouldLog) {
            logger.trace("Render content - mode: {}, state: {}", renderingState.getMode(), renderingState.getStateDescription());
        }

        // Render based on mode
        if (renderingState.getMode() == com.openmason.ui.viewport.state.RenderingMode.BLOCK_MODEL) {
            // Render editable block model (.OMO)
            renderBlockModel(transformState);
        } else if (renderingState.isBlockReady()) {
            renderBlock(renderingState, transformState);
        } else if (renderingState.isItemReady()) {
            renderItem(renderingState, transformState);
        } else if (renderingState.isModelReady()) {
            if (prepareModelIfNeeded(renderingState)) {
                renderModel(renderingState, transformState, wireframeMode);
            } else {
                // Model not ready, skip rendering
                logger.trace("Model not prepared, skipping render");
            }
        } else {
            // No content to render (default blank model should load on startup)
            logger.trace("No content ready for rendering");
        }
    }

    /**
     * Prepare model for rendering if not already prepared.
     */
    private boolean prepareModelIfNeeded(RenderingState renderingState) {
        if (legacyCowModelRenderer.isModelPrepared(renderingState.getCurrentModel())) {
            return true;
        }

        try {
            logger.debug("Preparing model for rendering: {}", renderingState.getCurrentModelName());
            boolean prepared = legacyCowModelRenderer.prepareModel(renderingState.getCurrentModel());
            if (!prepared) {
                logger.error("Failed to prepare model: {}", renderingState.getCurrentModelName());
                legacyCowModelRenderer.logDiagnosticInfo();
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
            LegacyCowTextureAtlas atlas = wireframeMode ? null : legacyCowTextureAtlas;

            legacyCowModelRenderer.renderModel(
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
        }
    }

    /**
     * Render editable block model (.OMO file).
     * Minimal implementation - pipeline state is configured once per frame.
     * Shader handles transparency via discard statement.
     */
    private void renderBlockModel(TransformState transformState) {
        try {
            if (!blockModelRenderer.isInitialized()) {
                logger.warn("BlockModelRenderer not initialized");
                return;
            }

            // Get shader and bind it
            ShaderProgram matrixShader = shaderManager.getShaderProgram(ShaderType.MATRIX);
            matrixShader.use();

            // Compute view-projection matrix
            org.joml.Matrix4f viewProjectionMatrix = new org.joml.Matrix4f(context.getCamera().getProjectionMatrix());
            viewProjectionMatrix.mul(context.getCamera().getViewMatrix());

            // Set up MVP matrix
            matrixShader.setMat4("uMVPMatrix", viewProjectionMatrix);

            // Apply gizmo transform if enabled
            org.joml.Matrix4f modelMatrix = new org.joml.Matrix4f();
            if (transformState.isGizmoEnabled() && transformState.getGizmoPosition() != null) {
                org.joml.Vector3f gizmoPos = transformState.getGizmoPosition();
                modelMatrix.translate(gizmoPos);
            }

            matrixShader.setMat4("uModelMatrix", modelMatrix);

            // Enable texturing
            matrixShader.setInt("uTexture", 0);
            matrixShader.setBool("uUseTexture", true);

            // Render (no state management needed - pipeline handles it)
            blockModelRenderer.render();

        } catch (Exception e) {
            logger.error("Error rendering BlockModel", e);
        }
    }

    /**
     * Render gizmo pass.
     */
    private void renderGizmo(ViewportState viewportState) {
        try {
            gizmoRenderer.render(
                context.getCamera().getViewMatrix(),
                context.getCamera().getProjectionMatrix(),
                viewportState.getWidth(),
                viewportState.getHeight()
            );
        } catch (Exception e) {
            logger.error("Error rendering gizmo", e);
        }
    }

    /**
     * Render vertices pass (Blender-style vertex visualization).
     * Note: Edge rendering is handled separately in renderEdges().
     */
    private void renderVertices(RenderingState renderingState, TransformState transformState) {
        try {
            // Initialize vertex renderer if needed
            if (!vertexRenderer.isInitialized()) {
                vertexRenderer.initialize();
            }

            // Render vertices based on current rendering mode
            switch (renderingState.getMode()) {
                case MODEL:
                    // Standard cow model rendering
                    if (renderingState.isModelReady()) {
                        // Extract all parts from cow model
                        Collection<ModelDefinition.ModelPart> parts = extractModelParts(
                            renderingState.getCurrentModel().getModelDefinition()
                        );

                        vertexRenderer.updateVertexData(parts, transformState.getTransformMatrix());

                        ShaderProgram basicShader = shaderManager.getShaderProgram(ShaderType.BASIC);
                        vertexRenderer.render(basicShader, context);
                    }
                    break;

                case BLOCK_MODEL:
                    // Editable .OMO block model rendering (simple cube)
                    Collection<ModelDefinition.ModelPart> cubeParts = createCubeParts();
                    vertexRenderer.updateVertexData(cubeParts, transformState.getTransformMatrix());

                    ShaderProgram basicShaderBlockModel = shaderManager.getShaderProgram(ShaderType.BASIC);
                    vertexRenderer.render(basicShaderBlockModel, context);
                    break;

                case BLOCK:
                    // Single block rendering
                    // TODO: Implement vertex extraction for Block rendering
                    logger.trace("BLOCK mode - vertices not yet supported");
                    break;

                case ITEM:
                    // Item rendering
                    // TODO: Implement vertex extraction for Item rendering
                    logger.trace("ITEM mode - vertices not yet supported");
                    break;
            }

        } catch (Exception e) {
            logger.error("Error rendering vertices", e);
        }
    }

    /**
     * Render edges pass (complements vertex rendering for mesh visualization).
     */
    private void renderEdges(RenderingState renderingState, TransformState transformState) {
        try {
            // Initialize edge renderer if needed
            if (!edgeRenderer.isInitialized()) {
                edgeRenderer.initialize();
            }

            // Render edges based on current rendering mode (mirrors vertex rendering)
            switch (renderingState.getMode()) {
                case MODEL:
                    // Standard cow model rendering
                    if (renderingState.isModelReady()) {
                        // Extract all parts from cow model
                        Collection<ModelDefinition.ModelPart> parts = extractModelParts(
                            renderingState.getCurrentModel().getModelDefinition()
                        );

                        edgeRenderer.updateEdgeData(parts, transformState.getTransformMatrix());

                        ShaderProgram basicShader = shaderManager.getShaderProgram(ShaderType.BASIC);
                        edgeRenderer.render(basicShader, context);
                    }
                    break;

                case BLOCK_MODEL:
                    // Editable .OMO block model rendering (simple cube)
                    Collection<ModelDefinition.ModelPart> cubeParts = createCubeParts();
                    edgeRenderer.updateEdgeData(cubeParts, transformState.getTransformMatrix());

                    ShaderProgram basicShaderBlockModel = shaderManager.getShaderProgram(ShaderType.BASIC);
                    edgeRenderer.render(basicShaderBlockModel, context);
                    break;

                case BLOCK:
                    // Single block rendering
                    // TODO: Implement edge extraction for Block rendering
                    logger.trace("BLOCK mode - edges not yet supported");
                    break;

                case ITEM:
                    // Item rendering
                    // TODO: Implement edge extraction for Item rendering
                    logger.trace("ITEM mode - edges not yet supported");
                    break;
            }

        } catch (Exception e) {
            logger.error("Error rendering edges", e);
        }
    }

    /**
     * Extract all model parts from a cow model definition.
     * Generic approach - caller handles model structure.
     */
    private Collection<ModelDefinition.ModelPart> extractModelParts(ModelDefinition.CowModelDefinition cowModel) {
        List<ModelDefinition.ModelPart> parts = new ArrayList<>();

        if (cowModel == null || cowModel.getParts() == null) {
            return parts;
        }

        ModelDefinition.ModelParts modelParts = cowModel.getParts();

        // Add all parts to collection
        if (modelParts.getBody() != null) parts.add(modelParts.getBody());
        if (modelParts.getHead() != null) parts.add(modelParts.getHead());
        if (modelParts.getUdder() != null) parts.add(modelParts.getUdder());
        if (modelParts.getTail() != null) parts.add(modelParts.getTail());

        if (modelParts.getLegs() != null) {
            parts.addAll(modelParts.getLegs());
        }

        if (modelParts.getHorns() != null) {
            parts.addAll(modelParts.getHorns());
        }

        return parts;
    }

    /**
     * Create a simple 1x1x1 cube model part.
     * Returns as collection for generic API.
     */
    private Collection<ModelDefinition.ModelPart> createCubeParts() {
        ModelDefinition.Position position = new ModelDefinition.Position(0.0f, 0.0f, 0.0f);
        ModelDefinition.Size size = new ModelDefinition.Size(1.0f, 1.0f, 1.0f);

        ModelDefinition.ModelPart cube = new ModelDefinition.ModelPart(
            "cube",
            position,
            size,
            null  // No texture needed for vertex display
        );

        cube.postLoadInitialization();

        return Collections.singletonList(cube);
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

    /**
     * Gets the vertex renderer for external access (preferences, etc.).
     */
    public VertexRenderer getVertexRenderer() {
        return vertexRenderer;
    }

    /**
     * Gets the edge renderer for external access (preferences, etc.).
     */
    public EdgeRenderer getEdgeRenderer() {
        return edgeRenderer;
    }

    /**
     * Clean up all render pipeline resources.
     */
    public void cleanup() {
        if (infiniteGridRenderer != null && infiniteGridRenderer.isInitialized()) {
            infiniteGridRenderer.cleanup();
        }
        if (vertexRenderer != null && vertexRenderer.isInitialized()) {
            vertexRenderer.cleanup();
        }
        if (edgeRenderer != null && edgeRenderer.isInitialized()) {
            edgeRenderer.cleanup();
        }
        logger.debug("RenderPipeline cleanup complete");
    }
}
