package com.stonebreak.rendering.gameWorld.weather;

import com.openmason.engine.rendering.RenderOrigin;
import com.openmason.engine.rendering.postfx.FullscreenQuad;
import com.openmason.engine.rendering.postfx.RenderTarget;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.rendering.shaders.ShaderResourceLoader;
import com.stonebreak.rendering.gameWorld.clouds.CloudUniforms;
import com.stonebreak.world.weather.CloudType;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import static org.lwjgl.opengl.GL11.*;

/**
 * Rain and snow fog as a volume in the world. It hangs under every raining
 * cloud, from the ground up to the cloud's underside as drawn, so an approaching storm shows
 * from kilometres off as a grey curtain, and the air above the clouds is clear.
 *
 * <p>Each frame {@link #prepare} renders the precipitation map: a camera-centred
 * texture of what every column of the cloud field drops, the light that comes
 * through the cloud above it, and the cloud's base. Shaders that include
 * {@code precip_fog.glsl} march their view rays through it ({@link #applyTo}):
 * terrain, water and clouds. {@link #renderSky} fogs the sky. Entities, which
 * are never far away, take the fog around the camera instead ({@link WeatherFog}).
 */
public final class PrecipitationFog {

    /** Unit the map is bound on for receivers; no other pass uses it. */
    public static final int MAP_TEXTURE_UNIT = 22;
    private static final int MAP_SIZE = 256;
    /** Blocks per map texel: the map reaches 3 km from the camera, the farthest the fog can. */
    private static final float TEXEL_BLOCKS = 24f;
    private static final float SPAN = MAP_SIZE * TEXEL_BLOCKS;

    /**
     * Blocks the fog reaches up into its cloud. It is full up to the cloud's
     * base and thins out above it, inside the cloud, which hides where it ends:
     * stopped at the base, it ended in a flat line under the cloud, plainest
     * with the eye level with it; thinned below the base, it stopped short of
     * the cloud. Also the band {@link WeatherEffects} thins the camera's fog over.
     */
    public static final float RISE = 32f;
    /** Highest base of a cloud type that rains. */
    public static final float BASE = highestRainingBase();
    /**
     * Highest above its column's base the map looks for the cloud's underside:
     * the noise carves a cloud's bottom ragged, so it starts above the base.
     */
    private static final float UNDERSIDE_REACH = 48f;
    /** No fog above this: the highest underside, plus {@link #RISE}. */
    public static final float TOP = BASE + UNDERSIDE_REACH + RISE;

    private final ShaderProgram mapShader;
    private final ShaderProgram skyShader;
    private final FullscreenQuad quad = new FullscreenQuad();
    private RenderTarget map;
    private boolean enabled;
    private final Vector2f origin = new Vector2f();
    private final Vector3f color = new Vector3f();
    private float heavy;
    private float reach;
    private float shadeMin;
    private final Matrix4f invViewProj = new Matrix4f();
    private final int[] viewport = new int[4];

    public PrecipitationFog() {
        String vert = load("/shaders/postfx/fullscreen.vert");
        mapShader = new ShaderProgram();
        mapShader.createVertexShader(vert);
        mapShader.createFragmentShader(load("/shaders/weather/precip_fog_map.frag"));
        mapShader.link();
        skyShader = new ShaderProgram();
        skyShader.createVertexShader(vert);
        skyShader.createFragmentShader(load("/shaders/weather/precip_fog_sky.frag"));
        skyShader.link();
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
     * Renders this frame's precipitation map around the camera. With
     * {@code active} false (no cloud field yet, or the camera underwater, where
     * the murk replaces it) the fog is off everywhere.
     *
     * @param reach blocks from the camera the fog ends at: the terrain's
     *              horizon, so no rain hangs over land that isn't drawn
     */
    public void prepare(CloudUniforms clouds, boolean active, Vector3f cameraWorldPos, float reach,
                        WeatherEffects effects) {
        enabled = active;
        if (!active) return;
        this.reach = Math.min(reach, SPAN / 2f);
        heavy = effects.heavyExtinction();
        shadeMin = effects.shadeMin();
        color.set(effects.fogColor());

        // Anchored to the world's texel grid, so the map doesn't swim as the camera moves.
        double minX = Math.floor(cameraWorldPos.x / TEXEL_BLOCKS) * TEXEL_BLOCKS - SPAN / 2.0;
        double minZ = Math.floor(cameraWorldPos.z / TEXEL_BLOCKS) * TEXEL_BLOCKS - SPAN / 2.0;
        origin.set((float) (minX - RenderOrigin.x()), (float) (minZ - RenderOrigin.z()));

        int previousFbo = glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadFbo = glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        glGetIntegerv(GL_VIEWPORT, viewport);
        boolean depthTest = glIsEnabled(GL_DEPTH_TEST);
        boolean blend = glIsEnabled(GL_BLEND);
        int previousUnit = glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + MAP_TEXTURE_UNIT);
        if (map == null) map = new RenderTarget(MAP_SIZE, MAP_SIZE, false, GL30.GL_RGBA16F);
        GL13.glActiveTexture(previousUnit);

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        map.bind();
        mapShader.bind();
        clouds.applyTo(mapShader);
        clouds.applyShapeTo(mapShader);
        mapShader.setVec2("u_mapOrigin", origin);
        mapShader.setFloat("u_mapSpan", SPAN);
        mapShader.setFloat("u_mapTexel", TEXEL_BLOCKS);
        mapShader.setFloat("u_undersideReach", UNDERSIDE_REACH);
        mapShader.setVec2("u_mapCentre", new Vector2f(origin).add(SPAN / 2f, SPAN / 2f));
        mapShader.setFloat("u_mapReach", this.reach);
        mapShader.setFloat("u_rateExponent", WeatherEffects.RATE_EXPONENT);
        mapShader.setFloat("u_topRise", RISE);
        quad.draw();
        mapShader.unbind();

        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousFbo);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFbo);
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        if (depthTest) glEnable(GL_DEPTH_TEST);
        if (blend) glEnable(GL_BLEND);
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

    /** Sets the {@code precip_fog.glsl} uniforms on a BOUND shader and binds the map. */
    public void applyTo(ShaderProgram shader) {
        shader.setBool("u_precipFogEnabled", enabled);
        if (!enabled) return;
        int previousUnit = glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + MAP_TEXTURE_UNIT);
        glBindTexture(GL_TEXTURE_2D, map.getColorTexture());
        GL13.glActiveTexture(previousUnit);
        shader.setInt("u_precipFogMap", MAP_TEXTURE_UNIT);
        shader.setVec2("u_precipFogOrigin", origin);
        shader.setFloat("u_precipFogSpan", SPAN);
        shader.setFloat("u_precipFogReach", reach);
        shader.setFloat("u_precipFogHeavy", heavy);
        shader.setFloat("u_precipFogTop", TOP);
        shader.setFloat("u_precipFogShadeMin", shadeMin);
        shader.setVec3("u_precipFogColor", color);
    }

    public void cleanup() {
        quad.cleanup();
        mapShader.cleanup();
        skyShader.cleanup();
        if (map != null) map.cleanup();
    }
}
