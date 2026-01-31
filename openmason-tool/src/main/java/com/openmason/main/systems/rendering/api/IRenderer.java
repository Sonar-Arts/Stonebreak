package com.openmason.main.systems.rendering.api;

import com.openmason.main.systems.rendering.core.shaders.ShaderProgram;
import com.openmason.main.systems.viewport.viewportRendering.RenderContext;
import org.joml.Matrix4f;

/**
 * Core renderer contract for all viewport renderers.
 * Defines the essential lifecycle and rendering methods.
 *
 * All implementations must support:
 * - Initialization: Create GPU resources (VAO, VBO, EBO)
 * - Rendering: Execute draw calls with proper state
 * - Cleanup: Release GPU resources
 *
 * SOLID Compliance:
 * - Single Responsibility: Only defines rendering contract
 * - Interface Segregation: Minimal, essential methods only
 * - Dependency Inversion: Consumers depend on this interface
 */
public interface IRenderer {

    /**
     * Initialize GPU resources.
     * Creates VAO, VBO, EBO and configures vertex attributes.
     * Must be called once before first render.
     */
    void initialize();

    /**
     * Check if renderer is initialized.
     *
     * @return true if initialize() has been called successfully
     */
    boolean isInitialized();

    /**
     * Check if renderer is enabled.
     *
     * @return true if renderer should participate in rendering
     */
    boolean isEnabled();

    /**
     * Enable or disable this renderer.
     *
     * @param enabled true to enable, false to disable
     */
    void setEnabled(boolean enabled);

    /**
     * Execute rendering with the given shader, context, and transform.
     *
     * @param shader The shader program to use
     * @param context The render context with camera and viewport info
     * @param modelMatrix The model transformation matrix
     */
    void render(ShaderProgram shader, RenderContext context, Matrix4f modelMatrix);

    /**
     * Release all GPU resources.
     * Must be called before application shutdown.
     */
    void cleanup();

    /**
     * Get a debug name for this renderer.
     * Used for logging and debugging.
     *
     * @return Human-readable renderer name
     */
    String getDebugName();

    /**
     * Get the render pass this renderer belongs to.
     * Determines execution order in the pipeline.
     *
     * @return The render pass
     */
    RenderPass getRenderPass();
}
