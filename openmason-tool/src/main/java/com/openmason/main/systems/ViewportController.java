package com.openmason.main.systems;

import com.openmason.main.systems.rendering.model.editable.BlockModel;
import com.openmason.main.systems.rendering.model.block.BlockManager;
import com.openmason.main.systems.rendering.model.io.omo.OMOFormat;
import com.openmason.main.systems.rendering.model.item.ItemManager;
import com.openmason.main.omConfig;
import com.openmason.main.systems.rendering.model.GenericModelRenderer;
import com.openmason.main.systems.rendering.model.miscComponents.OMTTextureLoader;
import com.openmason.main.systems.viewport.ViewportCamera;
import com.openmason.main.systems.viewport.ViewportInputHandler;
import com.openmason.main.systems.viewport.viewportRendering.ViewportRenderPipeline;
import com.openmason.main.systems.viewport.viewportRendering.gizmo.rendering.GizmoRenderer;
import com.openmason.main.systems.viewport.viewportRendering.gizmo.GizmoState;
import com.openmason.main.systems.rendering.core.BlockRenderer;
import com.openmason.main.systems.rendering.core.ItemRenderer;
import com.openmason.main.systems.viewport.viewportRendering.RenderContext;
import com.openmason.main.systems.viewport.resources.ViewportResourceManager;
import com.openmason.main.systems.rendering.core.shaders.ShaderManager;
import com.openmason.main.systems.viewport.state.RenderingState;
import com.openmason.main.systems.viewport.state.TransformState;
import com.openmason.main.systems.viewport.state.VertexSelectionState;
import com.openmason.main.systems.viewport.state.FaceSelectionState;
import com.openmason.main.systems.viewport.state.EditModeManager;
import com.openmason.main.systems.viewport.ViewportUIState;
import com.openmason.main.systems.viewport.input.KnifeSnapSettings;
import com.openmason.main.systems.rendering.model.gmr.subrenders.vertex.VertexTranslationHandler;
import com.openmason.main.systems.rendering.model.gmr.subrenders.edge.EdgeTranslationHandler;
import com.openmason.main.systems.rendering.model.gmr.subrenders.face.FaceTranslationHandler;
import com.openmason.main.systems.viewport.viewportRendering.TranslationCoordinator;
import com.openmason.main.systems.rendering.model.gmr.subrenders.edge.EdgeRenderer;
import com.openmason.main.systems.rendering.model.gmr.subrenders.face.FaceRenderer;
import com.openmason.main.systems.rendering.model.gmr.subrenders.vertex.VertexRenderer;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 3D viewport controller using modular architecture.
 * Displays models, blocks, and items from stonebreak-game module.
 */
public class ViewportController {

    private static final Logger logger = LoggerFactory.getLogger(ViewportController.class);

    // ========== Core Components ==========
    private final ShaderManager shaderManager;
    private final ViewportResourceManager resourceManager;
    private final ViewportCamera viewportCamera;
    private final RenderContext renderContext;
    private ViewportRenderPipeline viewportRenderPipeline;

    // ========== State ==========
    private final ViewportUIState viewportState;
    private final RenderingState renderingState;
    private final TransformState transformState;
    private final VertexSelectionState vertexSelectionState;
    private final com.openmason.main.systems.viewport.state.EdgeSelectionState edgeSelectionState;
    private final FaceSelectionState faceSelectionState;

    // ========== Renderers ==========
    private final BlockRenderer blockRenderer;
    private final ItemRenderer itemRenderer;
    private final GenericModelRenderer modelRenderer;

    // ========== Knife Snap ==========
    private final KnifeSnapSettings knifeSnapSettings;

    // ========== Gizmo ==========
    private final GizmoState gizmoState;
    private final GizmoRenderer gizmoRenderer;

    // ========== Input & UI ==========
    private final ViewportInputHandler inputHandler;

    private final com.openmason.main.systems.viewport.content.ContentTypeManager contentTypeManager;

    // ========== Services ==========
    private com.openmason.main.systems.services.EdgeOperationService edgeOperationService;

    // ========== Constructor ==========

