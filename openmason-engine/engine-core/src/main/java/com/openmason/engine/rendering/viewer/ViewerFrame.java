package com.openmason.engine.rendering.viewer;

import com.openmason.engine.rendering.shaders.ShaderManager;

/**
 * Everything a {@link ViewerPass} needs for one frame.
 *
 * @param context   camera matrices and viewport dimensions
 * @param shaders   shader programs, shared across all passes of this viewer
 * @param settings  display settings pushed by the host this frame
 * @param width     framebuffer width in pixels
 * @param height    framebuffer height in pixels
 * @param deltaTime seconds since the previous frame
 */
public record ViewerFrame(ViewerRenderContext context,
                          ShaderManager shaders,
                          ViewerSettings settings,
                          int width,
                          int height,
                          float deltaTime) {
}
