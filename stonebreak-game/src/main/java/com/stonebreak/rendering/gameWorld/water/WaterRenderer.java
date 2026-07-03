package com.stonebreak.rendering.gameWorld.water;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.stonebreak.world.chunk.Chunk;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL30;

import static org.lwjgl.opengl.GL11.*;

/**
 * Dedicated water renderer — draws every chunk's water mesh with its own
 * shader in a single pass, replacing the old approach of redrawing the whole
 * chunk VAO with per-fragment water discards (world shader
 * {@code u_renderPass}/{@code u_translucentLayer}).
 *
 * <p>Water geometry is built into a separate per-chunk mesh by
 * {@code MmsCcoAdapter} (see the water-mesh attribute semantics documented in
 * {@code shaders/water/water.vert}) and swapped by {@code MmsMeshPipeline}
 * alongside the atlas handle. The surface pattern is fully procedural in the
 * fragment shader — no texture binds, no CPU tile animation.
 *
 * <p>Invoked by {@code WorldRenderer} immediately after the transparent
 * (ice/translucent-solid) pass, before transparent drops — the same
 * compositing slot the old water sub-pass occupied. Follows the engine
 * {@code CloudRenderer} pattern: owns its shader, saves/restores all GL state
 * it touches. Face culling is disabled so the surface is visible from below
 * ({@code gl_FrontFacing} flips the normal in the fragment shader).
 */
public class WaterRenderer {

    private final ShaderProgram shader;

    public WaterRenderer() {
        shader = new ShaderProgram();
        try {
            shader.createVertexShader(loadShaderSource("/shaders/water/water.vert"));
            shader.createFragmentShader(loadShaderSource("/shaders/water/water.frag"));
            shader.link();

            shader.createUniform("uProjection");
            shader.createUniform("uView");
            shader.createUniform("uTime");
            shader.createUniform("uWavesEnabled");
            shader.createUniform("uSunDirection");
            shader.createUniform("uAmbientLight");
            shader.createUniform("uCameraPos");
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize water shader", e);
        }
    }

    private String loadShaderSource(String resourcePath) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Shader resource not found: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Draws the water meshes of the given chunks (expected back-to-front —
     * reuse the transparent pass's sorted list).
     *
     * @param chunksBackToFront chunks sorted farthest-first
     * @param projection        camera projection matrix
     * @param view              camera view matrix
     * @param cameraPos         camera world position
     * @param time              total elapsed time in seconds (drives waves/flow)
     * @param sunDirection      normalized sun direction (world shader convention)
     * @param ambientLight      ambient light level 0..1 from TimeOfDay
     * @param wavesEnabled      water animation setting (freezes waves + scroll)
     */
    public void render(List<Chunk> chunksBackToFront, Matrix4f projection, Matrix4f view,
                       Vector3f cameraPos, float time, Vector3f sunDirection,
                       float ambientLight, boolean wavesEnabled) {
        // Save GL state we touch.
        boolean depthTestEnabled = glIsEnabled(GL_DEPTH_TEST);
        boolean cullFaceEnabled = glIsEnabled(GL_CULL_FACE);
        boolean blendEnabled = glIsEnabled(GL_BLEND);
        boolean depthMaskEnabled = glGetBoolean(GL_DEPTH_WRITEMASK);
        int currentDepthFunc = glGetInteger(GL_DEPTH_FUNC);
        int currentBlendSrc = glGetInteger(GL_BLEND_SRC);
        int currentBlendDst = glGetInteger(GL_BLEND_DST);

        // Translucent water: depth-test against the world, no depth writes,
        // standard alpha blend, no culling (underwater views see back faces).
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        shader.bind();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uView", view);
        shader.setUniform("uTime", time);
        shader.setUniform("uWavesEnabled", wavesEnabled);
        shader.setUniform("uSunDirection", sunDirection);
        shader.setUniform("uAmbientLight", ambientLight);
        shader.setUniform("uCameraPos", cameraPos);

        for (Chunk chunk : chunksBackToFront) {
            if (chunk.hasWaterMesh()) {
                chunk.renderWater();
            }
        }
        GL30.glBindVertexArray(0);

        shader.unbind();

        // Restore GL state.
        if (depthTestEnabled) {
            glEnable(GL_DEPTH_TEST);
        } else {
            glDisable(GL_DEPTH_TEST);
        }
        if (cullFaceEnabled) {
            glEnable(GL_CULL_FACE);
        } else {
            glDisable(GL_CULL_FACE);
        }
        if (blendEnabled) {
            glEnable(GL_BLEND);
            glBlendFunc(currentBlendSrc, currentBlendDst);
        } else {
            glDisable(GL_BLEND);
        }
        glDepthMask(depthMaskEnabled);
        glDepthFunc(currentDepthFunc);
    }

    /** Releases the shader. Water mesh handles are owned by their chunks. */
    public void cleanup() {
        if (shader != null) {
            shader.cleanup();
        }
    }
}
