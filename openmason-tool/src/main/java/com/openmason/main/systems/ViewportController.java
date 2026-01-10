package com.openmason.main.systems;

import com.openmason.main.systems.rendering.model.editable.BlockModel;
import com.openmason.main.systems.rendering.model.block.BlockManager;
import com.openmason.main.systems.rendering.model.io.omo.MeshDataExtractor;
import com.openmason.main.systems.rendering.model.io.omo.OMOFormat;
import com.openmason.main.systems.rendering.model.item.ItemManager;
import com.openmason.main.omConfig;
import com.openmason.main.systems.rendering.model.GenericModelRenderer;
import com.openmason.main.systems.rendering.model.UVMode;
import com.openmason.main.systems.rendering.model.miscComponents.OMTTextureLoader;
import com.openmason.main.systems.rendering.model.miscComponents.TextureLoadResult;
import com.openmason.main.systems.viewport.ViewportCamera;
import com.openmason.main.systems.viewport.ViewportInputHandler;
import com.openmason.main.systems.viewport.viewportRendering.gizmo.rendering.GizmoRenderer;
import com.openmason.main.systems.viewport.viewportRendering.gizmo.GizmoState;
import com.openmason.main.systems.rendering.core.BlockRenderer;
import com.openmason.main.systems.rendering.core.ItemRenderer;
import com.openmason.main.systems.viewport.viewportRendering.RenderContext;
import com.openmason.main.systems.viewport.viewportRendering.RenderPipeline;
import com.openmason.main.systems.viewport.resources.ViewportResourceManager;
import com.openmason.main.systems.rendering.core.shaders.ShaderManager;
import com.openmason.main.systems.viewport.state.RenderingState;
import com.openmason.main.systems.viewport.state.TransformState;
import com.openmason.main.systems.viewport.state.VertexSelectionState;
import com.openmason.main.systems.viewport.state.FaceSelectionState;
import com.openmason.main.systems.viewport.state.EditModeManager;
import com.openmason.main.systems.viewport.ViewportUIState;
import com.openmason.main.systems.viewport.viewportRendering.vertex.VertexTranslationHandler;
import com.openmason.main.systems.viewport.viewportRendering.edge.EdgeTranslationHandler;
import com.openmason.main.systems.viewport.viewportRendering.face.FaceTranslationHandler;
import com.openmason.main.systems.viewport.viewportRendering.TranslationCoordinator;
import com.openmason.main.systems.viewport.viewportRendering.edge.EdgeRenderer;
import com.openmason.main.systems.viewport.viewportRendering.face.FaceRenderer;
import com.openmason.main.systems.viewport.viewportRendering.mesh.MeshManager;
import com.openmason.main.systems.viewport.viewportRendering.vertex.VertexRenderer;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import org.joml.Vector3f;
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
    private RenderPipeline renderPipeline;

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

    // ========== Gizmo ==========
    private final GizmoState gizmoState;
    private final GizmoRenderer gizmoRenderer;

    // ========== Input & UI ==========
    private final ViewportInputHandler inputHandler;

    // ========== Block Model Loading ==========
    private final OMTTextureLoader omtTextureLoader;
    private final MeshDataExtractor meshDataExtractor;
    private BlockModel currentBlockModel;
    private int currentBlockModelTextureId = 0;

    // ========== Constructor ==========

    public ViewportController() {
        logger.info("Creating viewport with modular architecture");

        this.viewportState = new ViewportUIState();
        this.renderingState = new RenderingState();
        this.transformState = new TransformState();
        this.vertexSelectionState = new VertexSelectionState();
        this.edgeSelectionState = new com.openmason.main.systems.viewport.state.EdgeSelectionState();
        this.faceSelectionState = new FaceSelectionState();

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

        this.omtTextureLoader = new OMTTextureLoader();
        this.meshDataExtractor = new MeshDataExtractor();
        this.currentBlockModel = null;

        logger.info("Viewport created successfully");
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

            this.renderPipeline = new RenderPipeline(
                renderContext, resourceManager, shaderManager,
                blockRenderer, itemRenderer,
                    modelRenderer, gizmoRenderer
            );

            // Load vertex point size from config
            try {
                omConfig omConfig = new omConfig();
                float vertexPointSize = omConfig.getVertexPointSize();
                if (renderPipeline.getVertexRenderer() != null) {
                    renderPipeline.getVertexRenderer().setPointSize(vertexPointSize);
                    logger.debug("Loaded vertex point size: {}", vertexPointSize);
                }
            } catch (Exception e) {
                logger.warn("Failed to load vertex point size, using default", e);
            }

            // Connect renderers to input handler for hover detection
            if (renderPipeline.getVertexRenderer() != null) {
                inputHandler.setVertexRenderer(renderPipeline.getVertexRenderer());
            }
            if (renderPipeline.getEdgeRenderer() != null) {
                inputHandler.setEdgeRenderer(renderPipeline.getEdgeRenderer());
            }
            if (renderPipeline.getFaceRenderer() != null) {
                inputHandler.setFaceRenderer(renderPipeline.getFaceRenderer());
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

            // Create translation handlers and coordinator
            if (renderPipeline.getVertexRenderer() != null && renderPipeline.getEdgeRenderer() != null &&
                renderPipeline.getFaceRenderer() != null && renderPipeline.getBlockModelRenderer() != null) {

                // Create vertex translation handler
                VertexTranslationHandler vertexTranslationHandler = new VertexTranslationHandler(
                    vertexSelectionState,
                    renderPipeline.getVertexRenderer(),
                    renderPipeline.getEdgeRenderer(),
                    renderPipeline.getFaceRenderer(),
                    renderPipeline.getBlockModelRenderer(),
                    viewportState,
                    renderPipeline,
                    transformState
                );
                logger.debug("Vertex translation handler created");

                // Create edge translation handler
                EdgeTranslationHandler edgeTranslationHandler = new EdgeTranslationHandler(
                    edgeSelectionState,
                    renderPipeline.getEdgeRenderer(),
                    renderPipeline.getVertexRenderer(),
                    renderPipeline.getFaceRenderer(),
                    renderPipeline.getBlockModelRenderer(),
                    viewportState,
                    renderPipeline,
                    transformState
                );
                logger.debug("Edge translation handler created");

                // Create face translation handler
                FaceTranslationHandler faceTranslationHandler = new FaceTranslationHandler(
                    faceSelectionState,
                    renderPipeline.getFaceRenderer(),
                    renderPipeline.getVertexRenderer(),
                    renderPipeline.getEdgeRenderer(),
                    renderPipeline.getBlockModelRenderer(),
                    viewportState,
                    renderPipeline,
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
                    renderPipeline.getVertexRenderer(),
                    renderPipeline.getEdgeRenderer(),
                    renderPipeline.getFaceRenderer()
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

        if (renderPipeline != null) {
            try { renderPipeline.cleanup(); }
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

        renderPipeline.render(viewportState, renderingState, transformState);
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

    // ========== Content Loading ==========

    /**
     * Load BlockModel (.OMO file) for editing.
     * Loads embedded texture, sets up rendering, and resets camera.
     */
    public void loadBlockModel(BlockModel blockModel) {
        if (blockModel == null) {
            logger.error("Cannot load null BlockModel");
            return;
        }

        logger.info("Loading BlockModel: {}", blockModel.getName());

        unloadBlockModel();
        currentBlockModel = blockModel;

        // Load texture and auto-detect UV mode
        java.nio.file.Path texturePath = blockModel.getTexturePath();
        if (texturePath != null && java.nio.file.Files.exists(texturePath)) {
            TextureLoadResult result =
                omtTextureLoader.loadTextureComposite(texturePath);

            if (result.isSuccess()) {
                // Auto-detect UV mode based on texture dimensions
                UVMode detectedMode = UVMode.detectFromDimensions(result.getWidth(), result.getHeight());
                modelRenderer.setUVMode(detectedMode);
                logger.info("Auto-detected UV mode: {} for texture {}x{}",
                    detectedMode, result.getWidth(), result.getHeight());

                currentBlockModelTextureId = result.getTextureId();
                modelRenderer.setTexture(result.getTextureId());
                logger.info("Loaded BlockModel texture: {}", result);
            } else {
                logger.error("Failed to load texture from: {}", texturePath);
            }
        } else {
            logger.warn("BlockModel has no valid texture path: {}", texturePath);
        }

        // Load geometry from BlockModel into renderer (LEGACY support only)
        com.openmason.main.systems.rendering.model.editable.ModelGeometry geometry = blockModel.getGeometry();
        if (geometry != null) {
            try {
                // LEGACY: Generate mesh from dimensions for old BlockModel format
                // TODO: Modern models should provide topology via .omo files, not dimensions
                @SuppressWarnings("deprecation")
                com.openmason.main.systems.rendering.model.io.omo.OMOFormat.MeshData meshData =
                    com.openmason.main.systems.rendering.model.LegacyGeometryGenerator.generateLegacyBoxMesh(
                        geometry.getWidth(),
                        geometry.getHeight(),
                        geometry.getDepth(),
                        geometry.getX(),
                        geometry.getY(),
                        geometry.getZ(),
                        com.openmason.main.systems.rendering.model.UVMode.CUBE_NET // Legacy default
                    );
                modelRenderer.loadMeshData(meshData);
                logger.info("Loaded legacy BlockModel geometry: {}x{}x{} at ({}, {}, {})",
                           geometry.getWidth(), geometry.getHeight(), geometry.getDepth(),
                           geometry.getX(), geometry.getY(), geometry.getZ());
            } catch (Exception e) {
                logger.error("Failed to load geometry into renderer", e);
            }
        } else {
            logger.warn("BlockModel has no geometry - model will be invisible");
        }

        renderingState.setBlockModelMode(blockModel.getName());
        transformState.resetPosition();

        logger.info("BlockModel loaded successfully: {}", blockModel.getName());
    }

    /**
     * Unload current BlockModel and free texture.
     */
    private void unloadBlockModel() {
        if (currentBlockModel != null) {
            logger.info("Unloading BlockModel: {}", currentBlockModel.getName());

            if (currentBlockModelTextureId > 0) {
                omtTextureLoader.deleteTexture(currentBlockModelTextureId);
                currentBlockModelTextureId = 0;
            }

            currentBlockModel = null;
        }
    }

    // ========== Mesh Data (OMO v1.1+ support) ==========

    /**
     * Extract current mesh state for saving to .OMO file.
     * Returns null if using standard cube geometry (no modifications).
     *
     * @return MeshData with current vertex/index data, or null for standard cube
     */
    public OMOFormat.MeshData extractMeshData() {
        return meshDataExtractor.extract(modelRenderer);
    }

    /**
     * Check if the current mesh has been modified from a standard cube.
     *
     * @return true if mesh has custom geometry (subdivisions, vertex moves, etc.)
     */
    public boolean hasCustomMeshData() {
        return meshDataExtractor.hasCustomMeshData(modelRenderer);
    }

    /**
     * Load mesh state from MeshData (restored from .OMO file).
     * This replaces the current geometry with the loaded data.
     * Call this AFTER loadBlockModel() to apply custom mesh data.
     *
     * @param meshData the mesh data to load
     */
    public void loadMeshData(OMOFormat.MeshData meshData) {
        meshDataExtractor.load(modelRenderer, meshData);

        // Invalidate render pipeline caches to force edge/face rebuild
        if (renderPipeline != null) {
            renderPipeline.invalidateMeshData();

            // Initialize renderers if needed (they check !initialized in rebuildFromModel)
            VertexRenderer vertexRenderer = renderPipeline.getVertexRenderer();
            EdgeRenderer edgeRenderer = renderPipeline.getEdgeRenderer();
            FaceRenderer faceRenderer = renderPipeline.getFaceRenderer();

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
     */
    public void setSelectedBlock(BlockType blockType) {
        if (blockType == null) {
            logger.warn("Cannot set null block type");
            return;
        }

        renderingState.setBlockMode(blockType);
        transformState.resetPosition();
    }

    /**
     * Set item to render in viewport.
     */
    public void setSelectedItem(ItemType itemType) {
        if (itemType == null) {
            logger.warn("Cannot set null item type");
            return;
        }

        renderingState.setItemMode(itemType);
        transformState.resetPosition();
    }

    /**
     * Set texture variant for current model.
     */
    public void setCurrentTextureVariant(String variant) {
        renderingState.setCurrentTextureVariant(variant);
    }

    /**
     * Update only the texture for the current BlockModel without rebuilding geometry.
     * Use this when changing textures to preserve any vertex/geometry modifications.
     * UV coordinates are updated to match the new texture type while preserving vertex positions.
     *
     * @param blockModel The BlockModel with updated texture path
     */
    public void updateBlockModelTexture(BlockModel blockModel) {
        if (blockModel == null) {
            logger.warn("Cannot update texture for null BlockModel");
            return;
        }

        logger.info("Updating BlockModel texture (preserving geometry): {}", blockModel.getName());

        // Delete old texture if exists
        if (currentBlockModelTextureId > 0) {
            omtTextureLoader.deleteTexture(currentBlockModelTextureId);
            currentBlockModelTextureId = 0;
        }

        // Update reference (in case it changed)
        currentBlockModel = blockModel;

        // Load new texture and update UV coordinates to match texture type
        java.nio.file.Path texturePath = blockModel.getTexturePath();
        if (texturePath != null && java.nio.file.Files.exists(texturePath)) {
            TextureLoadResult result = omtTextureLoader.loadTextureComposite(texturePath);

            if (result.isSuccess()) {
                // Auto-detect UV mode and update UVs without rebuilding vertex positions
                UVMode detectedMode = UVMode.detectFromDimensions(result.getWidth(), result.getHeight());
                modelRenderer.updateUVModeOnly(detectedMode);
                logger.info("Updated UV mode to {} for texture {}x{} (geometry preserved)",
                    detectedMode, result.getWidth(), result.getHeight());

                currentBlockModelTextureId = result.getTextureId();
                modelRenderer.setTexture(result.getTextureId());
                logger.info("Updated BlockModel texture: {}", texturePath.getFileName());
            } else {
                logger.error("Failed to load texture from: {}", texturePath);
                modelRenderer.setTexture(0);
            }
        } else {
            logger.info("BlockModel texture cleared or path invalid: {}", texturePath);
            modelRenderer.setTexture(0);
        }

        logger.info("BlockModel texture updated successfully: {}", blockModel.getName());
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
        if (renderPipeline != null && renderPipeline.getVertexRenderer() != null) {
            renderPipeline.getVertexRenderer().setEnabled(showVertices);
        }
        if (renderPipeline != null && renderPipeline.getEdgeRenderer() != null) {
            renderPipeline.getEdgeRenderer().setEnabled(showVertices);
        }
        if (renderPipeline != null && renderPipeline.getFaceRenderer() != null) {
            renderPipeline.getFaceRenderer().setEnabled(showVertices);
        }
    }

    public void setVertexPointSize(float size) {
        if (renderPipeline != null && renderPipeline.getVertexRenderer() != null) {
            renderPipeline.getVertexRenderer().setPointSize(size);
        }
    }

    // ========== Edge Operations ==========

    /**
     * Subdivide the currently hovered edge at its midpoint.
     * Coordinates between EdgeRenderer, VertexRenderer, MeshManager, and GenericModelRenderer.
     *
     * @return Index of newly created vertex, or -1 if failed
     */
    public int subdivideHoveredEdge() {
        if (renderPipeline == null) {
            logger.warn("Cannot subdivide edge: render pipeline not initialized");
            return -1;
        }

        var edgeRenderer = renderPipeline.getEdgeRenderer();
        var vertexRenderer = renderPipeline.getVertexRenderer();

        if (edgeRenderer == null || vertexRenderer == null) {
            logger.warn("Cannot subdivide edge: renderers not available");
            return -1;
        }

        // Get edge vertex indices and positions BEFORE subdivision (for GenericModelRenderer update)
        // FIX: Use vertex positions from VertexRenderer (source of truth) instead of EdgeRenderer's
        // edgePositions, which can drift from actual vertex positions after multiple subdivisions.
        int hoveredEdgeIndex = edgeRenderer.getHoveredEdgeIndex();
        int[] edgeVertexIndices = edgeRenderer.getEdgeVertexIndices(hoveredEdgeIndex);
        if (edgeVertexIndices == null || edgeVertexIndices.length != 2) {
            logger.warn("Cannot get edge vertex indices for subdivision");
            return -1;
        }

        // Get endpoint positions from VertexRenderer (ensures coordinates match GenericModelRenderer)
        Vector3f endpoint1 = vertexRenderer.getVertexPosition(edgeVertexIndices[0]);
        Vector3f endpoint2 = vertexRenderer.getVertexPosition(edgeVertexIndices[1]);
        if (endpoint1 == null || endpoint2 == null) {
            logger.warn("Cannot get vertex positions for subdivision endpoints");
            return -1;
        }
        // Make copies to avoid mutation issues
        endpoint1 = new Vector3f(endpoint1);
        endpoint2 = new Vector3f(endpoint2);

        // Calculate midpoint
        Vector3f midpoint = new Vector3f(
            (endpoint1.x + endpoint2.x) / 2.0f,
            (endpoint1.y + endpoint2.y) / 2.0f,
            (endpoint1.z + endpoint2.z) / 2.0f
        );

        logger.info("Subdivision: edge {} connects vertices {} and {} at ({},{},{}) to ({},{},{}), midpoint: ({},{},{})",
            hoveredEdgeIndex, edgeVertexIndices[0], edgeVertexIndices[1],
            endpoint1.x, endpoint1.y, endpoint1.z,
            endpoint2.x, endpoint2.y, endpoint2.z,
            midpoint.x, midpoint.y, midpoint.z);

        // Apply subdivision to GenericModelRenderer (single source of truth)
        // This triggers EdgeRenderer.onGeometryRebuilt() via MeshChangeListener
        var modelRenderer = renderPipeline.getBlockModelRenderer();
        if (modelRenderer == null) {
            logger.warn("Cannot subdivide: model renderer not available");
            return -1;
        }

        int meshVertexIndex = modelRenderer.applyEdgeSubdivisionByPosition(
            midpoint, endpoint1, endpoint2
        );

        if (meshVertexIndex >= 0) {
            // Sync MeshManager with GenericModelRenderer's actual vertices
            // GenericModelRenderer may add multiple vertices (one per split triangle for UV)
            MeshManager meshManager = MeshManager.getInstance();
            float[] modelMeshVertices = modelRenderer.getAllMeshVertexPositions();
            if (modelMeshVertices != null) {
                meshManager.setMeshVertices(modelMeshVertices);
                logger.debug("Synced MeshManager with GenericModelRenderer: {} mesh vertices",
                    modelMeshVertices.length / 3);
            }

            // Rebuild FaceRenderer data from GenericModelRenderer's triangles
            // After subdivision, face overlay geometry must match the actual mesh topology
            var faceRenderer = renderPipeline.getFaceRenderer();
            if (faceRenderer != null) {
                faceRenderer.setGenericModelRenderer(modelRenderer);
                faceRenderer.rebuildFromGenericModelRenderer();
                logger.debug("Rebuilt FaceRenderer from GenericModelRenderer triangles");
            }

            logger.info("Subdivided edge {}, created mesh vertex {}", hoveredEdgeIndex, meshVertexIndex);
            return meshVertexIndex;
        } else {
            logger.warn("Failed to apply subdivision to GenericModelRenderer");
            return -1;
        }
    }

    /**
     * Subdivide all currently selected edges at their midpoints.
     * If no edges are selected, falls back to subdividing the hovered edge.
     * Edges are processed in descending order to prevent index shifting issues.
     *
     * @return Number of edges successfully subdivided
     */
    public int subdivideSelectedEdges() {
        if (renderPipeline == null) {
            logger.warn("Cannot subdivide edges: render pipeline not initialized");
            return 0;
        }

        var edgeRenderer = renderPipeline.getEdgeRenderer();
        var vertexRenderer = renderPipeline.getVertexRenderer();

        if (edgeRenderer == null || vertexRenderer == null) {
            logger.warn("Cannot subdivide edges: renderers not available");
            return 0;
        }

        // Get selected edges from EdgeSelectionState
        java.util.Set<Integer> selectedEdges = edgeSelectionState.getSelectedEdgeIndices();

        if (selectedEdges.isEmpty()) {
            // Fall back to hovered edge if no selection
            logger.debug("No edges selected, falling back to hovered edge");
            int result = subdivideHoveredEdge();
            return result >= 0 ? 1 : 0;
        }

        var modelRenderer = renderPipeline.getBlockModelRenderer();
        if (modelRenderer == null) {
            logger.warn("Cannot subdivide: model renderer not available");
            return 0;
        }

        // Collect all edge endpoint positions BEFORE any subdivision
        // (positions will be copied, so they remain valid even as mesh changes)
        java.util.List<Vector3f[]> edgeEndpoints = new java.util.ArrayList<>();
        for (int edgeIndex : selectedEdges) {
            int[] edgeVertexIndices = edgeRenderer.getEdgeVertexIndices(edgeIndex);
            if (edgeVertexIndices == null || edgeVertexIndices.length != 2) {
                logger.warn("Cannot get edge vertex indices for edge {}", edgeIndex);
                continue;
            }

            Vector3f endpoint1 = vertexRenderer.getVertexPosition(edgeVertexIndices[0]);
            Vector3f endpoint2 = vertexRenderer.getVertexPosition(edgeVertexIndices[1]);
            if (endpoint1 == null || endpoint2 == null) {
                logger.warn("Cannot get vertex positions for edge {}", edgeIndex);
                continue;
            }

            // Store copies of endpoints
            edgeEndpoints.add(new Vector3f[] {
                new Vector3f(endpoint1),
                new Vector3f(endpoint2)
            });
        }

        // Apply subdivisions to GenericModelRenderer
        // EdgeRenderer automatically rebuilds after each via MeshChangeListener
        MeshManager meshManager = MeshManager.getInstance();
        int successCount = 0;

        for (Vector3f[] endpoints : edgeEndpoints) {
            Vector3f endpoint1 = endpoints[0];
            Vector3f endpoint2 = endpoints[1];

            // Calculate midpoint
            Vector3f midpoint = new Vector3f(
                (endpoint1.x + endpoint2.x) / 2.0f,
                (endpoint1.y + endpoint2.y) / 2.0f,
                (endpoint1.z + endpoint2.z) / 2.0f
            );

            // Apply subdivision to GenericModelRenderer (single source of truth)
            int meshVertexIndex = modelRenderer.applyEdgeSubdivisionByPosition(
                midpoint, endpoint1, endpoint2
            );

            if (meshVertexIndex >= 0) {
                // Sync MeshManager with GenericModelRenderer's vertices
                float[] modelMeshVertices = modelRenderer.getAllMeshVertexPositions();
                if (modelMeshVertices != null) {
                    meshManager.setMeshVertices(modelMeshVertices);
                }
                successCount++;
            }
        }

        // Rebuild face overlays after all subdivisions
        if (successCount > 0) {
            var faceRenderer = renderPipeline.getFaceRenderer();
            if (faceRenderer != null) {
                faceRenderer.setGenericModelRenderer(modelRenderer);
                faceRenderer.rebuildFromGenericModelRenderer();
            }
        }

        // Clear edge selection (indices are invalid after subdivision)
        edgeSelectionState.clearSelection();
        edgeRenderer.clearSelection();

        logger.info("Subdivided {} edges", successCount);
        return successCount;
    }

    // ========== Component Accessors ==========

    public ViewportCamera getCamera() { return viewportCamera; }
    public ViewportInputHandler getInputHandler() { return inputHandler; }

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
