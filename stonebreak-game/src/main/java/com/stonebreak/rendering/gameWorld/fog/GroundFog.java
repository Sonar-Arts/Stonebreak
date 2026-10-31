package com.stonebreak.rendering.gameWorld.fog;

import com.openmason.engine.rendering.RenderOrigin;
import com.openmason.engine.rendering.postfx.FullscreenQuad;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.rendering.shaders.ShaderResourceLoader;
import com.stonebreak.rendering.lighting.SkyLight;
import com.stonebreak.world.ShadeNight;
import com.stonebreak.world.generation.TerrainGenerator;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/**
 * The Shade's night ground fog: a layer {@link #DEPTH} blocks deep lying on Shade ground
 * ({@link ShadeFogMap}), forming as the sun sets and burning off just after it rises.
 * Shaders that include {@code ground_fog.glsl} ({@link #glsl}) take it ({@link #applyTo}):
 * terrain, FastLOD, water, clouds and creatures. {@link #renderSky} fogs the sky seen across it.
 */
public final class GroundFog {

    public static final int TEXTURE_UNIT = 24;
    /** Blocks above the ground the fog reaches: into the dry oak canopy and a little over it. */
    static final float DEPTH = 10f;
    /** Below the ground the fog fades out over this many blocks, so caves under the Shade stay clear. */
    static final float BELOW = 6f;
    /** Extinction per block in the thick of the fog: about 30 blocks of sight inside it. */
    static final float DENSITY = 0.08f;
    /**
     * Blocks of fog the camera sees in every direction, the sky overhead included, while it
     * stands in the thick of the layer; less as it climbs out ({@code ground_fog.glsl}).
     */
    static final float HAZE = 24f;
    /**
     * Least luminance of the fog by night, and how much a full moon adds: the night sky's
     * horizon is all but black, and fog held to it vanished at midnight.
     */
    static final float NIGHT_LUMINANCE = 0.09f;
    static final float MOON_LUMINANCE = 0.08f;
    /** The fog's hue at unit luminance: a cool, moonlit grey. */
    private static final Vector3f HUE = new Vector3f(0.90f, 1.00f, 1.15f);

    private final ShadeFogMap map = new ShadeFogMap();
    private final ShaderProgram skyShader;
    private final FullscreenQuad quad = new FullscreenQuad();
    private final int texture;
    private final FloatBuffer upload = BufferUtils.createFloatBuffer(ShadeFogMap.CELLS * ShadeFogMap.CELLS * 2);
    private ShadeFogMap.Grid uploaded;
    private boolean enabled;
    private float density;
    private final Vector3f color = new Vector3f();
    private final Vector3f origin = new Vector3f();
    private final Matrix4f invViewProj = new Matrix4f();

    public GroundFog() {
        skyShader = new ShaderProgram();
        skyShader.createVertexShader(load("/shaders/postfx/fullscreen.vert"));
        skyShader.createFragmentShader(load("/shaders/world/ground_fog_sky.frag"));
        skyShader.link();

        texture = glGenTextures();
        int previousUnit = glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT);
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL30.GL_RG16F, ShadeFogMap.CELLS, ShadeFogMap.CELLS, 0,
                GL30.GL_RG, GL_FLOAT, (FloatBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        GL13.glActiveTexture(previousUnit);
    }

    /** {@code ground_fog.glsl}'s source, for shaders built in code rather than loaded. */
    public static String glsl() {
        return load("/shaders/world/ground_fog.glsl");
    }

    /** Lambda (not a method reference) so the caller-sensitive lookup happens in this module. */
    private static String load(String path) {
        return ShaderResourceLoader.load(path, p -> GroundFog.class.getResourceAsStream(p));
    }

    /** How formed the fog is with the sun at height {@code sunY}; see {@link ShadeNight}. */
    static float nightStrength(float sunY) {
        return ShadeNight.fogStrength(sunY);
    }

