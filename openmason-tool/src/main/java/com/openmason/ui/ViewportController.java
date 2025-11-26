package com.openmason.ui;

import com.openmason.block.BlockManager;
import com.openmason.item.ItemManager;
import com.openmason.deprecated.LegacyCowStonebreakModel;
import com.openmason.deprecated.LegacyCowModelRenderer;
import com.openmason.ui.viewport.ViewportCamera;
import com.openmason.ui.viewport.ViewportInputHandler;
import com.openmason.ui.viewport.gizmo.GizmoRenderer;
import com.openmason.ui.viewport.gizmo.GizmoState;
import com.openmason.ui.viewport.model.AsyncModelLoader;
import com.openmason.rendering.core.BlockRenderer;
import com.openmason.rendering.core.ItemRenderer;
import com.openmason.deprecated.LegacyCowTextureAtlas;
import com.openmason.ui.viewport.rendering.RenderContext;
import com.openmason.ui.viewport.rendering.RenderPipeline;
import com.openmason.ui.viewport.resources.ViewportResourceManager;
import com.openmason.ui.viewport.shaders.ShaderManager;
import com.openmason.ui.viewport.state.RenderingState;
import com.openmason.ui.viewport.state.TransformState;
import com.openmason.ui.viewport.state.ViewportState;
import com.openmason.ui.viewport.ui.ModelControlsUI;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Clean and robust 3D viewport implementation using modular architecture.
 * Displays 3D models, blocks, and items loaded from stonebreak-game module.
 */
public class ViewportController {

    private static final Logger logger = LoggerFactory.getLogger(ViewportController.class);

    // ========== Module Dependencies ==========
    // Managers
    private final ShaderManager shaderManager;
    private final ViewportResourceManager resourceManager;

    // State
    private ViewportState viewportState;
    private final RenderingState renderingState;
    private final TransformState transformState;

    // Rendering
    private final ViewportCamera viewportCamera;
    private final RenderContext renderContext;
    private RenderPipeline renderPipeline;

    // Input handling
    private final ViewportInputHandler inputHandler;

    // External renderers
    private final LegacyCowModelRenderer legacyCowModelRenderer;
    private final BlockRenderer blockRenderer;
    private final ItemRenderer itemRenderer;
    private final LegacyCowTextureAtlas legacyCowTextureAtlas;

    // BlockModel rendering (.OMO editable models)
    private final com.openmason.rendering.blockmodel.BlockModelRenderer blockModelRenderer;
    private final com.openmason.rendering.blockmodel.OMTTextureLoader omtTextureLoader;
    private com.openmason.model.editable.BlockModel currentBlockModel;
    private int currentBlockModelTextureId = 0;

    // Model loading
    private final AsyncModelLoader modelLoader;

    // UI
    private final ModelControlsUI modelControlsUI;

    // Gizmo
    private final GizmoState gizmoState;
    private final GizmoRenderer gizmoRenderer;

    /**
     * Create new viewport with default configuration.
     */
    public ViewportController() {
        logger.info("Creating OpenMason3DViewport with modular architecture");

        // Initialize state
        this.viewportState = ViewportState.createDefault();
        this.renderingState = new RenderingState();
        this.transformState = new TransformState();

        // Initialize gizmo (pass viewportState for grid snapping)
        this.gizmoState = new GizmoState();
        this.gizmoRenderer = new GizmoRenderer(gizmoState, transformState, viewportState);

        // Initialize managers
        this.shaderManager = new ShaderManager();
        this.resourceManager = new ViewportResourceManager();

        // Initialize camera and rendering
        this.viewportCamera = new ViewportCamera();
        this.renderContext = new RenderContext(viewportCamera);

        // Initialize input handling
        this.inputHandler = new ViewportInputHandler(viewportCamera);

        // Initialize external renderers
        this.legacyCowModelRenderer = new LegacyCowModelRenderer("Viewport");
        this.blockRenderer = new BlockRenderer("Viewport");
        this.itemRenderer = new ItemRenderer("Viewport");
        this.legacyCowTextureAtlas = new LegacyCowTextureAtlas("Viewport_CowAtlas");

        // Initialize BlockModel renderers (.OMO support)
        this.blockModelRenderer = new com.openmason.rendering.blockmodel.BlockModelRenderer();
        this.omtTextureLoader = new com.openmason.rendering.blockmodel.OMTTextureLoader();
        this.currentBlockModel = null;

        // Initialize model loader
        this.modelLoader = new AsyncModelLoader();

        // Initialize UI
        this.modelControlsUI = new ModelControlsUI();

        logger.info("OpenMason3DViewport created successfully with modular architecture");
    }