    public ViewportController() {
        logger.info("Creating viewport with modular architecture");

        this.viewportState = new ViewportUIState();
        this.renderingState = new RenderingState();
        this.transformState = new TransformState();
        this.vertexSelectionState = new VertexSelectionState();
        this.edgeSelectionState = new com.openmason.main.systems.viewport.state.EdgeSelectionState();
        this.faceSelectionState = new FaceSelectionState();

        this.knifeSnapSettings = new KnifeSnapSettings();

        this.gizmoState = new GizmoState();
        this.gizmoRenderer = new GizmoRenderer(gizmoState, transformState, viewportState);

        this.shaderManager = new ShaderManager();
        this.resourceManager = new ViewportResourceManager();

        this.viewportCamera = new ViewportCamera();
        this.renderContext = new RenderContext(viewportCamera);

        this.inputHandler = new ViewportInputHandler(viewportCamera);

        this.blockRenderer = new BlockRenderer("Viewport");
        this.itemRenderer = new ItemRenderer("Viewport");
        this.modelRenderer = new GenericModelRenderer();

        // Content loading (SOLID refactored)
        // ========== Content Loading (SOLID refactored) ==========
        OMTTextureLoader omtTextureLoader = new OMTTextureLoader();
        com.openmason.main.systems.viewport.content.BlockModelLoader blockModelLoader = new com.openmason.main.systems.viewport.content.BlockModelLoader(
                omtTextureLoader, modelRenderer
        );
        this.contentTypeManager = new com.openmason.main.systems.viewport.content.ContentTypeManager(
                blockModelLoader, renderingState, transformState
        );

        logger.info("Viewport created successfully with SOLID content management");
    }

    // ========== Lifecycle ==========