    /**
     * Takes this frame's fog. With {@code active} false (the camera underwater) or no sky
     * there is none; the map still follows the camera, so it is ready by sunset.
     */
    public void prepare(boolean active, SkyLight sky, TerrainGenerator terrain, Vector3f cameraWorldPos) {
        map.focus(cameraWorldPos.x, cameraWorldPos.z, terrain);
        float strength = active && sky != null ? nightStrength(sky.sunDir().y) : 0f;
        ShadeFogMap.Grid grid = map.grid();
        enabled = strength > 0f && grid != null && grid.hasShade();
        if (!enabled) return;
        density = DENSITY * strength;
        if (grid != uploaded) {
            uploadGrid(grid);
        }
        // Lit by the sky at dusk; by night the moon keeps it a faint pale grey that reads
        // against the dark ground without glowing.
        Vector3f horizon = sky.horizon();
        float lum = 0.3f * horizon.x + 0.55f * horizon.y + 0.15f * horizon.z;
        color.set(HUE).mul(Math.max(lum, NIGHT_LUMINANCE + MOON_LUMINANCE * sky.moonLight()));
    }

    private void uploadGrid(ShadeFogMap.Grid grid) {
        upload.clear();
        float[] coverage = grid.coverage();
        float[] height = grid.height();
        for (int i = 0; i < coverage.length; i++) {
            upload.put(coverage[i]).put(height[i]);
        }
        upload.flip();
        int previousUnit = glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT);
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, ShadeFogMap.CELLS, ShadeFogMap.CELLS, GL30.GL_RG, GL_FLOAT, upload);
        GL13.glActiveTexture(previousUnit);
        uploaded = grid;
    }

    /** Fogs the sky just drawn: each pixel by its ray across the fog layer. */
    public void renderSky(Matrix4f projection, Matrix4f view, Vector3f cameraRenderPos) {
        if (!enabled) return;
        boolean depthTest = glIsEnabled(GL_DEPTH_TEST);
        boolean blend = glIsEnabled(GL_BLEND);
        boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        int blendSrc = glGetInteger(GL_BLEND_SRC);
        int blendDst = glGetInteger(GL_BLEND_DST);
        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_SRC_ALPHA);

        skyShader.bind();
        applyTo(skyShader);
        skyShader.setMat4("u_invViewProj", invViewProj.set(projection).mul(view).invert());
        skyShader.setVec3("u_camPos", cameraRenderPos);
        quad.draw();
        skyShader.unbind();

        glBlendFunc(blendSrc, blendDst);
        if (!blend) glDisable(GL_BLEND);
        if (depthTest) glEnable(GL_DEPTH_TEST);
        glDepthMask(depthMask);
    }

    /** Sets the {@code ground_fog.glsl} uniforms on a BOUND shader, binding the map on its unit. */
    public void applyTo(ShaderProgram shader) {
        if (!enabled) {
            applyDisabled(shader);
            return;
        }
        shader.setBool("u_groundFogEnabled", true);
        shader.setInt("u_groundFogMap", TEXTURE_UNIT);
        ShadeFogMap.Grid grid = uploaded;
        int previousUnit = glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT);
        glBindTexture(GL_TEXTURE_2D, texture);
        GL13.glActiveTexture(previousUnit);
        // The map's corner in render space; heights are world Y, which render space keeps.
        origin.set(grid.originX() - RenderOrigin.x(), 0f, grid.originZ() - RenderOrigin.z());
        shader.setVec3("u_groundFogOrigin", origin);
        shader.setFloat("u_groundFogSize", ShadeFogMap.SIZE_BLOCKS);
        shader.setFloat("u_groundFogBottom", grid.minGround() - BELOW);
        shader.setFloat("u_groundFogTop", grid.maxGround() + DEPTH);
        shader.setFloat("u_groundFogDepth", DEPTH);
        shader.setFloat("u_groundFogBelow", BELOW);
        shader.setFloat("u_groundFogDensity", density);
        shader.setVec3("u_groundFogColor", color);
        shader.setFloat("u_groundFogHaze", HAZE);
    }

    /** Turns the fog off on a BOUND shader, for draws it must not touch (UI previews). */
    public static void applyDisabled(ShaderProgram shader) {
        shader.setBool("u_groundFogEnabled", false);
        // Even when off: left at unit 0 the sampler could clash with another type bound there.
        shader.setInt("u_groundFogMap", TEXTURE_UNIT);
    }

    public void cleanup() {
        map.close();
        quad.cleanup();
        skyShader.cleanup();
        glDeleteTextures(texture);
    }
}
