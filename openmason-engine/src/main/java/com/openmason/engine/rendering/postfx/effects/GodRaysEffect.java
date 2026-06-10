package com.openmason.engine.rendering.postfx.effects;

import org.joml.Vector2f;
import org.joml.Vector4f;

import com.openmason.engine.rendering.postfx.FullscreenQuad;
import com.openmason.engine.rendering.postfx.PostFxFrameParams;
import com.openmason.engine.rendering.postfx.PostProcessingEffect;
import com.openmason.engine.rendering.postfx.RenderTarget;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.rendering.shaders.ShaderResourceLoader;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;

/**
 * Screen-space god rays (crepuscular rays) via radial blur, after GPU Gems 3 ch. 13.
 *
 * <p>Pass A renders a half-resolution occlusion mask: a sun glow where the scene depth equals
 * the far plane (the sky dome writes depth exactly 1.0), black where geometry occludes the sun.
 * Pass B radially blurs the mask toward the sun's projected screen position and composites it
 * additively onto the default framebuffer.</p>
 *
 * <p>Known, accepted behavior: water and clouds do not write depth, so rays shine through
 * both. The effect fades out as the sun approaches the screen edge and is skipped entirely
 * when the sun is behind the camera.</p>
 */
public class GodRaysEffect implements PostProcessingEffect {

    // Radial blur tuning (see godrays_blur.frag)
    private static final float DECAY = 0.95f;
    private static final float DENSITY = 0.9f;
    private static final float WEIGHT = 0.06f;
    private static final float BASE_EXPOSURE = 0.8f;

    // Edge fade: full strength while max(|ndc|) < EDGE_FADE_START, zero at EDGE_FADE_END
    private static final float EDGE_FADE_START = 0.45f;
    private static final float EDGE_FADE_END = 0.95f;

    private static final float MIN_STRENGTH = 0.001f;

    private RenderTarget occlusionTarget;
    private ShaderProgram occlusionProgram;
    private ShaderProgram blurProgram;

    private final Vector4f sunViewSpace = new Vector4f();
    private final Vector2f sunUv = new Vector2f();

    @Override
    public void init(int width, int height) {
        occlusionTarget = new RenderTarget(Math.max(1, width / 2), Math.max(1, height / 2), false);

        String fullscreenVert = ShaderResourceLoader.load("/shaders/postfx/fullscreen.vert");

        occlusionProgram = new ShaderProgram();
        occlusionProgram.createVertexShader(fullscreenVert);
        occlusionProgram.createFragmentShader(ShaderResourceLoader.load("/shaders/postfx/godrays_occlusion.frag"));
        occlusionProgram.link();
        occlusionProgram.createUniform("depthTex");
        occlusionProgram.createUniform("sunUv");
        occlusionProgram.createUniform("aspect");

        blurProgram = new ShaderProgram();
        blurProgram.createVertexShader(fullscreenVert);
        blurProgram.createFragmentShader(ShaderResourceLoader.load("/shaders/postfx/godrays_blur.frag"));
        blurProgram.link();
        blurProgram.createUniform("occlusionTex");
        blurProgram.createUniform("sunUv");
        blurProgram.createUniform("exposure");
        blurProgram.createUniform("decay");
        blurProgram.createUniform("density");
        blurProgram.createUniform("weight");
    }

    @Override
    public void resize(int width, int height) {
        occlusionTarget.resize(Math.max(1, width / 2), Math.max(1, height / 2));
    }

    @Override
    public void apply(int sceneColorTexture, int sceneDepthTexture, int outputWidth, int outputHeight,
                      FullscreenQuad quad, PostFxFrameParams params) {
        if (sceneDepthTexture == 0 || params.effectStrength() <= MIN_STRENGTH) {
            return;
        }

        // Project the sun direction (a direction at infinity, w = 0) into view space.
        // The view matrix's translation is irrelevant for w = 0 vectors.
        sunViewSpace.set(params.sunDirection(), 0.0f).mul(params.viewMatrix());
        if (sunViewSpace.z >= 0.0f) {
            return; // Sun is behind the camera (GL view space looks down -Z).
        }

        // Project to clip space; with w = 0 the perspective divide uses -viewZ.
        params.projectionMatrix().transform(sunViewSpace);
        if (sunViewSpace.w <= 0.0f) {
            return;
        }
        float ndcX = sunViewSpace.x / sunViewSpace.w;
        float ndcY = sunViewSpace.y / sunViewSpace.w;

        float edgeDistance = Math.max(Math.abs(ndcX), Math.abs(ndcY));
        float edgeFade = 1.0f - smoothstep(EDGE_FADE_START, EDGE_FADE_END, edgeDistance);
        float strength = params.effectStrength() * edgeFade;
        if (strength <= MIN_STRENGTH) {
            return;
        }

        sunUv.set(ndcX * 0.5f + 0.5f, ndcY * 0.5f + 0.5f);

        boolean depthTestWasEnabled = glIsEnabled(GL_DEPTH_TEST);
        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);

        // Pass A: half-resolution occlusion mask from scene depth.
        occlusionTarget.bind();
        glClear(GL_COLOR_BUFFER_BIT);
        occlusionProgram.bind();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, sceneDepthTexture);
        occlusionProgram.setUniform("depthTex", 0);
        occlusionProgram.setUniform("sunUv", sunUv);
        occlusionProgram.setUniform("aspect", (float) outputWidth / (float) outputHeight);
        quad.draw();

        // Pass B: radial blur, composited additively onto the default framebuffer.
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, outputWidth, outputHeight);
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);
        blurProgram.bind();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, occlusionTarget.getColorTexture());
        blurProgram.setUniform("occlusionTex", 0);
        blurProgram.setUniform("sunUv", sunUv);
        blurProgram.setUniform("exposure", BASE_EXPOSURE * strength);
        blurProgram.setUniform("decay", DECAY);
        blurProgram.setUniform("density", DENSITY);
        blurProgram.setUniform("weight", WEIGHT);
        quad.draw();

        blurProgram.unbind();
        glBindTexture(GL_TEXTURE_2D, 0);
        glDisable(GL_BLEND);
        glDepthMask(true);
        if (depthTestWasEnabled) {
            glEnable(GL_DEPTH_TEST);
        }
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    @Override
    public void cleanup() {
        if (occlusionProgram != null) {
            occlusionProgram.cleanup();
            occlusionProgram = null;
        }
        if (blurProgram != null) {
            blurProgram.cleanup();
            blurProgram = null;
        }
        if (occlusionTarget != null) {
            occlusionTarget.cleanup();
            occlusionTarget = null;
        }
    }
}
