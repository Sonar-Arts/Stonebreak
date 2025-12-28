package com.openmason.main.systems.viewport.viewportRendering;

import com.openmason.main.systems.rendering.model.GenericModelRenderer;
import com.openmason.main.systems.rendering.core.BlockRenderer;
import com.openmason.main.systems.rendering.core.ItemRenderer;
import com.openmason.main.systems.viewport.viewportRendering.gizmo.rendering.GizmoRenderer;
import com.openmason.main.systems.viewport.resources.ViewportResourceManager;
import com.openmason.main.systems.rendering.core.shaders.ShaderManager;
import com.openmason.main.systems.rendering.core.shaders.ShaderProgram;
import com.openmason.main.systems.rendering.core.shaders.ShaderType;
import com.openmason.main.systems.viewport.state.RenderingMode;
import com.openmason.main.systems.viewport.state.RenderingState;
import com.openmason.main.systems.viewport.state.TransformState;
import com.openmason.main.systems.viewport.ViewportUIState;
import com.openmason.main.systems.viewport.viewportRendering.edge.EdgeRenderer;
import com.openmason.main.systems.viewport.viewportRendering.vertex.VertexRenderer;
import com.openmason.main.systems.viewport.viewportRendering.face.FaceRenderer;
import com.stonebreak.model.ModelDefinition;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;

import static org.lwjgl.opengl.GL11.*;

/**
 * Orchestrates the main rendering pipeline for the viewport.
 * Coordinates all render passes: grid → content → gizmo.
 * Follows Single Responsibility Principle - only handles render coordination.
 *
 * <h2>Render Pipeline Architecture</h2>
 * <p>The pipeline executes in ordered passes:
 * <ol>
 *   <li><b>Grid Pass</b> - Infinite grid background ({@link GridRenderer})</li>
 *   <li><b>Content Pass</b> - Block/Item/Model rendering ({@link GenericModelRenderer})</li>
 *   <li><b>Mesh Pass</b> - Vertices, edges, faces ({@link VertexRenderer}, {@link EdgeRenderer}, {@link FaceRenderer})</li>
 *   <li><b>UI Pass</b> - Gizmo overlays ({@link GizmoRenderer})</li>
 * </ol>
 *
 * <h2>Unified Rendering API Integration</h2>
 * <p>This class will be enhanced to use the new unified rendering API:
 * <ul>
 *   <li>{@link com.openmason.main.systems.rendering.api.RenderingController} - Master coordinator</li>
 *   <li>{@link com.openmason.main.systems.rendering.api.IRenderer} - Renderer interface</li>
 *   <li>{@link com.openmason.main.systems.rendering.api.RenderPass} - Pass ordering</li>
 * </ul>
 *
 * <p>New renderers should implement {@link com.openmason.main.systems.rendering.api.IRenderer}
 * or extend {@link com.openmason.main.systems.rendering.api.BaseRenderer}.
 *
 * @see com.openmason.main.systems.rendering.api.RenderingController
 * @see com.openmason.main.systems.rendering.model.GenericModelRenderer
 */
public class RenderPipeline {

    private static final Logger logger = LoggerFactory.getLogger(RenderPipeline.class);

    private final RenderContext context;
    private final ViewportResourceManager resources;
    private final ShaderManager shaderManager;

    // Specialized renderers
    private final GridRenderer gridRenderer;
    private final VertexRenderer vertexRenderer;
    private final EdgeRenderer edgeRenderer;
    private final FaceRenderer faceRenderer;

    // External renderers (blocks, items)
    private final BlockRenderer blockRenderer;
    private final ItemRenderer itemRenderer;

    // BlockModel renderer (.OMO editable models)
    private final GenericModelRenderer modelRenderer;

    // Gizmo renderer
    private final GizmoRenderer gizmoRenderer;

    // Diagnostic throttling
    private long lastDiagnosticLogTime = 0;
    private static final long DIAGNOSTIC_LOG_INTERVAL_MS = 2000;

    // Vertex data caching to prevent unnecessary updates
    private boolean vertexDataNeedsUpdate = true;

    // Edge data caching to prevent unnecessary updates (same pattern as vertices)
    private boolean edgeDataNeedsUpdate = true;

    // Face data caching to prevent unnecessary updates (same pattern as edges)
    private boolean faceDataNeedsUpdate = true;

    /**
     * Create render pipeline with all required dependencies.
     */
    public RenderPipeline(RenderContext context, ViewportResourceManager resources, ShaderManager shaderManager,
                          BlockRenderer blockRenderer, ItemRenderer itemRenderer,
                          GenericModelRenderer modelRenderer,
                          GizmoRenderer gizmoRenderer) {
        this.context = context;
        this.resources = resources;
        this.shaderManager = shaderManager;
        this.gridRenderer = new GridRenderer();
        this.vertexRenderer = new VertexRenderer();
        this.edgeRenderer = new EdgeRenderer();
        this.faceRenderer = new FaceRenderer();
        this.blockRenderer = blockRenderer;
        this.itemRenderer = itemRenderer;
        this.modelRenderer = modelRenderer;
        this.gizmoRenderer = gizmoRenderer;
    }

