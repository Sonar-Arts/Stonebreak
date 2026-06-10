package com.openmason.engine.rendering.postfx;

/**
 * A screen-space effect executed by {@link PostProcessingPipeline} after the scene has been
 * rendered and blitted to the default framebuffer.
 *
 * <p>Effects composite their result directly onto the currently bound output framebuffer
 * (typically additively). They may bind their own intermediate {@link RenderTarget}s during
 * {@code apply()}, but must leave the default framebuffer bound with the full window viewport
 * when they return.</p>
 */
public interface PostProcessingEffect {

    /**
     * Creates GPU resources. Called once on the main thread, with a current GL context,
     * before the first {@link #apply}.
     */
    void init(int width, int height);

    /**
     * Notifies the effect that the output resolution changed so it can resize intermediate
     * buffers.
     */
    void resize(int width, int height);

    /**
     * Runs the effect.
     *
     * @param sceneColorTexture color texture of the rendered scene
     * @param sceneDepthTexture depth texture of the rendered scene (0 if unavailable)
     * @param outputWidth       width of the output framebuffer
     * @param outputHeight      height of the output framebuffer
     * @param quad              shared fullscreen triangle for screen-space passes
     * @param params            per-frame parameters from the game
     */
    void apply(int sceneColorTexture, int sceneDepthTexture, int outputWidth, int outputHeight,
               FullscreenQuad quad, PostFxFrameParams params);

    /**
     * Releases GPU resources. Called on the main thread during renderer shutdown.
     */
    void cleanup();
}
