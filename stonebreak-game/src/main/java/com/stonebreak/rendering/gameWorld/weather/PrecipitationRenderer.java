package com.stonebreak.rendering.gameWorld.weather;

import com.openmason.engine.rendering.RenderOrigin;
import com.openmason.engine.rendering.postfx.FullscreenQuad;
import com.openmason.engine.rendering.postfx.RenderTarget;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.rendering.shaders.ShaderResourceLoader;
import com.stonebreak.rendering.gameWorld.clouds.CloudRenderer;
import com.stonebreak.rendering.gameWorld.clouds.CloudUniforms;
import com.stonebreak.rendering.gameWorld.clouds.CloudsInFront;
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
 * at its own spot, and falls from the underside of the lowest cloud drawn
 * above it. So rain starts and stops exactly under the cloud, a cloud's edge
 * rains on part of a block, and small clouds drop less.
 *
 * <p>Two rings of lanes: a dense one around the camera, and a sparse one out to
 * {@link #FAR} that shows the rain hanging under distant clouds. Each frame,
 * per ring, one fragment per lane asks the cloud field
 * ({@code precip_lane_field.frag}), then one instanced draw reads the answers.</p>
 *
 * <p>Ground height and snow near the camera come from {@link WeatherSurface}
 * (loaded chunks only) as a small texture, so the render thread never waits on
 * terrain generation; far lanes take the coarse cloud relief and the snow
 * around the camera.</p>
 */
public final class PrecipitationRenderer {

    /**
     * A ring of lanes.
     *
     * @param spacing blocks between lane slots in cloud space; divides the
     *                cloud map period, so the slot hash repeats with the
     *                offset's wrap and no lane jumps
     * @param radius  lanes reach this far from the camera
     * @param inner   and start this far out
     * @param fadeIn  lanes fade in from {@code inner} to here
     * @param below   and fall no further than this below the camera
     * @param width   quad width, blocks: rain, snow
     * @param far     ground from the cloud relief rather than the world
     */
    record Ring(float spacing, float radius, float inner, float fadeIn, float below, Vector2f width, boolean far) {
        int grid() {
            return 2 * (int) Math.ceil(radius / spacing) + 1;
        }

        int period() {
            return (int) (CloudUniforms.MAP_PERIOD_BLOCKS / spacing);
        }

        /** Near lanes each far lane stands in for. */
        float density() {
            return (spacing * spacing) / (NEAR.spacing * NEAR.spacing);
        }
    }

    /** Four lanes per block, each raining or not on its own. */
    static final Ring NEAR = new Ring(0.5f, 20f, 0f, 0f, 24f, new Vector2f(0.1f, 0.5f), false);
    /**
     * Out to where the rain under a cloud still reads as rain. It takes over
     * across the near ring's fade, so the two sum to one there.
     */
    static final Ring FAR = new Ring(3f, 192f, 0.6f * NEAR.radius, NEAR.radius, 128f, new Vector2f(0.1f, 0.5f), true);
    private static final Ring[] RINGS = {NEAR, FAR};

    /** Ground columns around the camera: the near lanes' reach plus a block each side. */
    private static final int GROUND_SIZE = 2 * (int) NEAR.radius + 3;
    /** Ground written where the chunk isn't loaded: below anything, so the lane stops at {@link Ring#below}. */
    private static final float GROUND_UNKNOWN = -1e6f;
    /** Clear of the cloud field's units (10, 15-17) and the cloud passes' (11-14). */
    private static final int GROUND_TEXTURE_UNIT = 18;
    /** The ring's lane answers, read by the draw; clear of every unit above. */
    private static final int LANES_TEXTURE_UNIT = 22;

    static {
        for (Ring ring : RINGS) {
            if (CloudUniforms.MAP_PERIOD_BLOCKS % ring.spacing() != 0f) {
                throw new IllegalStateException("lane spacing " + ring.spacing() + " must divide the cloud map period");
            }
        }
    }

    private final ShaderProgram fieldShader;
    private final ShaderProgram shader;
    private final FullscreenQuad quad = new FullscreenQuad();
    private final RenderTarget[] lanes = new RenderTarget[RINGS.length];
    private final int vao;
    private final int cornerVbo;
    private final int groundTexture;
    private final FloatBuffer ground = BufferUtils.createFloatBuffer(GROUND_SIZE * GROUND_SIZE * 2);
    private final Vector3f renderCamera = new Vector3f();
    private final Vector2f groundOrigin = new Vector2f();
    private final Vector2f fade = new Vector2f();
    private final int[] viewport = new int[4];

    public PrecipitationRenderer() {
        fieldShader = new ShaderProgram();
        fieldShader.createVertexShader(load("/shaders/postfx/fullscreen.vert"));
        fieldShader.createFragmentShader(load("/shaders/weather/precip_lane_field.frag"));
        fieldShader.link();
        shader = new ShaderProgram();
        shader.createVertexShader(load("/shaders/weather/precip.vert"));
        shader.createFragmentShader(load("/shaders/weather/precip.frag"));
        shader.link();

        for (int i = 0; i < RINGS.length; i++) {
            lanes[i] = new RenderTarget(RINGS[i].grid(), RINGS[i].grid(), false, GL_RGBA32F);
        }

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
     * no depth writes), behind any cloud drawn nearer than a lane. {@code clouds}
     * must hold this frame's cloud field ({@code CloudRenderer.mapsReady()}).
     *
     * @param snow share of the ground around the camera where it snows (far lanes)
     * @param fog  the rain fog between the eye and each lane
     */
    public void render(World world, CloudRenderer cloudRenderer, PrecipitationFog fog, Matrix4f projection,
                       Matrix4f view, Vector3f cameraWorldPos, float time, float ambientLight, float snow) {
        if (world == null || cloudRenderer == null) return;
        CloudUniforms clouds = cloudRenderer.uniforms();

        int previousFbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadFbo = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        glGetIntegerv(GL_VIEWPORT, viewport);
        boolean depthTest = glIsEnabled(GL_DEPTH_TEST);
        boolean blend = glIsEnabled(GL_BLEND);
        boolean cull = glIsEnabled(GL_CULL_FACE);
        boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        int blendSrc = glGetInteger(GL_BLEND_SRC);
        int blendDst = glGetInteger(GL_BLEND_DST);
        int previousUnit = glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + LANES_TEXTURE_UNIT);
        int previousLanes = glGetInteger(GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + GROUND_TEXTURE_UNIT);
        int previousGround = glGetInteger(GL_TEXTURE_BINDING_2D);
        uploadGround(world, cameraWorldPos);
        RenderOrigin.toRender(cameraWorldPos, renderCamera);

        // Pass 1: what each lane of each ring sees above it.
        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE);
        fieldShader.bind();
        clouds.applyTo(fieldShader);
        clouds.applyShapeTo(fieldShader);
        fieldShader.setInt("uGround", GROUND_TEXTURE_UNIT);
        fieldShader.setVec2("uGroundOrigin", groundOrigin);
        fieldShader.setFloat("uSnow", snow);
        for (int i = 0; i < RINGS.length; i++) {
            Ring ring = RINGS[i];
            lanes[i].bind();
            applyLanes(fieldShader, ring);
            fieldShader.setFloat("uRadius", ring.radius());
            fieldShader.setFloat("uInnerRadius", ring.inner());
            fieldShader.setFloat("uBelow", ring.below());
            fieldShader.setBool("uFar", ring.far());
            quad.draw();
        }
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousFbo);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, previousReadFbo);
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);

        // Pass 2: the lanes, over the scene.
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        shader.bind();
        CloudsInFront.bindSampler(shader);
        CloudsInFront.Bindings previousClouds = CloudsInFront.enable(shader, cloudRenderer);
        shader.setVec2("u_cloudOffset", clouds.cloudOffset());
        fog.applyTo(shader);
        shader.setMat4("uProjection", projection);
        shader.setMat4("uView", view);
        // Radians per pixel: the projection's vertical scale is 1 / tan(fov / 2).
        shader.setFloat("uPixelAngle", 2f / (projection.m11() * Math.max(1, viewport[3])));
        shader.setInt("uLanes", LANES_TEXTURE_UNIT);
        shader.setFloat("uTime", time);
        shader.setFloat("uAmbient", ambientLight);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + LANES_TEXTURE_UNIT);
        glBindVertexArray(vao);
        for (int i = 0; i < RINGS.length; i++) {
            Ring ring = RINGS[i];
            glBindTexture(GL_TEXTURE_2D, lanes[i].getColorTexture());
            applyLanes(shader, ring);
            shader.setVec2("uLaneWidth", ring.width());
            shader.setVec2("uFade", ring.inner() > 0f ? fade.set(ring.inner(), ring.fadeIn()) : fade.set(-1f, 0f));
            shader.setFloat("uRadius", ring.radius());
            shader.setFloat("uDensity", ring.density());
            glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, ring.grid() * ring.grid());
        }
        glBindVertexArray(0);
        CloudsInFront.disable(shader, previousClouds);
        shader.unbind();

        glBindTexture(GL_TEXTURE_2D, previousLanes);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + GROUND_TEXTURE_UNIT);
        glBindTexture(GL_TEXTURE_2D, previousGround);
        GL13.glActiveTexture(previousUnit);
        glBlendFunc(blendSrc, blendDst);
        if (!blend) glDisable(GL_BLEND);
        if (!depthTest) glDisable(GL_DEPTH_TEST);
        if (cull) glEnable(GL_CULL_FACE);
        glDepthMask(depthMask);
    }

    /** The {@code precip_lanes.glsl} uniforms for {@code ring}, on a BOUND shader. */
    private void applyLanes(ShaderProgram target, Ring ring) {
        target.setVec3("uCameraPos", renderCamera);
        target.setFloat("uLaneSpacing", ring.spacing());
        target.setInt("uLaneGrid", ring.grid());
        target.setInt("uLanePeriod", ring.period());
    }

    /** Fills and binds the ground texture around the camera (active unit). */
    private void uploadGround(World world, Vector3f cam) {
        int radius = (int) NEAR.radius();
        int x0 = (int) Math.floor(cam.x) - radius - 1;
        int z0 = (int) Math.floor(cam.z) - radius - 1;
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
        fieldShader.cleanup();
        shader.cleanup();
        quad.cleanup();
        for (RenderTarget target : lanes) target.cleanup();
        glDeleteBuffers(cornerVbo);
        glDeleteVertexArrays(vao);
        glDeleteTextures(groundTexture);
    }
}