    /**
     * Set the GLFW window handle for mouse capture functionality.
     */
    public void setWindowHandle(long windowHandle) {
        if (inputHandler != null) {
            inputHandler.setWindowHandle(windowHandle);
            logger.debug("Window handle set on input handler");
        } else {
            logger.warn("Cannot set window handle - input handler is null");
        }
    }

    /**
     * Initialize all OpenGL resources.
     */
    public void initialize() {
        if (viewportState.isInitialized()) {
            logger.debug("Viewport already initialized");
            return;
        }

        try {
            logger.info("Initializing OpenMason3D Viewport modules...");

            // Initialize managers
            shaderManager.initialize();
            resourceManager.initialize(viewportState.getWidth(), viewportState.getHeight());

            // Initialize model renderer
            legacyCowModelRenderer.initialize();
            legacyCowModelRenderer.setMatrixTransformationMode(true);

            // Initialize block renderer and BlockManager
            if (!BlockManager.isInitialized()) {
                BlockManager.initialize();
            }
            blockRenderer.initialize();

            // Initialize item renderer and ItemManager
            if (!ItemManager.isInitialized()) {
                ItemManager.initialize();
            }
            itemRenderer.initialize();

            // Initialize BlockModel renderer (for .OMO editable models)
            blockModelRenderer.initialize();

            // Initialize texture atlas
            legacyCowTextureAtlas.initialize();

            // Initialize gizmo renderer
            gizmoRenderer.initialize();

            // Set gizmo renderer in input handler for interaction
            inputHandler.setGizmoRenderer(gizmoRenderer);

            // Update gizmo renderer with initial viewport state for snapping
            gizmoRenderer.updateViewportState(viewportState);

            // Enable gizmo by default
            gizmoState.setEnabled(true);
            transformState.setGizmoEnabled(true); // Apply position to model transform

            // Create render pipeline (after all dependencies initialized)
            this.renderPipeline = new RenderPipeline(
                renderContext, resourceManager, shaderManager,
                    legacyCowModelRenderer, blockRenderer, itemRenderer,
                    legacyCowTextureAtlas, blockModelRenderer, gizmoRenderer
            );

            // Load vertex point size from config and apply to renderer
            try {
                com.openmason.app.AppConfig appConfig = new com.openmason.app.AppConfig();
                float vertexPointSize = appConfig.getVertexPointSize();
                if (renderPipeline.getVertexRenderer() != null) {
                    renderPipeline.getVertexRenderer().setPointSize(vertexPointSize);
                    logger.debug("Loaded vertex point size from config: {}", vertexPointSize);
                }
            } catch (Exception e) {
                logger.warn("Failed to load vertex point size from config, using default", e);
            }

            // Set vertex renderer in input handler for hover detection
            if (renderPipeline.getVertexRenderer() != null) {
                inputHandler.setVertexRenderer(renderPipeline.getVertexRenderer());
                logger.debug("Vertex renderer set in input handler for hover detection");
            }

            // Set edge renderer in input handler for hover detection
            if (renderPipeline.getEdgeRenderer() != null) {
                inputHandler.setEdgeRenderer(renderPipeline.getEdgeRenderer());
                logger.debug("Edge renderer set in input handler for hover detection");
            }

            // Update state
            this.viewportState = viewportState.toBuilder().initialized(true).build();

            logger.info("OpenMason3D Viewport initialized successfully");

        } catch (Exception e) {
            logger.error("Failed to initialize viewport", e);
            cleanup();
            throw new RuntimeException("Viewport initialization failed", e);
        }
    }

