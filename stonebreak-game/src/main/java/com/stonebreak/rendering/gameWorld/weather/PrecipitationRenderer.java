package com.stonebreak.rendering.gameWorld.weather;

import com.openmason.engine.rendering.RenderOrigin;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.rendering.shaders.ShaderResourceLoader;
import com.stonebreak.rendering.gameWorld.clouds.CloudUniforms;
import com.stonebreak.world.World;
import com.stonebreak.world.weather.WeatherSurface;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL13;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.glDrawArraysInstanced;

/**
 * Rain and snow falling from the clouds that make them. Lanes (thin streaks)
 * sit on a grid fixed in cloud space, so the whole pattern rides the wind with
 * the cloud; each lane asks the GPU cloud field ({@code cloudPrecipitation} in
 * cloud_field.glsl, the same field the clouds are drawn from) how much falls
 * at its own spot. So rain starts and stops exactly under the cloud, a cloud's
 * edge rains on part of a block, and small clouds drop less. All of it happens
 * in the vertex shader: one instanced draw, no per-frame instance data.
 *
 * <p>Ground height and snow come from {@link WeatherSurface} (loaded chunks
 * only) as a small texture around the camera, so the render thread never
 * waits on terrain generation.</p>
 */
public final class PrecipitationRenderer {

    /** Lanes drawn around the camera, in blocks. */
    static final int RADIUS = 20;
    /** How far below and above the camera lanes reach. */
    private static final float BELOW = 24f;
    private static final float ABOVE = 32f;
    /**
     * Blocks between lane slots in cloud space: four lanes per block, each
     * raining or not on its own. Divides the cloud map period, so the slot
     * hash repeats with the offset's wrap and no lane jumps.
     */
    private static final float LANE_SPACING = 0.5f;
    private static final int LANE_GRID = 2 * (int) Math.ceil(RADIUS / LANE_SPACING) + 1;
    private static final int LANE_PERIOD = (int) (CloudUniforms.MAP_PERIOD_BLOCKS / LANE_SPACING);
    /** Quad width of a lane, blocks: room for a streak, or a swaying flake. */
    private static final Vector2f LANE_WIDTH = new Vector2f(0.1f, 0.5f);

    /** Ground columns around the camera: the lanes' reach plus a block each side. */
    private static final int GROUND_SIZE = 2 * RADIUS + 3;
    /** Ground written where the chunk isn't loaded: below anything, so the lane stops at {@link #BELOW}. */
    private static final float GROUND_UNKNOWN = -1e6f;
    /** Clear of the cloud field's units (10, 15-17) and the cloud passes' (11-14). */
    private static final int GROUND_TEXTURE_UNIT = 18;

    static {
        if (CloudUniforms.MAP_PERIOD_BLOCKS % LANE_SPACING != 0f) {
            throw new IllegalStateException("LANE_SPACING must divide the cloud map period");
        }
    }

    private final ShaderProgram shader;
    private final int vao;
    private final int cornerVbo;
    private final int groundTexture;
    private final FloatBuffer ground = BufferUtils.createFloatBuffer(GROUND_SIZE * GROUND_SIZE * 2);
    private final Vector3f renderCamera = new Vector3f();
    private final Vector2f groundOrigin = new Vector2f();

    public PrecipitationRenderer() {
        shader = new ShaderProgram();
        shader.createVertexShader(load("/shaders/weather/precip.vert"));
        shader.createFragmentShader(load("/shaders/weather/precip.frag"));
        shader.link();

        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        cornerVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, cornerVbo);
        glBufferData(GL_ARRAY_BUFFER, new float[]{0, 0, 1, 0, 0, 1, 1, 1}, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        groundTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, groundTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RG32F, GROUND_SIZE, GROUND_SIZE, 0, GL_RG, GL_FLOAT, (FloatBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /** Lambda (not a method reference) so the caller-sensitive lookup happens in this module. */
    private static String load(String path) {
        return ShaderResourceLoader.load(path, p -> PrecipitationRenderer.class.getResourceAsStream(p));
    }

    /**
     * Draws this frame's rain and snow over the bound framebuffer (depth-tested,
     * no depth writes). {@code clouds} must hold this frame's cloud field
     * ({@code CloudRenderer.mapsReady()}).
     */
    public void render(World world, CloudUniforms clouds, Matrix4f projection, Matrix4f view,
                       Vector3f cameraWorldPos, float time, float ambientLight) {
        if (world == null || clouds == null) return;

        int previousUnit = glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + GROUND_TEXTURE_UNIT);
        uploadGround(world, cameraWorldPos);

        boolean depthTest = glIsEnabled(GL_DEPTH_TEST);
        boolean blend = glIsEnabled(GL_BLEND);
        boolean cull = glIsEnabled(GL_CULL_FACE);
        boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        int blendSrc = glGetInteger(GL_BLEND_SRC);
        int blendDst = glGetInteger(GL_BLEND_DST);

        glEnable(GL_DEPTH_TEST);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        shader.bind();
        clouds.applyTo(shader);
        shader.setMat4("uProjection", projection);
        shader.setMat4("uView", view);
        shader.setVec3("uCameraPos", RenderOrigin.toRender(cameraWorldPos, renderCamera));
        shader.setFloat("uLaneSpacing", LANE_SPACING);
        shader.setInt("uLaneGrid", LANE_GRID);
        shader.setInt("uLanePeriod", LANE_PERIOD);
        shader.setVec2("uLaneWidth", LANE_WIDTH);
        shader.setFloat("uRadius", RADIUS);
        shader.setFloat("uBelow", BELOW);
        shader.setFloat("uAbove", ABOVE);
        shader.setInt("uGround", GROUND_TEXTURE_UNIT);
        shader.setVec2("uGroundOrigin", groundOrigin);
        shader.setFloat("uTime", time);
        shader.setFloat("uAmbient", ambientLight);
        glBindVertexArray(vao);
        glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, LANE_GRID * LANE_GRID);
        glBindVertexArray(0);
        shader.unbind();
        GL13.glActiveTexture(previousUnit);

        glBlendFunc(blendSrc, blendDst);
        if (!blend) glDisable(GL_BLEND);
        if (!depthTest) glDisable(GL_DEPTH_TEST);
        if (cull) glEnable(GL_CULL_FACE);
        glDepthMask(depthMask);
    }

    /** Fills and binds the ground texture around the camera (active unit). */
    private void uploadGround(World world, Vector3f cam) {
        int x0 = (int) Math.floor(cam.x) - RADIUS - 1;
        int z0 = (int) Math.floor(cam.z) - RADIUS - 1;
        ground.clear();
        for (int j = 0; j < GROUND_SIZE; j++) {
            for (int i = 0; i < GROUND_SIZE; i++) {
                int y = WeatherSurface.groundY(world, x0 + i, z0 + j);
                ground.put(y == WeatherSurface.UNKNOWN ? GROUND_UNKNOWN : y)
                        .put(WeatherSurface.isSnow(world, x0 + i, z0 + j) ? 1f : 0f);
            }
        }
        ground.flip();
        glBindTexture(GL_TEXTURE_2D, groundTexture);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, GROUND_SIZE, GROUND_SIZE, GL_RG, GL_FLOAT, ground);
        groundOrigin.set((float) (x0 - (double) RenderOrigin.x()), (float) (z0 - (double) RenderOrigin.z()));
    }

    public void cleanup() {
        shader.cleanup();
        glDeleteBuffers(cornerVbo);
        glDeleteVertexArrays(vao);
        glDeleteTextures(groundTexture);
    }
}
