package com.stonebreak.rendering.gameWorld.weather;

import com.openmason.engine.rendering.postfx.FullscreenQuad;
import com.openmason.engine.rendering.postfx.RenderTarget;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.rendering.shaders.ShaderResourceLoader;
import com.stonebreak.world.weather.CloudType;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import static org.lwjgl.opengl.GL11.*;

/**
 * Rain and snow fog: a haze around the camera while it is in the weather, as
 * thick as the rain or snow there ({@link WeatherEffects#extinction}) and gone
 * above the clouds. Shaders that include {@code precip_fog.glsl} take it
 * ({@link #applyTo}): terrain, water and clouds. {@link #renderSky} veils the
 * sky, and {@link #sunVisibility} tells the god rays how much sky shows through it.
 *
 * <p>It used to hang under every distant raining cloud as a volume, but seen
 * from outside those shafts were hard-edged grey columns; the user had them
 * removed (2026-10-20). A distant storm shows as its cloud and its rain.
 */
public final class PrecipitationFog {

    /**
     * Blocks above the highest raining base over which the camera's fog thins
     * out as it climbs into the clouds ({@link WeatherEffects}).
     */
    public static final float RISE = 32f;
    /** Highest base of a cloud type that rains. */
    public static final float BASE = highestRainingBase();
    /**
     * Deepest the fog reaches, in blocks: the veil over the sky. Twice the
     * heaviest rain's visibility, so in a downpour the sky is hidden and in a
     * drizzle it shows through.
     */
    private static final float DEPTH = 2f * WeatherEffects.HEAVY_RAIN_VISIBILITY;

    private final ShaderProgram skyShader;
    private final ShaderProgram visibilityShader;
    private RenderTarget visibility;
    private final FullscreenQuad quad = new FullscreenQuad();
    private boolean enabled;
    private float density;
    private final Vector3f color = new Vector3f();
    private final Matrix4f invViewProj = new Matrix4f();
    private final int[] viewport = new int[4];

    public PrecipitationFog() {
        String vert = load("/shaders/postfx/fullscreen.vert");
        skyShader = new ShaderProgram();
        skyShader.createVertexShader(vert);
        skyShader.createFragmentShader(load("/shaders/weather/precip_fog_sky.frag"));
        skyShader.link();
        visibilityShader = new ShaderProgram();
        visibilityShader.createVertexShader(vert);
        visibilityShader.createFragmentShader(load("/shaders/weather/sun_visibility.frag"));
        visibilityShader.link();
    }

    /** Lambda (not a method reference) so the caller-sensitive lookup happens in this module. */
    private static String load(String path) {
        return ShaderResourceLoader.load(path, p -> PrecipitationFog.class.getResourceAsStream(p));
    }

    private static float highestRainingBase() {
        float top = 0f;
        for (CloudType t : CloudType.values()) {
            if (t.precipitates) top = Math.max(top, t.baseY);
        }
        return top;
    }

    /**
     * Takes this frame's fog from the weather around the camera. With
     * {@code active} false (the camera underwater, where the murk replaces it)
     * there is none.
     */
    public void prepare(boolean active, WeatherEffects effects) {
        density = active ? effects.extinction() : 0f;
        enabled = density > 0f;
        effects.localFogColor(color);
    }

    /** Fogs the sky just drawn: each pixel by its whole ray through the fog. */
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

    /**
     * How much of the sky's light reaches the eye at each pixel through the cloud
     * and the fog in front of it, for screen-space effects such as god rays: a
     * texture (alpha) at half the current viewport's resolution. With no fog it is
     * the cloud's own transmittance texture (0 for none); call after the clouds.
     */
    public int sunVisibility(int cloudTransmittance, Matrix4f projection, Matrix4f view, Vector3f cameraRenderPos) {
        if (!enabled) return cloudTransmittance;
        glGetIntegerv(GL_VIEWPORT, viewport);
        int width = Math.max(1, viewport[2] / 2), height = Math.max(1, viewport[3] / 2);
        int previousFbo = glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadFbo = glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        boolean depthTest = glIsEnabled(GL_DEPTH_TEST);
        boolean blend = glIsEnabled(GL_BLEND);
        int previousUnit = glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        if (visibility == null) {
            visibility = new RenderTarget(width, height, false, GL_RGBA8);
        } else {
            visibility.resize(width, height);
        }

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        visibility.bind();
        visibilityShader.bind();
        applyTo(visibilityShader);
        visibilityShader.setMat4("u_invViewProj", invViewProj.set(projection).mul(view).invert());
        visibilityShader.setVec3("u_camPos", cameraRenderPos);
        glBindTexture(GL_TEXTURE_2D, cloudTransmittance);
        visibilityShader.setInt("u_cloudTransmittance", 0);
        visibilityShader.setBool("u_hasClouds", cloudTransmittance != 0);
        quad.draw();
        visibilityShader.unbind();

        glBindTexture(GL_TEXTURE_2D, previousTexture);
        GL13.glActiveTexture(previousUnit);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousFbo);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFbo);
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        if (depthTest) glEnable(GL_DEPTH_TEST);
        if (blend) glEnable(GL_BLEND);
        return visibility.getColorTexture();
    }

    /** Sets the {@code precip_fog.glsl} uniforms on a BOUND shader. */
    public void applyTo(ShaderProgram shader) {
        shader.setBool("u_precipFogEnabled", enabled);
        if (!enabled) return;
        shader.setFloat("u_precipFogDensity", density);
        shader.setFloat("u_precipFogDepth", DEPTH);
        shader.setVec3("u_precipFogColor", color);
    }

    public void cleanup() {
        quad.cleanup();
        skyShader.cleanup();
        visibilityShader.cleanup();
        if (visibility != null) visibility.cleanup();
    }
}