    /**
     * Initialize OpenGL resources. Idempotent - safe to call multiple times.
     */
    public void initialize() {
        if (viewportState.isInitialized()) {
            logger.debug("Viewport already initialized");
            return;
        }

        try {
            logger.info("Initializing viewport modules...");

            shaderManager.initialize();
            resourceManager.initialize(viewportState.getWidth(), viewportState.getHeight());

            if (!BlockManager.isInitialized()) BlockManager.initialize();
            blockRenderer.initialize();

            if (!ItemManager.isInitialized()) ItemManager.initialize();
            itemRenderer.initialize();

            modelRenderer.initialize();
            gizmoRenderer.initialize();

            inputHandler.setGizmoRenderer(gizmoRenderer);
            gizmoRenderer.updateViewportState(viewportState);

            gizmoState.setEnabled(true);
            transformState.setGizmoEnabled(true);

            this.viewportRenderPipeline = new ViewportRenderPipeline(
                renderContext, resourceManager, shaderManager,
                blockRenderer, itemRenderer,
                    modelRenderer, gizmoRenderer
            );

            // Initialize EdgeOperationService after ViewportRenderPipeline is ready
            this.edgeOperationService = new com.openmason.main.systems.services.EdgeOperationService(
                    viewportRenderPipeline, edgeSelectionState
            );
            logger.debug("EdgeOperationService initialized");

            // Load vertex point size from config
            try {
                omConfig omConfig = new omConfig();
                float vertexPointSize = omConfig.getVertexPointSize();
                if (viewportRenderPipeline.getVertexRenderer() != null) {
                    viewportRenderPipeline.getVertexRenderer().setPointSize(vertexPointSize);
                    logger.debug("Loaded vertex point size: {}", vertexPointSize);
                }
            } catch (Exception e) {
                logger.warn("Failed to load vertex point size, using default", e);
            }

            // Connect renderers to input handler for hover detection
            if (viewportRenderPipeline.getVertexRenderer() != null) {
                inputHandler.setVertexRenderer(viewportRenderPipeline.getVertexRenderer());
            }
            if (viewportRenderPipeline.getEdgeRenderer() != null) {
                inputHandler.setEdgeRenderer(viewportRenderPipeline.getEdgeRenderer());
            }
            if (viewportRenderPipeline.getFaceRenderer() != null) {
                inputHandler.setFaceRenderer(viewportRenderPipeline.getFaceRenderer());
            }

            // Connect vertex selection state for vertex manipulation
            inputHandler.setVertexSelectionState(vertexSelectionState);
            logger.debug("Vertex selection state connected to input handler");

            // Connect edge selection state for edge manipulation
            inputHandler.setEdgeSelectionState(edgeSelectionState);
            logger.debug("Edge selection state connected to input handler");

            // Connect face selection state for face manipulation
            inputHandler.setFaceSelectionState(faceSelectionState);
            logger.debug("Face selection state connected to input handler");

            // Connect transform state for model matrix access in hover detection
            inputHandler.setTransformState(transformState);
            logger.debug("Transform state connected to input handler");

            // Connect model renderer for edge insertion (J key) and knife tool (K key)
            inputHandler.setModelRenderer(modelRenderer);
            logger.debug("Model renderer connected to input handler for edge insertion");

            // Connect knife preview renderer for visual feedback
            if (viewportRenderPipeline.getKnifePreviewRenderer() != null) {
                inputHandler.setKnifePreviewRenderer(viewportRenderPipeline.getKnifePreviewRenderer());
                logger.debug("Knife preview renderer connected to input handler");
            }

            // Connect knife snap settings for independent knife tool grid snapping
            inputHandler.setKnifeSnapSettings(knifeSnapSettings);

            // Create translation handlers and coordinator
            if (viewportRenderPipeline.getVertexRenderer() != null && viewportRenderPipeline.getEdgeRenderer() != null &&
                viewportRenderPipeline.getFaceRenderer() != null && viewportRenderPipeline.getBlockModelRenderer() != null) {

                // Create vertex translation handler
                VertexTranslationHandler vertexTranslationHandler = new VertexTranslationHandler(
                    vertexSelectionState,
                    viewportRenderPipeline.getVertexRenderer(),
                    viewportRenderPipeline.getEdgeRenderer(),
                    viewportRenderPipeline.getFaceRenderer(),
                    viewportRenderPipeline.getBlockModelRenderer(),
                    viewportState,
                        viewportRenderPipeline,
                    transformState
                );
                logger.debug("Vertex translation handler created");

                // Create edge translation handler
                EdgeTranslationHandler edgeTranslationHandler = new EdgeTranslationHandler(
                    edgeSelectionState,
                    viewportRenderPipeline.getEdgeRenderer(),
                    viewportRenderPipeline.getVertexRenderer(),
                    viewportRenderPipeline.getFaceRenderer(),
                    viewportRenderPipeline.getBlockModelRenderer(),
                    viewportState,
                        viewportRenderPipeline,
                    transformState
                );
                logger.debug("Edge translation handler created");

                // Create face translation handler
                FaceTranslationHandler faceTranslationHandler = new FaceTranslationHandler(
                    faceSelectionState,
                    viewportRenderPipeline.getFaceRenderer(),
                    viewportRenderPipeline.getVertexRenderer(),
                    viewportRenderPipeline.getEdgeRenderer(),
                    viewportRenderPipeline.getBlockModelRenderer(),
                    viewportState,
                        viewportRenderPipeline,
                    transformState
                );
                logger.debug("Face translation handler created");

                // Create coordinator to manage mutual exclusion between handlers
                TranslationCoordinator translationCoordinator = new TranslationCoordinator(
                    vertexTranslationHandler,
                    edgeTranslationHandler,
                    faceTranslationHandler
                );
                logger.debug("Translation coordinator created");

                // Connect coordinator to input handler
                inputHandler.setTranslationCoordinator(translationCoordinator);
                logger.debug("Translation coordinator connected to input handler");

                // Connect selection components to EditModeManager for clearing on mode switch
                EditModeManager.getInstance().setSelectionComponents(
                    vertexSelectionState,
                    edgeSelectionState,
                    faceSelectionState,
                    viewportRenderPipeline.getVertexRenderer(),
                    viewportRenderPipeline.getEdgeRenderer(),
                    viewportRenderPipeline.getFaceRenderer()
                );
                logger.debug("Selection components connected to EditModeManager");
            }

            viewportState.setViewportInitialized(true);
            logger.info("Viewport initialized successfully");

        } catch (Exception e) {
            logger.error("Viewport initialization failed", e);
            cleanup();
            throw new RuntimeException("Viewport initialization failed", e);
        }
    }

