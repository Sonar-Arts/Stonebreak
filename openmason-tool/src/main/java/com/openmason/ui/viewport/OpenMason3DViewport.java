package com.openmason.ui.viewport;

import com.openmason.block.BlockManager;
import com.openmason.item.ItemManager;
import com.openmason.deprecated.LegacyCowStonebreakModel;
import com.openmason.deprecated.LegacyCowModelRenderer;
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
import com.openmason.ui.viewport.ui.ViewportControlsUI;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiWindowFlags;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Clean and robust 3D viewport implementation using modular architecture.
 * Displays 3D models, blocks, and items loaded from stonebreak-game module.
 *
 * This class acts as a thin orchestrator/facade, delegating to specialized modules:
 * - Shader management → ShaderManager
 * - Resource management → ViewportResourceManager
 * - Rendering → RenderPipeline
 * - State → ViewportState, RenderingState, TransformState
 * - UI → ViewportControlsUI, ModelControlsUI
 * - Model loading → AsyncModelLoader
 *
 * Follows SOLID principles for maintainability and extensibility.
 */
public class OpenMason3DViewport {

    private static final Logger logger = LoggerFactory.getLogger(OpenMason3DViewport.class);

    // ========== Module Dependencies ==========
    // Managers
    private final ShaderManager shaderManager;
    private final ViewportResourceManager resourceManager;

    // State
    private ViewportState viewportState;
    private final RenderingState renderingState;
    private final TransformState transformState;

    // Rendering
    private final Camera camera;
    private final RenderContext renderContext;
    private RenderPipeline renderPipeline;

    // Input handling
    private final ViewportInputHandler inputHandler;

    // External renderers
    private final LegacyCowModelRenderer legacyCowModelRenderer;
    private final BlockRenderer blockRenderer;
    private final ItemRenderer itemRenderer;
    private final LegacyCowTextureAtlas legacyCowTextureAtlas;

    // Model loading
    private final AsyncModelLoader modelLoader;

    // UI
    private final ViewportControlsUI viewportControlsUI;
    private final ModelControlsUI modelControlsUI;

    // Gizmo
    private final GizmoState gizmoState;
    private final GizmoRenderer gizmoRenderer;

