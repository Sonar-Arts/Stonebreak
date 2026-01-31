package com.openmason.main.systems.rendering.api;

import com.openmason.main.systems.rendering.core.shaders.ShaderProgram;
import com.openmason.main.systems.viewport.viewportRendering.RenderContext;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Master coordinator for all renderers.
 * Single source of truth for renderer lifecycle and execution order.
 *
 * Responsibilities:
 * - Register/unregister renderers
 * - Initialize all renderers
 * - Execute render passes in correct order
 * - Cleanup all renderers
 *
 * SOLID Compliance:
 * - Single Responsibility: Only coordinates renderers
 * - Open/Closed: New renderers via registration, not modification
 * - Dependency Inversion: Depends on IRenderer, not concrete types
 */
public class RenderingController {

    private static final Logger logger = LoggerFactory.getLogger(RenderingController.class);

    // Renderers organized by pass for ordered execution
    private final EnumMap<RenderPass, List<IRenderer>> renderersByPass = new EnumMap<>(RenderPass.class);

    // Quick lookup by type
    private final Map<Class<? extends IRenderer>, IRenderer> renderersByType = new HashMap<>();

    // State
    private boolean initialized = false;

    /**
     * Create a new RenderingController.
     */
    public RenderingController() {
        // Initialize lists for each pass
        for (RenderPass pass : RenderPass.values()) {
            renderersByPass.put(pass, new ArrayList<>());
        }
    }

    /**
     * Register a renderer with the controller.
     * Renderer will be initialized on next initialize() call or immediately if already initialized.
     *
     * @param renderer The renderer to register
     */
    public void registerRenderer(IRenderer renderer) {
        if (renderer == null) {
            logger.warn("Cannot register null renderer");
            return;
        }

        RenderPass pass = renderer.getRenderPass();
        List<IRenderer> passRenderers = renderersByPass.get(pass);

        // Check for duplicates
        if (passRenderers.contains(renderer)) {
            logger.warn("Renderer {} already registered", renderer.getDebugName());
            return;
        }

        passRenderers.add(renderer);
        renderersByType.put(renderer.getClass(), renderer);

        logger.debug("Registered renderer: {} (pass: {})", renderer.getDebugName(), pass);

        // Initialize immediately if controller is already initialized
        if (initialized && !renderer.isInitialized()) {
            try {
                renderer.initialize();
            } catch (Exception e) {
                logger.error("Failed to initialize renderer: {}", renderer.getDebugName(), e);
            }
        }
    }

    /**
     * Unregister a renderer from the controller.
     * Renderer resources are NOT cleaned up - caller is responsible.
     *
     * @param renderer The renderer to unregister
     */
    public void unregisterRenderer(IRenderer renderer) {
        if (renderer == null) {
            return;
        }

        RenderPass pass = renderer.getRenderPass();
        List<IRenderer> passRenderers = renderersByPass.get(pass);

        if (passRenderers.remove(renderer)) {
            renderersByType.remove(renderer.getClass());
            logger.debug("Unregistered renderer: {}", renderer.getDebugName());
        }
    }

    /**
     * Initialize all registered renderers.
     */
    public void initialize() {
        if (initialized) {
            logger.debug("RenderingController already initialized");
            return;
        }

        logger.info("Initializing RenderingController with {} renderers...", getTotalRendererCount());

        int successCount = 0;
        int failCount = 0;

        // Initialize renderers in pass order
        for (RenderPass pass : RenderPass.values()) {
            for (IRenderer renderer : renderersByPass.get(pass)) {
                if (!renderer.isInitialized()) {
                    try {
                        renderer.initialize();
                        successCount++;
                    } catch (Exception e) {
                        logger.error("Failed to initialize renderer: {}", renderer.getDebugName(), e);
                        failCount++;
                    }
                } else {
                    successCount++;
                }
            }
        }

        initialized = true;
        logger.info("RenderingController initialized - {} succeeded, {} failed", successCount, failCount);
    }

    /**
     * Execute all render passes in order.
     * Provides shader and model matrix for renderers that need external resources.
     *
     * @param context The render context
     * @param modelMatrix The model transformation matrix
     * @param shaderProvider Function to get shader for each pass (can return null to skip)
     */
    public void render(RenderContext context, Matrix4f modelMatrix, ShaderProvider shaderProvider) {
        if (!initialized) {
            logger.warn("RenderingController not initialized");
            return;
        }

        // Execute passes in order
        for (RenderPass pass : RenderPass.values()) {
            ShaderProgram shader = shaderProvider.getShaderForPass(pass);

            // Skip pass if no shader provided
            if (shader == null) {
                continue;
            }

            for (IRenderer renderer : renderersByPass.get(pass)) {
                if (renderer.isEnabled() && renderer.isInitialized()) {
                    try {
                        renderer.render(shader, context, modelMatrix);
                    } catch (Exception e) {
                        logger.error("Error rendering {} (pass: {})", renderer.getDebugName(), pass, e);
                    }
                }
            }
        }
    }

    /**
     * Cleanup all registered renderers.
     */
    public void cleanup() {
        logger.info("Cleaning up RenderingController...");

        int cleanupCount = 0;

        // Cleanup in reverse pass order (UI/DEBUG first, BACKGROUND last)
        RenderPass[] passes = RenderPass.values();
        for (int i = passes.length - 1; i >= 0; i--) {
            RenderPass pass = passes[i];
            for (IRenderer renderer : renderersByPass.get(pass)) {
                if (renderer.isInitialized()) {
                    try {
                        renderer.cleanup();
                        cleanupCount++;
                    } catch (Exception e) {
                        logger.error("Error cleaning up renderer: {}", renderer.getDebugName(), e);
                    }
                }
            }
        }

        // Clear collections
        for (List<IRenderer> list : renderersByPass.values()) {
            list.clear();
        }
        renderersByType.clear();

        initialized = false;
        logger.info("RenderingController cleanup complete - {} renderers cleaned", cleanupCount);
    }

    /**
     * Get a renderer by its type.
     *
     * @param type The renderer class
     * @param <T> The renderer type
     * @return The renderer instance, or null if not found
     */
    @SuppressWarnings("unchecked")
    public <T extends IRenderer> T getRenderer(Class<T> type) {
        return (T) renderersByType.get(type);
    }

    /**
     * Get all renderers for a specific pass.
     *
     * @param pass The render pass
     * @return Unmodifiable list of renderers
     */
    public List<IRenderer> getRenderersForPass(RenderPass pass) {
        return Collections.unmodifiableList(renderersByPass.get(pass));
    }

    /**
     * Get total number of registered renderers.
     *
     * @return Total renderer count
     */
    public int getTotalRendererCount() {
        return renderersByType.size();
    }

    /**
     * Check if controller is initialized.
     *
     * @return true if initialized
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Functional interface for providing shaders per render pass.
     */
    @FunctionalInterface
    public interface ShaderProvider {
        /**
         * Get the shader to use for a render pass.
         *
         * @param pass The render pass
         * @return ShaderProgram to use, or null to skip the pass
         */
        ShaderProgram getShaderForPass(RenderPass pass);
    }
}