    /**
     * Clean up all OpenGL resources.
     */
    public void cleanup() {
        logger.info("Cleaning up viewport resources");

        if (inputHandler != null) inputHandler.cleanup();

        if (gizmoRenderer != null) {
            try { gizmoRenderer.dispose(); }
            catch (Exception e) { logger.error("Error cleaning up gizmo renderer", e); }
        }

        if (viewportRenderPipeline != null) {
            try { viewportRenderPipeline.cleanup(); }
            catch (Exception e) { logger.error("Error cleaning up render pipeline", e); }
        }

        resourceManager.close();
        shaderManager.cleanup();

        viewportState.setViewportInitialized(false);
        logger.info("Viewport cleanup complete");
    }

    /**
     * Update camera and input state.
     * @param deltaTime time since last update in seconds
     */
    public void update(float deltaTime) {
        if (viewportCamera != null) viewportCamera.update(deltaTime);
        if (inputHandler != null) inputHandler.handleKeyboardInput(deltaTime);
    }

    // ========== Rendering ==========

    /**
     * Render viewport content to framebuffer.
     */
    public void render() {
        if (!viewportState.isInitialized()) {
            logger.debug("Viewport not initialized, initializing now...");
            initialize();
        }

        viewportRenderPipeline.render(viewportState, renderingState, transformState);
    }

    /**
     * Resize viewport framebuffer.
     */
    public void resize(int width, int height) {
        if (width <= 0 || height <= 0 || !viewportState.dimensionsChanged(width, height)) {
            return;
        }

        viewportState.setDimensions(width, height);

        if (viewportState.isInitialized()) {
            resourceManager.resizeFramebuffer(width, height);
        }

        viewportCamera.setAspectRatio(viewportState.getAspectRatio());
        logger.trace("Viewport resized to {}x{}", width, height);
    }

    // ========== Content Loading (Delegating to SOLID classes) ==========

    /**
     * Load BlockModel (.OMO file) for editing.
     * Loads embedded texture, sets up rendering, and resets camera.
     *
     * <p><b>SOLID Refactored:</b> Delegates to ContentTypeManager → BlockModelLoader.
     */
    public void loadBlockModel(BlockModel blockModel) {
        contentTypeManager.switchToBlockModel(blockModel);
    }

    // ========== Mesh Data (OMO v1.1+ support) ==========

    /**
     * Extract current mesh state for saving to .OMO file.
     * Returns null if using standard cube geometry (no modifications).
     *
     * @return MeshData with current vertex/index data, or null for standard cube
     */
    public OMOFormat.MeshData extractMeshData() {
        return modelRenderer.toMeshData();
    }

    /**
     * Load mesh state from MeshData (restored from .OMO file).
     * This replaces the current geometry with the loaded data.
     * Call this AFTER loadBlockModel() to apply custom mesh data.
     *
     * @param meshData the mesh data to load
     */
    public void loadMeshData(OMOFormat.MeshData meshData) {
        modelRenderer.loadMeshData(meshData);

        // Invalidate render pipeline caches to force edge/face rebuild
        if (viewportRenderPipeline != null) {
            viewportRenderPipeline.invalidateMeshData();

            // Initialize renderers if needed (they check !initialized in rebuildFromModel)
            VertexRenderer vertexRenderer = viewportRenderPipeline.getVertexRenderer();
            EdgeRenderer edgeRenderer = viewportRenderPipeline.getEdgeRenderer();
            FaceRenderer faceRenderer = viewportRenderPipeline.getFaceRenderer();

            if (vertexRenderer != null && !vertexRenderer.isInitialized()) {
                vertexRenderer.initialize();
            }
            if (edgeRenderer != null && !edgeRenderer.isInitialized()) {
                edgeRenderer.initialize();
            }
            if (faceRenderer != null && !faceRenderer.isInitialized()) {
                faceRenderer.initialize();
            }

            // Force immediate rebuild of all renderers from model
            // This prevents stale data from causing issues before first render
            if (vertexRenderer != null) {
                vertexRenderer.setModelRenderer(modelRenderer);
            }
            if (edgeRenderer != null) {
                edgeRenderer.setModelRenderer(modelRenderer);
            }
            if (faceRenderer != null) {
                faceRenderer.setGenericModelRenderer(modelRenderer);
            }
        }
    }