    /**
     * Execute complete render pipeline.
     */
    public void render(ViewportUIState viewportState, RenderingState renderingState, TransformState transformState) {
        try {
            // Update camera animation
            context.getCamera().update(0.016f); // Assuming ~60fps (16ms frame time)
            context.getCamera().updateMatrices(); // Force matrix update

            // Update render context
            context.update(viewportState.getWidth(), viewportState.getHeight(), viewportState.getWireframeMode().get());

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
            if (viewportState.getWireframeMode().get()) {
                glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
            } else {
                glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
            }

            // PASS 1: Render grid (if enabled)
            if (viewportState.getGridVisible().get()) {
                renderGrid();
            }

            // PASS 2: Render content (model/block/item)
            renderContent(renderingState, transformState);

            // Restore polygon mode after content rendering
            if (viewportState.getWireframeMode().get()) {
                glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
            }

            // PASS 3: Render mesh (vertices + edges + faces, debug overlay, Blender-style)
            if (viewportState.getShowVertices().get()) {
                renderVertices(renderingState, transformState);
                renderEdges(renderingState, transformState);
                renderFaces(renderingState, transformState);  // Render face overlays LAST for proper blending
            }

            // PASS 4: Render gizmo (after content, always in fill mode)
            if (gizmoRenderer != null && gizmoRenderer.isInitialized()) {
                renderGizmo();
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
            if (!gridRenderer.isInitialized()) {
                gridRenderer.initialize();
            }

            // Use infinite grid shader
            ShaderProgram infiniteGridShader = shaderManager.getShaderProgram(ShaderType.INFINITE_GRID);
            gridRenderer.render(infiniteGridShader, context);
        } catch (Exception e) {
            logger.error("Error rendering infinite grid", e);
        }
    }

    /**
     * Render content pass (block, item, or block model).
     */
    private void renderContent(RenderingState renderingState, TransformState transformState) {
        // Diagnostic logging (throttled)
        boolean shouldLog = shouldLogDiagnostics();

        if (shouldLog) {
            logger.trace("Render content - mode: {}, state: {}", renderingState.getMode(), renderingState.getStateDescription());
        }

        // Render based on mode
        if (renderingState.getMode() == RenderingMode.BLOCK_MODEL) {
            // Render editable block model (.OMO)
            renderBlockModel(transformState);
        } else if (renderingState.isBlockReady()) {
            renderBlock(renderingState, transformState);
        } else if (renderingState.isItemReady()) {
            renderItem(renderingState, transformState);
        } else {
            // No content to render
            logger.trace("No content ready for rendering");
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
            if (!modelRenderer.isInitialized()) {
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

            // Apply transform matrix (includes position, rotation, scale from gizmo)
            org.joml.Matrix4f modelMatrix = transformState.getTransformMatrix();
            matrixShader.setMat4("uModelMatrix", modelMatrix);

            // Enable texturing
            matrixShader.setInt("uTexture", 0);
            matrixShader.setBool("uUseTexture", true);

            // Render (no state management needed - pipeline handles it)
            modelRenderer.render(matrixShader, context, modelMatrix);

        } catch (Exception e) {
            logger.error("Error rendering BlockModel", e);
        }
    }

    /**
     * Render gizmo pass.
     */
    private void renderGizmo() {
        try {
            gizmoRenderer.render(
                context.getCamera().getViewMatrix(),
                context.getCamera().getProjectionMatrix()
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
                case BLOCK_MODEL:
                    // Editable .OMO block model rendering (simple cube)
                    Collection<ModelDefinition.ModelPart> cubeParts = createCubeParts();

                    // Only extract vertex data ONCE (in MODEL SPACE, no transform)
                    // Vertices will be transformed by model matrix in shader (like BlockModelRenderer)
                    if (vertexDataNeedsUpdate) {
                        Matrix4f identityTransform = new Matrix4f(); // Identity = no transform
                        vertexRenderer.updateVertexData(cubeParts, identityTransform);

                        // CRITICAL FIX: Sync MeshManager with GenericModelRenderer's actual vertices
                        // This ensures coordinate consistency between all systems (VertexRenderer,
                        // EdgeRenderer, FaceRenderer, MeshManager, GenericModelRenderer) for subdivision
                        // to work correctly at any level (n+1 subdivisions).
                        if (modelRenderer != null && modelRenderer.isInitialized()) {
                            float[] modelMeshVertices = modelRenderer.getAllMeshVertexPositions();
                            if (modelMeshVertices != null) {
                                var meshManager = com.openmason.main.systems.viewport.viewportRendering.mesh.MeshManager.getInstance();
                                meshManager.setMeshVertices(modelMeshVertices);

                                // Rebuild mapping with synced vertices
                                float[] uniqueVertexPositions = vertexRenderer.getAllVertexPositions();
                                if (uniqueVertexPositions != null) {
                                    meshManager.buildUniqueToMeshMapping(uniqueVertexPositions, modelMeshVertices);
                                }
                                logger.debug("Synced MeshManager with GenericModelRenderer: {} mesh vertices", modelMeshVertices.length / 3);

                                // Wire FaceRenderer to GenericModelRenderer for face overlay data access
                                faceRenderer.setGenericModelRenderer(modelRenderer);
                            }
                        }

                        vertexDataNeedsUpdate = false;
                        logger.trace("Vertex data extracted in model space");
                    }

                    ShaderProgram basicShaderBlockModel = shaderManager.getShaderProgram(ShaderType.BASIC);
                    vertexRenderer.render(basicShaderBlockModel, context, transformState.getTransformMatrix());
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
                case BLOCK_MODEL:
                    // Editable .OMO block model rendering (simple cube)
                    Collection<ModelDefinition.ModelPart> cubeParts = createCubeParts();

                    // Only extract edge data ONCE (in MODEL SPACE, no transform)
                    // Edges will be transformed by model matrix in shader (like BlockModelRenderer)
                    if (edgeDataNeedsUpdate) {
                        Matrix4f identityTransform = new Matrix4f(); // Identity = no transform

                        // Get unique vertex positions for edge deduplication
                        float[] uniqueVertexPositions = vertexRenderer.getAllVertexPositions();

                        // Extract unique edges (no duplicates) using vertex positions
                        edgeRenderer.updateEdgeData(cubeParts, identityTransform, uniqueVertexPositions);

                        // Build edge-to-vertex mapping to prevent unification bug
                        if (uniqueVertexPositions != null) {
                            edgeRenderer.buildEdgeToVertexMapping(uniqueVertexPositions);
                            logger.trace("Built edge-to-vertex mapping for unification prevention");
                        }

                        edgeDataNeedsUpdate = false;
                        logger.trace("Edge data extracted in model space (unique edges only)");
                    }

                    ShaderProgram basicShaderBlockModel = shaderManager.getShaderProgram(ShaderType.BASIC);
                    edgeRenderer.render(basicShaderBlockModel, context, transformState.getTransformMatrix());
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
     * Render faces pass (semi-transparent overlays for hover/selection).
     * Renders LAST for proper blending on top of model.
     */
    private void renderFaces(RenderingState renderingState, TransformState transformState) {
        try {
            // Initialize face renderer if needed
            if (!faceRenderer.isInitialized()) {
                faceRenderer.initialize();
            }

            // Render faces based on current rendering mode (mirrors edge rendering)
            switch (renderingState.getMode()) {
                case BLOCK_MODEL:
                    // Editable .OMO block model rendering (simple cube)
                    Collection<ModelDefinition.ModelPart> cubeParts = createCubeParts();

                    // Only extract face data ONCE (in MODEL SPACE, no transform)
                    // Faces will be transformed by model matrix in shader (like BlockModelRenderer)
                    if (faceDataNeedsUpdate) {
                        Matrix4f identityTransform = new Matrix4f(); // Identity = no transform
                        faceRenderer.updateFaceData(cubeParts, identityTransform);

                        // Build face-to-vertex mapping to prevent unification bug
                        float[] uniqueVertexPositions = vertexRenderer.getAllVertexPositions();
                        if (uniqueVertexPositions != null) {
                            faceRenderer.buildFaceToVertexMapping(uniqueVertexPositions);
                            logger.trace("Built face-to-vertex mapping for unification prevention");
                        }

                        faceDataNeedsUpdate = false;
                        logger.trace("Face data extracted in model space");
                    }

                    ShaderProgram basicShaderBlockModel = shaderManager.getShaderProgram(ShaderType.BASIC);
                    faceRenderer.render(basicShaderBlockModel, context, transformState.getTransformMatrix());
                    break;

                case BLOCK:
                    // Single block rendering
                    // TODO: Implement face extraction for Block rendering
                    logger.trace("BLOCK mode - faces not yet supported");
                    break;

                case ITEM:
                    // Item rendering
                    // TODO: Implement face extraction for Item rendering
                    logger.trace("ITEM mode - faces not yet supported");
                    break;
            }

        } catch (Exception e) {
            logger.error("Error rendering faces", e);
        }
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
     * Gets the face renderer for external access (preferences, etc.).
     */
    public FaceRenderer getFaceRenderer() {
        return faceRenderer;
    }

    /**
     * Gets the block model renderer for external access (vertex editing, etc.).
     */
    public GenericModelRenderer getBlockModelRenderer() {
        return modelRenderer;
    }

    /**
     * Clean up all render pipeline resources.
     */
    public void cleanup() {
        if (gridRenderer.isInitialized()) {
            gridRenderer.cleanup();
        }
        if (vertexRenderer.isInitialized()) {
            vertexRenderer.cleanup();
        }
        if (edgeRenderer.isInitialized()) {
            edgeRenderer.cleanup();
        }
        if (faceRenderer.isInitialized()) {
            faceRenderer.cleanup();
        }
        logger.debug("RenderPipeline cleanup complete");
    }
}
