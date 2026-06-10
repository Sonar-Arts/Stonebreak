package com.openmason.engine.rendering.postfx;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL30.*;

/**
 * Coordinates offscreen scene rendering and screen-space post-processing effects.
 *
 * <p>Usage per frame: {@link #beginFrame()} → render the 3D scene normally →
 * {@link #endFrame(PostFxFrameParams)}. {@code endFrame} blits the scene (color + depth) to the
 * default framebuffer, then lets each registered {@link PostProcessingEffect} composite on top.
 * Additive effects (god rays, bloom) draw straight onto the default framebuffer; a future
 * full-chain effect (color grading, SSAO) would upgrade this to ping-pong targets without
 * changing the effect interface.</p>
 *
 * <p>After {@code endFrame} returns, GL state matches what overlay/UI code expects following a
 * world render: default framebuffer bound, window viewport, depth test on (GL_LESS), depth
 * writes on, blending off, no program/VAO/texture bound.</p>
 */
public class PostProcessingPipeline {

    private static final Logger LOGGER = Logger.getLogger(PostProcessingPipeline.class.getName());

    private final RenderTarget sceneTarget;
    private final FullscreenQuad quad;
    private final List<PostProcessingEffect> effects = new ArrayList<>();

    /**
     * Whether the combined color+depth blit to the default framebuffer is supported.
     * Depth blits fail with GL_INVALID_OPERATION when the default framebuffer's depth format
     * differs from the scene target's; in that case only color is blitted from then on.
     */
    private boolean depthBlitSupported = true;
    private boolean depthBlitProbed = false;

    public PostProcessingPipeline(int width, int height) {
        sceneTarget = new RenderTarget(width, height, true);
        quad = new FullscreenQuad();
    }

    /**
     * Registers an effect, initializing its GPU resources at the current output size.
     */
    public void addEffect(PostProcessingEffect effect) {
        effect.init(sceneTarget.getWidth(), sceneTarget.getHeight());
        effects.add(effect);
    }

    /**
     * Binds the scene framebuffer and clears it. Call before rendering the 3D scene.
     */
    public void beginFrame() {
        sceneTarget.bind();
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }

    /**
     * Blits the scene to the default framebuffer, runs all effects, and restores the GL state
     * contract expected by overlay/UI rendering.
     */
    public void endFrame(PostFxFrameParams params) {
        int width = sceneTarget.getWidth();
        int height = sceneTarget.getHeight();

        blitSceneToDefaultFramebuffer(width, height);

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, width, height);

        for (PostProcessingEffect effect : effects) {
            effect.apply(sceneTarget.getColorTexture(), sceneTarget.getDepthTexture(),
                    width, height, quad, params);
        }

        // State contract: leave GL exactly as overlay/UI code expects after a world render.
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, width, height);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);
        glDepthMask(true);
        glDisable(GL_BLEND);
        glUseProgram(0);
        glBindVertexArray(0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    private void blitSceneToDefaultFramebuffer(int width, int height) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, sceneTarget.getFboId());
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);

        if (!depthBlitProbed) {
            // Drain any pre-existing error so the probe below is unambiguous.
            while (glGetError() != GL_NO_ERROR) {
                // discard
            }
        }

        int mask = depthBlitSupported
                ? GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT
                : GL_COLOR_BUFFER_BIT;
        glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, mask, GL_NEAREST);

        if (!depthBlitProbed) {
            depthBlitProbed = true;
            if (glGetError() != GL_NO_ERROR) {
                // Depth format mismatch with the default framebuffer — retry color-only and
                // skip depth from now on. Downstream depth-dependent draws (pause-menu depth
                // curtain) then see the pre-frame default depth buffer.
                depthBlitSupported = false;
                LOGGER.log(Level.WARNING,
                        "Combined color+depth blit rejected by driver; falling back to color-only blits");
                glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                        GL_COLOR_BUFFER_BIT, GL_NEAREST);
            }
        }
    }

    /**
     * Resizes the scene target and all effects. Safe to call with zero dimensions
     * (minimized window) — the call is ignored.
     */
    public void resize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        sceneTarget.resize(width, height);
        for (PostProcessingEffect effect : effects) {
            effect.resize(width, height);
        }
    }

    public void cleanup() {
        for (PostProcessingEffect effect : effects) {
            effect.cleanup();
        }
        effects.clear();
        quad.cleanup();
        sceneTarget.cleanup();
    }
}
