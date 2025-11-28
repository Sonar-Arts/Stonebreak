package com.openmason.ui;

import com.openmason.block.BlockManager;
import com.openmason.item.ItemManager;
import com.openmason.deprecated.LegacyCowStonebreakModel;
import com.openmason.deprecated.LegacyCowModelRenderer;
import com.openmason.ui.viewport.ViewportCamera;
import com.openmason.ui.viewport.ViewportInputHandler;
import com.openmason.ui.viewport.gizmo.rendering.GizmoRenderer;
import com.openmason.ui.viewport.gizmo.GizmoState;
import com.openmason.deprecated.AsyncModelLoader;
import com.openmason.rendering.core.BlockRenderer;
import com.openmason.rendering.core.ItemRenderer;
import com.openmason.deprecated.LegacyCowTextureAtlas;
import com.openmason.ui.viewport.rendering.RenderContext;
import com.openmason.ui.viewport.rendering.RenderPipeline;
import com.openmason.ui.viewport.resources.ViewportResourceManager;
import com.openmason.ui.viewport.shaders.ShaderManager;
import com.openmason.ui.viewport.state.RenderingState;
import com.openmason.ui.viewport.state.TransformState;
import com.openmason.ui.viewport.ViewportUIState;
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
    private RenderPipeline renderPipeline;

    // ========== State ==========
    private ViewportUIState viewportState;
    private final RenderingState renderingState;
    private final TransformState transformState;

    // ========== Renderers ==========
    private final LegacyCowModelRenderer legacyCowModelRenderer;
    private final BlockRenderer blockRenderer;
    private final ItemRenderer itemRenderer;
    private final LegacyCowTextureAtlas legacyCowTextureAtlas;
    private final com.openmason.rendering.blockmodel.BlockModelRenderer blockModelRenderer;

    // ========== Gizmo ==========
    private final GizmoState gizmoState;
    private final GizmoRenderer gizmoRenderer;

    // ========== Input & UI ==========
    private final ViewportInputHandler inputHandler;

    // ========== Model Loading ==========
    private final AsyncModelLoader modelLoader;
    private final com.openmason.rendering.blockmodel.OMTTextureLoader omtTextureLoader;
    private com.openmason.model.editable.BlockModel currentBlockModel;
    private int currentBlockModelTextureId = 0;

    // ========== Constructor ==========

    public ViewportController() {
        logger.info("Creating viewport with modular architecture");

        this.viewportState = new ViewportUIState();
        this.renderingState = new RenderingState();
        this.transformState = new TransformState();

        this.gizmoState = new GizmoState();
        this.gizmoRenderer = new GizmoRenderer(gizmoState, transformState, viewportState);

        this.shaderManager = new ShaderManager();
        this.resourceManager = new ViewportResourceManager();

        this.viewportCamera = new ViewportCamera();
        this.renderContext = new RenderContext(viewportCamera);

        this.inputHandler = new ViewportInputHandler(viewportCamera);

        this.legacyCowModelRenderer = new LegacyCowModelRenderer("Viewport");
        this.blockRenderer = new BlockRenderer("Viewport");
        this.itemRenderer = new ItemRenderer("Viewport");
        this.legacyCowTextureAtlas = new LegacyCowTextureAtlas("Viewport_CowAtlas");
        this.blockModelRenderer = new com.openmason.rendering.blockmodel.BlockModelRenderer();

        this.omtTextureLoader = new com.openmason.rendering.blockmodel.OMTTextureLoader();
        this.currentBlockModel = null;

        this.modelLoader = new AsyncModelLoader();

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

            legacyCowModelRenderer.initialize();
            legacyCowModelRenderer.setMatrixTransformationMode(true);

            if (!BlockManager.isInitialized()) BlockManager.initialize();
            blockRenderer.initialize();

            if (!ItemManager.isInitialized()) ItemManager.initialize();
            itemRenderer.initialize();

            blockModelRenderer.initialize();
            legacyCowTextureAtlas.initialize();
            gizmoRenderer.initialize();

            inputHandler.setGizmoRenderer(gizmoRenderer);
            gizmoRenderer.updateViewportState(viewportState);

            gizmoState.setEnabled(true);
            transformState.setGizmoEnabled(true);

            this.renderPipeline = new RenderPipeline(
                renderContext, resourceManager, shaderManager,
                legacyCowModelRenderer, blockRenderer, itemRenderer,
                legacyCowTextureAtlas, blockModelRenderer, gizmoRenderer
            );

            // Load vertex point size from config
            try {
                com.openmason.app.AppConfig appConfig = new com.openmason.app.AppConfig();
                float vertexPointSize = appConfig.getVertexPointSize();
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

        if (legacyCowModelRenderer != null) {
            try { legacyCowModelRenderer.close(); }
            catch (Exception e) { logger.error("Error cleaning up model renderer", e); }
        }

        if (legacyCowTextureAtlas != null) legacyCowTextureAtlas.close();

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

    /** Alias for {@link #cleanup()} */
    public void dispose() {
        cleanup();
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

    /** No-op: rendering happens automatically in main loop */
    public void requestRender() {
        // Rendering happens automatically
    }

    // ========== Content Loading ==========

    /**
     * Load model asynchronously by name.
     */
    public void loadModel(String modelName) {
        if (modelName == null || modelName.trim().isEmpty()) {
            logger.error("Cannot load model: name is null or empty");
            return;
        }

        renderingState.setModelMode(modelName, null);
        transformState.resetPosition();

        logger.info("Loading model: {}", modelName);

        modelLoader.loadModelAsync(modelName,
            model -> renderingState.setCurrentModel(model),
            error -> logger.error("Failed to load model: {}", error.getMessage())
        );
    }

    /**
     * Load BlockModel (.OMO file) for editing.
     * Loads embedded texture, sets up rendering, and resets camera.
     */
    public void loadBlockModel(com.openmason.model.editable.BlockModel blockModel) {
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
            com.openmason.rendering.blockmodel.TextureLoadResult result =
                omtTextureLoader.loadTextureComposite(texturePath);

            if (result.isSuccess()) {
                if (result.isCubeNet()) {
                    blockModelRenderer.setUVMode(com.openmason.rendering.blockmodel.BlockModelRenderer.UVMode.CUBE_NET);
                    logger.debug("Auto-detected CUBE_NET UV mode");
                } else if (result.isFlat16x16()) {
                    blockModelRenderer.setUVMode(com.openmason.rendering.blockmodel.BlockModelRenderer.UVMode.FLAT);
                    logger.debug("Auto-detected FLAT UV mode");
                } else {
                    blockModelRenderer.setUVMode(com.openmason.rendering.blockmodel.BlockModelRenderer.UVMode.FLAT);
                    logger.warn("Non-standard texture size ({}x{}), defaulting to FLAT UV mode",
                        result.getWidth(), result.getHeight());
                }

                currentBlockModelTextureId = result.getTextureId();
                blockModelRenderer.setTexture(result);
                logger.info("Loaded BlockModel texture: {}", result);
            } else {
                logger.error("Failed to load texture from: {}", texturePath);
            }
        } else {
            logger.warn("BlockModel has no valid texture path: {}", texturePath);
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

    /** Alias for {@link #setShowGrid(boolean)} */
    public void setGridVisible(boolean visible) {
        setShowGrid(visible);
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
    }

    public void setVertexPointSize(float size) {
        if (renderPipeline != null && renderPipeline.getVertexRenderer() != null) {
            renderPipeline.getVertexRenderer().setPointSize(size);
        }
    }

    // ========== Component Accessors ==========

    public ViewportCamera getCamera() { return viewportCamera; }
    public LegacyCowModelRenderer getModelRenderer() { return legacyCowModelRenderer; }
    public ViewportInputHandler getInputHandler() { return inputHandler; }
    public GizmoRenderer getGizmoRenderer() { return gizmoRenderer; }

    public void resetCamera() {
        if (viewportCamera != null) viewportCamera.reset();
    }

    // ========== State Accessors ==========

    public int getColorTexture() { return resourceManager.getFramebuffer().getColorTextureId(); }
    public boolean isInitialized() { return viewportState.isInitialized(); }
    public float getCurrentFPS() { return 60.0f; } // Placeholder

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