    /**
     * Resize viewport and update state.
     */
    public void resize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }

        if (!viewportState.dimensionsChanged(width, height)) {
            return;
        }

        // Update viewport state
        this.viewportState = viewportState.toBuilder()
            .dimensions(width, height)
            .build();

        // Resize framebuffer if initialized
        if (viewportState.isInitialized()) {
            resourceManager.resizeFramebuffer(width, height);
        }

        // Update camera aspect ratio
        viewportCamera.setAspectRatio(viewportState.getAspectRatio());

        // Reduced logging verbosity (resize operations are frequent)
        logger.trace("Viewport resized to {}x{}", width, height);
    }

    /**
     * Main render method.
     */
    public void render() {
        if (!viewportState.isInitialized()) {
            logger.debug("Viewport not initialized, initializing now...");
            initialize();
        }

        // Delegate rendering to pipeline
        renderPipeline.render(viewportState, renderingState, transformState);
    }

    /**
     * Load model asynchronously.
     */
    public void loadModel(String modelName) {
        if (modelName == null || modelName.trim().isEmpty()) {
            logger.error("Cannot load model: name is null or empty");
            return;
        }

        // Switch to model rendering mode
        renderingState.setModelMode(modelName, null);

        // Reset position when loading new models
        transformState.resetPosition();

        logger.info("Loading model: {}", modelName);

        // Load asynchronously
        modelLoader.loadModelAsync(modelName,
            model -> renderingState.setCurrentModel(model),
            error -> logger.error("Failed to load model: {}", error.getMessage())
        );
    }

    /**
     * Load a BlockModel (.OMO editable model) for display.
     *
     * <p>This method:
     * <ul>
     *   <li>Loads the texture from the embedded .OMT file</li>
     *   <li>Sets up rendering for the block model</li>
     *   <li>Resets camera position</li>
     * </ul>
     *
     * @param blockModel the BlockModel to display
     */
    public void loadBlockModel(com.openmason.model.editable.BlockModel blockModel) {
        if (blockModel == null) {
            logger.error("Cannot load null BlockModel");
            return;
        }

        logger.info("Loading BlockModel: {}", blockModel.getName());

        // Unload any existing block model
        unloadBlockModel();

        // Store reference
        currentBlockModel = blockModel;

        // Load texture with full layer compositing and auto-detect UV mode
        java.nio.file.Path texturePath = blockModel.getTexturePath();
        if (texturePath != null && java.nio.file.Files.exists(texturePath)) {
            com.openmason.rendering.blockmodel.TextureLoadResult result =
                omtTextureLoader.loadTextureComposite(texturePath);

            if (result.isSuccess()) {
                // Auto-detect UV mode based on texture dimensions
                if (result.isCubeNet()) {
                    blockModelRenderer.setUVMode(com.openmason.rendering.blockmodel.BlockModelRenderer.UVMode.CUBE_NET);
                    logger.debug("Auto-detected CUBE_NET UV mode (64x48 texture)");
                } else if (result.isFlat16x16()) {
                    blockModelRenderer.setUVMode(com.openmason.rendering.blockmodel.BlockModelRenderer.UVMode.FLAT);
                    logger.debug("Auto-detected FLAT UV mode (16x16 texture)");
                } else {
                    // Default to FLAT for non-standard sizes
                    blockModelRenderer.setUVMode(com.openmason.rendering.blockmodel.BlockModelRenderer.UVMode.FLAT);
                    logger.warn("Non-standard texture size ({}x{}), defaulting to FLAT UV mode",
                        result.getWidth(), result.getHeight());
                }

                // Set texture on renderer (with transparency info)
                currentBlockModelTextureId = result.getTextureId();
                blockModelRenderer.setTexture(result);
                logger.info("Loaded multi-layer BlockModel texture: {}", result);
            } else {
                logger.error("Failed to load texture from: {}", texturePath);
            }
        } else {
            logger.warn("BlockModel has no valid texture path: {}", texturePath);
        }

        // Switch to block model rendering mode
        renderingState.setBlockModelMode(blockModel.getName());

        // Reset position when loading new models
        transformState.resetPosition();

        logger.info("BlockModel loaded successfully: {}", blockModel.getName());
    }

    /**
     * Unload the current BlockModel and free resources.
     */
    private void unloadBlockModel() {
        if (currentBlockModel != null) {
            logger.info("Unloading BlockModel: {}", currentBlockModel.getName());

            // Delete texture
            if (currentBlockModelTextureId > 0) {
                omtTextureLoader.deleteTexture(currentBlockModelTextureId);
                currentBlockModelTextureId = 0;
            }

            currentBlockModel = null;
        }
    }

    /**
     * Set selected block for rendering.
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
     * Set selected item for rendering.
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
     * Set current texture variant.
     */
    public void setCurrentTextureVariant(String variant) {
        renderingState.setCurrentTextureVariant(variant);
    }

    /**
     * Set model transform.
     */
    public void setModelTransform(float rotX, float rotY, float rotZ, float scale) {
        transformState.setTransform(
            transformState.getPositionX(), transformState.getPositionY(), transformState.getPositionZ(),
            rotX, rotY, rotZ, scale
        );
    }

    /**
     * Set model transform with position (uniform scale).
     */
    public void setModelTransform(float posX, float posY, float posZ,
                                  float rotX, float rotY, float rotZ, float scale) {
        transformState.setTransform(posX, posY, posZ, rotX, rotY, rotZ, scale);
    }

    /**
     * Set model transform with position and non-uniform scale.
     */
    public void setModelTransform(float posX, float posY, float posZ,
                                  float rotX, float rotY, float rotZ,
                                  float scaleX, float scaleY, float scaleZ) {
        transformState.setPosition(posX, posY, posZ);
        transformState.setRotation(rotX, rotY, rotZ);
        transformState.setScale(scaleX, scaleY, scaleZ);
    }

    /**
     * Reset model transform to defaults.
     */
    public void resetModelTransform() {
        transformState.reset();
    }

    /**
     * Cleanup all resources.
     */
    public void cleanup() {
        logger.info("Cleaning up viewport resources");

        if (inputHandler != null) {
            inputHandler.cleanup();
        }

        if (legacyCowModelRenderer != null) {
            try {
                legacyCowModelRenderer.close();
            } catch (Exception e) {
                logger.error("Error cleaning up model renderer", e);
            }
        }

        if (legacyCowTextureAtlas != null) {
            legacyCowTextureAtlas.close();
        }

        if (gizmoRenderer != null) {
            try {
                gizmoRenderer.dispose();
            } catch (Exception e) {
                logger.error("Error cleaning up gizmo renderer", e);
            }
        }

        if (renderPipeline != null) {
            try {
                renderPipeline.cleanup();
            } catch (Exception e) {
                logger.error("Error cleaning up render pipeline", e);
            }
        }

        resourceManager.close();
        shaderManager.cleanup();

        this.viewportState = viewportState.toBuilder().initialized(false).build();

        logger.info("Viewport cleanup complete");
    }

    // ========== Public API (Backward Compatibility) ==========

    public ViewportCamera getCamera() { return viewportCamera; }
    public LegacyCowModelRenderer getModelRenderer() { return legacyCowModelRenderer; }
    public ViewportInputHandler getInputHandler() { return inputHandler; }

    public void setShowGrid(boolean showGrid) {
        this.viewportState = viewportState.toBuilder().showGrid(showGrid).build();
    }

    public void setGridVisible(boolean visible) { setShowGrid(visible); }

    public void setWireframeMode(boolean wireframe) {
        this.viewportState = viewportState.toBuilder().wireframeMode(wireframe).build();
    }

    public void setAxesVisible(boolean visible) {
        this.viewportState = viewportState.toBuilder().showAxes(visible).build();
    }

    public void setGridSnappingEnabled(boolean enabled) {
        this.viewportState = viewportState.toBuilder().gridSnappingEnabled(enabled).build();
        // Update gizmo renderer with new viewport state for snapping
        if (gizmoRenderer != null) {
            gizmoRenderer.updateViewportState(this.viewportState);
        }
    }

    public void setGridSnappingIncrement(float increment) {
        this.viewportState = viewportState.toBuilder().gridSnappingIncrement(increment).build();
        // Update gizmo renderer with new viewport state for snapping
        if (gizmoRenderer != null) {
            gizmoRenderer.updateViewportState(this.viewportState);
        }
        logger.debug("Grid snapping increment updated to: {}", increment);
    }

    public void setShowVertices(boolean showVertices) {
        this.viewportState = viewportState.toBuilder().showVertices(showVertices).build();
        // Enable/disable vertex renderer
        if (renderPipeline != null && renderPipeline.getVertexRenderer() != null) {
            renderPipeline.getVertexRenderer().setEnabled(showVertices);
        }
        // Enable/disable edge renderer (mesh visualization includes edges)
        if (renderPipeline != null && renderPipeline.getEdgeRenderer() != null) {
            renderPipeline.getEdgeRenderer().setEnabled(showVertices);
        }
    }

    public void setVertexPointSize(float size) {
        if (renderPipeline != null && renderPipeline.getVertexRenderer() != null) {
            renderPipeline.getVertexRenderer().setPointSize(size);
        }
    }

    public int getColorTexture() { return resourceManager.getFramebuffer().getColorTextureId(); }
    public boolean isInitialized() { return viewportState.isInitialized(); }

    public String getCurrentModelName() { return renderingState.getCurrentModelName(); }

    public LegacyCowStonebreakModel getCurrentModel() { return renderingState.getCurrentModel(); }

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

    public void resetCamera() {
        if (viewportCamera != null) {
            viewportCamera.reset();
        }
    }

    public void update(float deltaTime) {
        if (viewportCamera != null) {
            viewportCamera.update(deltaTime);
        }
        if (inputHandler != null) {
            inputHandler.handleKeyboardInput(deltaTime);
        }
    }

    public float getCurrentFPS() {
        return 60.0f; // Placeholder
    }

    public void dispose() {
        cleanup();
    }

    public void requestRender() {
        // Rendering happens automatically
    }

    // ========== Gizmo API ==========

    public GizmoRenderer getGizmoRenderer() {
        return gizmoRenderer;
    }

    public void setGizmoEnabled(boolean enabled) {
        gizmoState.setEnabled(enabled);
        transformState.setGizmoEnabled(enabled); // Keep flags synchronized
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
}