    /**
     * Set block to render in viewport.
     *
     * <p><b>SOLID Refactored:</b> Delegates to ContentTypeManager.
     */
    public void setSelectedBlock(BlockType blockType) {
        contentTypeManager.switchToBlock(blockType);
    }

    /**
     * Set item to render in viewport.
     *
     * <p><b>SOLID Refactored:</b> Delegates to ContentTypeManager.
     */
    public void setSelectedItem(ItemType itemType) {
        contentTypeManager.switchToItem(itemType);
    }

    /**
     * Set texture variant for current model.
     *
     * <p><b>SOLID Refactored:</b> Delegates to ContentTypeManager.
     */
    public void setCurrentTextureVariant(String variant) {
        contentTypeManager.setTextureVariant(variant);
    }

    /**
     * Update only the texture for the current BlockModel without rebuilding geometry.
     * Use this when changing textures to preserve any vertex/geometry modifications.
     * UV coordinates are updated to match the new texture type while preserving vertex positions.
     *
     * <p><b>SOLID Refactored:</b> Delegates to ContentTypeManager → BlockModelLoader.
     *
     * @param blockModel The BlockModel with updated texture path
     */
    public void updateBlockModelTexture(BlockModel blockModel) {
        contentTypeManager.updateBlockModelTexture(blockModel);
    }

    // ========== Transform ==========

    /** Set position, rotation, and non-uniform scale */
    public void setModelTransform(float posX, float posY, float posZ,
                                  float rotX, float rotY, float rotZ,
                                  float scaleX, float scaleY, float scaleZ) {
        transformState.setPosition(posX, posY, posZ);
        transformState.setRotation(rotX, rotY, rotZ);
        transformState.setScale(scaleX, scaleY, scaleZ);
    }

    /** Reset transform to defaults */
    public void resetModelTransform() {
        transformState.reset();
    }

    // ========== Viewport Configuration ==========

    public void setShowGrid(boolean showGrid) {
        viewportState.getGridVisible().set(showGrid);
    }

    public void setWireframeMode(boolean wireframe) {
        viewportState.getWireframeMode().set(wireframe);
    }

    public void setAxesVisible(boolean visible) {
        viewportState.getAxesVisible().set(visible);
    }

    public void setGridSnappingEnabled(boolean enabled) {
        viewportState.getGridSnappingEnabled().set(enabled);
        if (gizmoRenderer != null) {
            gizmoRenderer.updateViewportState(viewportState);
        }
    }

    public void setGridSnappingIncrement(float increment) {
        viewportState.getGridSnappingIncrement().set(increment);
        if (gizmoRenderer != null) {
            gizmoRenderer.updateViewportState(viewportState);
        }
        logger.debug("Grid snapping increment: {}", increment);
    }

    public void setShowVertices(boolean showVertices) {
        viewportState.getShowVertices().set(showVertices);
        if (viewportRenderPipeline != null && viewportRenderPipeline.getVertexRenderer() != null) {
            viewportRenderPipeline.getVertexRenderer().setEnabled(showVertices);
        }
        if (viewportRenderPipeline != null && viewportRenderPipeline.getEdgeRenderer() != null) {
            viewportRenderPipeline.getEdgeRenderer().setEnabled(showVertices);
        }
        if (viewportRenderPipeline != null && viewportRenderPipeline.getFaceRenderer() != null) {
            viewportRenderPipeline.getFaceRenderer().setEnabled(showVertices);
        }
    }

    public void setVertexPointSize(float size) {
        if (viewportRenderPipeline != null && viewportRenderPipeline.getVertexRenderer() != null) {
            viewportRenderPipeline.getVertexRenderer().setPointSize(size);
        }
    }

    // ========== Edge Operations (Delegated to EdgeOperationService) ==========