    /**
     * Create new viewport with default configuration.
     */
    public OpenMason3DViewport() {
        logger.info("Creating OpenMason3DViewport with modular architecture");

        // Initialize state
        this.viewportState = ViewportState.createDefault();
        this.renderingState = new RenderingState();
        this.transformState = new TransformState();

        // Initialize gizmo
        this.gizmoState = new GizmoState();
        this.gizmoRenderer = new GizmoRenderer(gizmoState, transformState);

        // Initialize managers
        this.shaderManager = new ShaderManager();
        this.resourceManager = new ViewportResourceManager();

        // Initialize camera and rendering
        this.camera = new Camera();
        this.renderContext = new RenderContext(camera);

        // Initialize input handling
        this.inputHandler = new ViewportInputHandler(camera);

        // Initialize external renderers
        this.legacyCowModelRenderer = new LegacyCowModelRenderer("Viewport");
        this.blockRenderer = new BlockRenderer("Viewport");
        this.itemRenderer = new ItemRenderer("Viewport");
        this.legacyCowTextureAtlas = new LegacyCowTextureAtlas("Viewport_CowAtlas");

        // Initialize model loader
        this.modelLoader = new AsyncModelLoader();

        // Initialize UI
        this.viewportControlsUI = new ViewportControlsUI();
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

            // Initialize texture atlas
            legacyCowTextureAtlas.initialize();

            // Initialize gizmo renderer
            gizmoRenderer.initialize();

            // Set gizmo renderer in input handler for interaction
            inputHandler.setGizmoRenderer(gizmoRenderer);

            // Enable gizmo by default
            gizmoState.setEnabled(true);
            transformState.setGizmoEnabled(true); // Apply position to model transform

            // Create render pipeline (after all dependencies initialized)
            this.renderPipeline = new RenderPipeline(
                renderContext, resourceManager, shaderManager,
                    legacyCowModelRenderer, blockRenderer, itemRenderer,
                    legacyCowTextureAtlas, gizmoRenderer
            );

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
        camera.setAspectRatio(viewportState.getAspectRatio());

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
     * Display viewport in ImGui.
     */
    public void displayInImGui() {
        if (!viewportState.isInitialized() || resourceManager.getFramebuffer().getColorTextureId() == -1) {
            ImGui.text("Viewport not initialized");
            return;
        }

        // Begin 3D Viewport window
        ImGui.begin("3D Viewport", ImGuiWindowFlags.None);

        // Get available content region
        ImVec2 contentRegion = ImGui.getContentRegionAvail();
        int newWidth = (int) contentRegion.x;
        int newHeight = (int) contentRegion.y;

        // Resize if needed
        if (newWidth > 0 && newHeight > 0) {
            resize(newWidth, newHeight);
        }

        // Render 3D content
        render();

        // Display the rendered texture
        ImVec2 imagePos = ImGui.getCursorScreenPos();
        ImGui.image(resourceManager.getFramebuffer().getColorTextureId(),
                   contentRegion.x, contentRegion.y, 0, 1, 1, 0);

        // Check if the viewport window itself is being hovered
        // This prevents mouse capture when interacting with overlaying windows
        boolean viewportHovered = ImGui.isWindowHovered();

        // Handle input
        if (inputHandler != null) {
            inputHandler.handleInput(imagePos, contentRegion.x, contentRegion.y, viewportHovered);
        }

        ImGui.end();

        // Show control windows
        showControls();
        showModelControls();
    }

    /**
     * Show viewport controls window.
     */
    private void showControls() {
        viewportControlsUI.updateState(viewportState, camera, inputHandler,
                                       renderingState.isModelRenderingEnabled());
        ViewportControlsUI.ShowGridResult result = viewportControlsUI.render();

        // Apply changes
        if (result.showGridChanged) {
            this.viewportState = viewportState.toBuilder()
                .showGrid(result.newShowGridValue)
                .build();
        }

        if (result.modelRenderingEnabled != renderingState.isModelRenderingEnabled()) {
            renderingState.setModelRenderingEnabled(result.modelRenderingEnabled);
        }
    }

    /**
     * Show model controls window.
     */
    private void showModelControls() {
        modelControlsUI.updateState(
            renderingState.getCurrentModelName(),
            renderingState.getCurrentTextureVariant(),
            renderingState.getCurrentModel(),
            modelLoader.getCurrentLoadingFuture(),
                legacyCowModelRenderer
        );

        ModelControlsUI.ModelControlsResult result = modelControlsUI.render();

        // Handle model loading
        if (result.loadModelName != null) {
            loadModel(result.loadModelName);
        }

        // Handle model unload
        if (result.unloadModel) {
            unloadCurrentModel();
        }

        // Handle texture variant change
        if (result.newTextureVariant != null) {
            setCurrentTextureVariant(result.newTextureVariant);
        }
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
     * Unload current model.
     */
    private void unloadCurrentModel() {
        logger.info("Unloading current model: {}", renderingState.getCurrentModelName());

        // Cancel pending loading
        modelLoader.cancelLoading();

        // Clear model state
        renderingState.clearModel();

        logger.info("Model unloaded successfully");
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

        resourceManager.close();
        shaderManager.cleanup();

        this.viewportState = viewportState.toBuilder().initialized(false).build();

        logger.info("Viewport cleanup complete");
    }

    // ========== Public API (Backward Compatibility) ==========

    public Camera getCamera() { return camera; }
    public LegacyCowModelRenderer getModelRenderer() { return legacyCowModelRenderer; }
    public ViewportInputHandler getInputHandler() { return inputHandler; }

    public boolean isShowGrid() { return viewportState.isShowGrid(); }
    public void setShowGrid(boolean showGrid) {
        this.viewportState = viewportState.toBuilder().showGrid(showGrid).build();
    }

    public boolean isGridVisible() { return viewportState.isShowGrid(); }
    public void setGridVisible(boolean visible) { setShowGrid(visible); }

    public boolean isWireframeMode() { return viewportState.isWireframeMode(); }
    public void setWireframeMode(boolean wireframe) {
        this.viewportState = viewportState.toBuilder().wireframeMode(wireframe).build();
    }

    public boolean isAxesVisible() { return viewportState.isShowAxes(); }
    public void setAxesVisible(boolean visible) {
        this.viewportState = viewportState.toBuilder().showAxes(visible).build();
    }

    public int getColorTexture() { return resourceManager.getFramebuffer().getColorTextureId(); }
    public boolean isInitialized() { return viewportState.isInitialized(); }

    public String getCurrentModelName() { return renderingState.getCurrentModelName(); }
    public void setCurrentModelName(String modelName) {
        // Legacy method - use loadModel() instead
        loadModel(modelName);
    }

    public LegacyCowStonebreakModel getCurrentModel() { return renderingState.getCurrentModel(); }
    public void setCurrentModel(LegacyCowStonebreakModel model) {
        renderingState.setCurrentModel(model);
    }

    public String getCurrentTextureVariant() { return renderingState.getCurrentTextureVariant(); }

    public float getModelPositionX() { return transformState.getPositionX(); }
    public float getModelPositionY() { return transformState.getPositionY(); }
    public float getModelPositionZ() { return transformState.getPositionZ(); }
    public float getModelRotationX() { return transformState.getRotationX(); }
    public float getModelRotationY() { return transformState.getRotationY(); }
    public float getModelRotationZ() { return transformState.getRotationZ(); }
    public float getModelScale() { return transformState.getScale(); }
    public float getModelScaleX() { return transformState.getScaleX(); }
    public float getModelScaleY() { return transformState.getScaleY(); }
    public float getModelScaleZ() { return transformState.getScaleZ(); }
    public float getMinScale() { return TransformState.getMinScale(); }
    public float getMaxScale() { return TransformState.getMaxScale(); }

    public Matrix4f getUserTransformMatrix() { return transformState.getTransformMatrix(); }

    public boolean isDragging() { return inputHandler != null && inputHandler.isDragging(); }
    public boolean isMouseCaptured() { return inputHandler != null && inputHandler.isMouseCaptured(); }
    public boolean isRawMouseMotionSupported() { return inputHandler != null && inputHandler.isRawMouseMotionSupported(); }

    public void forceReleaseMouse() {
        if (inputHandler != null) {
            inputHandler.forceReleaseMouse();
        }
    }

    public void resetCamera() {
        if (camera != null) {
            camera.reset();
        }
    }

    public void update(float deltaTime) {
        if (camera != null) {
            camera.update(deltaTime);
        }
        if (inputHandler != null) {
            inputHandler.handleKeyboardInput(deltaTime);
        }
    }

    public void renderToFramebuffer(int width, int height) {
        int originalWidth = viewportState.getWidth();
        int originalHeight = viewportState.getHeight();

        try {
            resize(width, height);
            render();
        } finally {
            resize(originalWidth, originalHeight);
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

    public GizmoState getGizmoState() {
        return gizmoState;
    }

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

    public void cycleGizmoMode() {
        gizmoRenderer.cycleMode();
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