    /**
     * Subdivide the currently hovered edge at its midpoint.
     * Delegates to EdgeOperationService for proper separation of concerns.
     *
     * @return Index of newly created vertex, or -1 if failed
     */
    public int subdivideHoveredEdge() {
        if (edgeOperationService == null) {
            logger.warn("Cannot subdivide edge: EdgeOperationService not initialized");
            return -1;
        }
        return edgeOperationService.subdivideHoveredEdge();
    }

    /**
     * Subdivide all currently selected edges at their midpoints.
     * If no edges are selected, falls back to subdividing the hovered edge.
     * Delegates to EdgeOperationService for proper separation of concerns.
     *
     * @return Number of edges successfully subdivided
     */
    public int subdivideSelectedEdges() {
        if (edgeOperationService == null) {
            logger.warn("Cannot subdivide edges: EdgeOperationService not initialized");
            return 0;
        }
        return edgeOperationService.subdivideSelectedEdges();
    }

    // ========== Component Accessors ==========

    public ViewportCamera getCamera() { return viewportCamera; }
    public ViewportInputHandler getInputHandler() { return inputHandler; }
    public FaceSelectionState getFaceSelectionState() { return faceSelectionState; }
    public GenericModelRenderer getModelRenderer() { return modelRenderer; }
    public KnifeSnapSettings getKnifeSnapSettings() { return knifeSnapSettings; }

    /**
     * Toggle the knife tool from keybind (K key).
     * Delegates to ViewportInputHandler → KnifeToolController.
     */
    public void toggleKnifeTool() {
        if (inputHandler != null) {
            inputHandler.toggleKnifeTool();
        }
    }

    /**
     * @return true if the knife tool is currently active
     */
    public boolean isKnifeToolActive() {
        return inputHandler != null && inputHandler.isKnifeToolActive();
    }

    /**
     * Start grab mode from keybind (G key).
     * Blender-style: Press G to grab and move all selected items.
     *
     * @return true if grab started successfully, false otherwise
     */
    public boolean startGrabMode() {
        if (inputHandler == null) {
            logger.warn("Cannot start grab: input handler not initialized");
            return false;
        }
        return inputHandler.startGrabMode();
    }

    public void resetCamera() {
        if (viewportCamera != null) viewportCamera.reset();
    }

    // ========== State Accessors ==========

    public int getColorTexture() { return resourceManager.getFramebuffer().getColorTextureId(); }
    public boolean isInitialized() { return viewportState.isInitialized(); }

    public float getModelPositionX() { return transformState.getPositionX(); }
    public float getModelPositionY() { return transformState.getPositionY(); }
    public float getModelPositionZ() { return transformState.getPositionZ(); }
    public float getModelRotationX() { return transformState.getRotationX(); }
    public float getModelRotationY() { return transformState.getRotationY(); }
    public float getModelRotationZ() { return transformState.getRotationZ(); }
    public float getModelScaleX() { return transformState.getScaleX(); }
    public float getModelScaleY() { return transformState.getScaleY(); }
    public float getModelScaleZ() { return transformState.getScaleZ(); }
    public float getMinScale() { return TransformState.getMinScale(); }
    public float getMaxScale() { return TransformState.getMaxScale(); }

    // ========== Gizmo API ==========

    public void setGizmoEnabled(boolean enabled) {
        gizmoState.setEnabled(enabled);
        transformState.setGizmoEnabled(enabled);
    }

    public boolean isGizmoEnabled() {
        return gizmoState.isEnabled();
    }

    public void setGizmoMode(GizmoState.Mode mode) {
        gizmoRenderer.setMode(mode);
    }

    public GizmoState.Mode getGizmoMode() {
        return gizmoRenderer.getCurrentMode();
    }

    public void setGizmoUniformScaling(boolean uniform) {
        gizmoState.setUniformScaling(uniform);
    }

    public boolean getGizmoUniformScaling() {
        return gizmoState.isUniformScaling();
    }

    // ========== Window Integration ==========

    /**
     * Set GLFW window handle for mouse capture.
     */
    public void setWindowHandle(long windowHandle) {
        if (inputHandler != null) {
            inputHandler.setWindowHandle(windowHandle);
            logger.debug("Window handle set");
        } else {
            logger.warn("Cannot set window handle - input handler is null");
        }
    }
}
