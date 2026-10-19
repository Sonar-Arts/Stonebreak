package com.stonebreak.rendering.gameWorld.clouds;

import com.openmason.engine.rendering.RenderOrigin;
import com.openmason.engine.rendering.postfx.FullscreenQuad;
import com.openmason.engine.rendering.postfx.RenderTarget;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.rendering.shaders.ShaderResourceLoader;
import com.stonebreak.rendering.gameWorld.SceneDepthCopy;
import com.stonebreak.rendering.gameWorld.weather.PrecipitationFog;
import com.stonebreak.world.weather.ClientWeather;
import com.stonebreak.world.weather.CloudField;
import com.stonebreak.world.weather.CloudType;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import static org.lwjgl.opengl.GL11.*;

/**
 * Volumetric clouds, sculpted from the {@link CloudField} with 3D noise
 * ({@link CloudNoise}) into flat-based, billowing shapes. Each frame:
 * <ol>
 *   <li>{@link #prepareFrame} patches the {@link CloudMap} around the camera and
 *       resolves this frame's {@link CloudUniforms} — before the world draws,
 *       because terrain and water read cloud shadows from them;</li>
 *   <li>{@link #render} (after the opaque world, before glass, ice and water)
 *       snapshots scene depth, raymarches the
 *       clouds at half resolution into an RGBA16F target, blends that into the
 *       reprojected history of earlier frames (the march's dither changes every
 *       frame, so its grain averages away), then composites the result over
 *       the scene.</li>
 * </ol>
 * Opacity follows Beer-Lambert like water: thin cloud lets the sky and sun
 * through, thick cloud hides them. Saves and restores all GL state it touches.
 */
public final class CloudRenderer {

    /** Unit for this renderer's own samplers (scene depth, then the cloud buffer). */
    private static final int SCRATCH_TEXTURE_UNIT = 11;
    /** Units of the shape and detail noise volumes, read only by the cloud march. */
    private static final int SHAPE_TEXTURE_UNIT = 12;
    private static final int DETAIL_TEXTURE_UNIT = 13;
    /** Unit for the cloud history read by the temporal pass; its binding is restored. */
    private static final int HISTORY_TEXTURE_UNIT = 14;
    /** Unit for the march's cloud distances read by the temporal pass; its binding is restored. */
    private static final int DISTANCE_TEXTURE_UNIT = 19;
    /** Unit for the history's slab-visibility mask read by the temporal pass; its binding is restored. */
    private static final int VISIBLE_TEXTURE_UNIT = 21;

    /**
     * How fast the shape and detail noise rise through the clouds, blocks per
     * second of weather time: billows slowly boil up and dissolve, the detail
     * faster than the body, as in a time-lapse of real cumulus.
     */
    private static final double SHAPE_RISE_SPEED = 0.05;
    private static final double DETAIL_RISE_SPEED = 0.3;

    /**
     * Clouds are gone this far out (horizontally), inside the map's half-width
     * so no wrapped cell shows, or sooner, at the terrain's horizon...
     */
    private static final float FADE_END = CloudMap.PERIOD_BLOCKS * 0.45f;
    /** ...and thin out over this last share of that distance. */
    private static final float FADE_SHARE = 0.3f;

    /** Must match the {@code u_flashes} array length in clouds_march.frag. */
    private static final String[] FLASH_UNIFORMS = {"u_flashes[0]", "u_flashes[1]", "u_flashes[2]", "u_flashes[3]"};
    private static final Vector4f NO_FLASH = new Vector4f();

    private final CloudMap map = new CloudMap();
    private final WeatherMapTexture weatherMap = new WeatherMapTexture();
    private final TerrainMap terrainMap = new TerrainMap();
    private final CloudNoiseTextures noise = new CloudNoiseTextures();
    private final CloudUniforms uniforms = new CloudUniforms();
    private final SceneDepthCopy sceneDepth = new SceneDepthCopy();
    private final FullscreenQuad quad = new FullscreenQuad();
    private final ShaderProgram marchShader;
    private final ShaderProgram temporalShader;
    private final ShaderProgram compositeShader;
    private RenderTarget target;
    /** Ping-ponged accumulation targets: one is read as history while the other is written. */
    private final RenderTarget[] history = new RenderTarget[2];
    private int historyIndex;
    private boolean historyValid;
    private int frame;
    private final Matrix4f prevViewProjRel = new Matrix4f();
    private final Matrix4f viewProjRel = new Matrix4f();
    private final Vector3f camDelta = new Vector3f();
    private double prevCamX, prevCamY, prevCamZ;
    private boolean prepared;
    /** The maps are current this frame (weather exists), whether or not clouds draw. */
    private boolean mapsReady;
    private boolean drawnThisFrame;
    private float shapeRise;
    private float detailRise;

    private final Matrix4f invViewProj = new Matrix4f();
    private final Vector3f sunColor = new Vector3f();
    private final Vector3f ambientColor = new Vector3f();
    private final int[] viewportBuf = new int[4];

    public CloudRenderer() {
        String vert = load("/shaders/postfx/fullscreen.vert");
        marchShader = new ShaderProgram();
        marchShader.createVertexShader(vert);
        marchShader.createFragmentShader(load("/shaders/clouds/clouds_march.frag"));
        marchShader.link();
        temporalShader = new ShaderProgram();
        temporalShader.createVertexShader(vert);
        temporalShader.createFragmentShader(load("/shaders/clouds/clouds_temporal.frag"));
        temporalShader.link();
        compositeShader = new ShaderProgram();
        compositeShader.createVertexShader(vert);
        compositeShader.createFragmentShader(load("/shaders/clouds/clouds_composite.frag"));
        compositeShader.link();
    }

    /** Lambda (not a method reference) so the caller-sensitive lookup happens in this module. */
    private static String load(String path) {
        return ShaderResourceLoader.load(path, p -> CloudRenderer.class.getResourceAsStream(p));
    }

    /**
     * Updates the map and this frame's uniforms. Call before any shader that
     * receives cloud shadows is set up; with {@code enabled} false (setting
     * off) receivers get unshadowed light and no cloud draws, but the maps stay
     * current for the rain, which falls from the cloud field either way.
     */
    public void prepareFrame(ClientWeather weather, Vector3f cameraWorldPos, Vector3f sunDirection,
                             boolean enabled) {
        drawnThisFrame = false;
        mapsReady = weather != null;
        prepared = enabled && mapsReady;
        if (!prepared) historyValid = false; // stale by the time clouds return
        if (!mapsReady) {
            uniforms.disable();
            return;
        }
        double s = weather.seconds();
        // Wrapped to a tile in double precision, so float precision never runs out.
        shapeRise = (float) (s * SHAPE_RISE_SPEED % CloudNoiseTextures.SHAPE_TILE_BLOCKS);
        detailRise = (float) (s * DETAIL_RISE_SPEED % CloudNoiseTextures.DETAIL_TILE_BLOCKS);
        int previousUnit = glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + CloudUniforms.CLOUD_MAP_TEXTURE_UNIT);
        map.update(weather.field(), CloudField.cellX(cameraWorldPos.x, s), CloudField.cellZ(cameraWorldPos.z, s));
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + CloudUniforms.WEATHER_MAP_TEXTURE_UNIT);
        weatherMap.update(weather.map());
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + CloudUniforms.TERRAIN_MAP_TEXTURE_UNIT);
        terrainMap.update(weather.relief(), cameraWorldPos.x, cameraWorldPos.z);
        GL13.glActiveTexture(previousUnit);
        // cloud = world - drift = render + origin - drift
        uniforms.prepare(map.texture(), weatherMap.texture(), terrainMap.shapeTexture(), terrainMap.flowTexture(),
                RenderOrigin.x() - CloudField.driftX(s),
                RenderOrigin.z() - CloudField.driftZ(s),
                RenderOrigin.x(), RenderOrigin.z(),
                sunDirection, true);
        if (!prepared) uniforms.disable();
    }

    /** Whether {@link #uniforms} hold this frame's cloud field (for the rain). */
    public boolean mapsReady() {
        return mapsReady;
    }

    /** The uniforms receivers apply (cloud shadows); valid after {@link #prepareFrame}. */
    public CloudUniforms uniforms() {
        return uniforms;
    }

    /**
     * Draws the clouds over the bound framebuffer, which must hold this frame's
     * opaque scene depth (translucent passes draw after). No-op unless {@link #prepareFrame} enabled them.
     *
     * @param renderCameraPos camera position in render space
     * @param flashes         lightning flash points (render-space xyz, intensity w; 0 = unused),
     *                        each lighting only the cloud around it
     * @param fog             rain and snow fog, which hides the cloud behind it
     * @param reach           blocks to the terrain's horizon: no cloud hangs beyond it
     */
    public void render(Matrix4f projection, Matrix4f view, Vector3f renderCameraPos,
                       Vector3f sunDirection, Vector3f skyColor, float ambientLight, Vector4f[] flashes,
                       PrecipitationFog fog, float reach) {
        if (!prepared) return;

        // Save state.
        // Both bindings: binding the cloud target sets the READ framebuffer too, and a
        // stale read binding makes the next frame's depth copies (water, clouds) read
        // a target with no depth — which crashes the NVIDIA driver, not just errors.
        int previousFbo = glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadFbo = glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        glGetIntegerv(GL_VIEWPORT, viewportBuf);
        int vx = viewportBuf[0], vy = viewportBuf[1], vw = viewportBuf[2], vh = viewportBuf[3];
        boolean depthTest = glIsEnabled(GL_DEPTH_TEST);
        boolean blend = glIsEnabled(GL_BLEND);
        boolean cull = glIsEnabled(GL_CULL_FACE);
        boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        int blendSrc = glGetInteger(GL_BLEND_SRC);
        int blendDst = glGetInteger(GL_BLEND_DST);
        int previousUnit = glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + SCRATCH_TEXTURE_UNIT);
        int previousScratch = glGetInteger(GL_TEXTURE_BINDING_2D);

        sceneDepth.copy(); // bound on the scratch unit

        int hw = Math.max(1, vw / 2), hh = Math.max(1, vh / 2);
        if (target == null) {
            // Second attachment: how far away each pixel's cloud is, for reprojection.
            target = new RenderTarget(hw, hh, false, GL30.GL_RGBA16F, GL30.GL_R32F);
            // Second attachment: whether each texel saw the slab, so history the scene hid is never read.
            history[0] = new RenderTarget(hw, hh, false, GL30.GL_RGBA16F, GL30.GL_R8);
            history[1] = new RenderTarget(hw, hh, false, GL30.GL_RGBA16F, GL30.GL_R8);
        } else if (target.getWidth() != hw || target.getHeight() != hh) {
            target.resize(hw, hh);
            history[0].resize(hw, hh);
            history[1].resize(hw, hh);
            historyValid = false;
        }
        // (Re)creating the target unbinds the active unit's texture.
        glBindTexture(GL_TEXTURE_2D, sceneDepth.texture());
        bindNoise(SHAPE_TEXTURE_UNIT, noise.shape());
        bindNoise(DETAIL_TEXTURE_UNIT, noise.detail());
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + SCRATCH_TEXTURE_UNIT);

        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);
        glDisable(GL_BLEND);

        // Pass 1: raymarch at half resolution.
        target.bind();
        marchShader.bind();
        uniforms.applyTo(marchShader);
        marchShader.setInt("u_sceneDepth", SCRATCH_TEXTURE_UNIT);
        marchShader.setInt("u_shapeNoise", SHAPE_TEXTURE_UNIT);
        marchShader.setInt("u_detailNoise", DETAIL_TEXTURE_UNIT);
        marchShader.setFloat("u_shapeTile", CloudNoiseTextures.SHAPE_TILE_BLOCKS);
        marchShader.setFloat("u_detailTile", CloudNoiseTextures.DETAIL_TILE_BLOCKS);
        marchShader.setFloat("u_shapeRise", shapeRise);
        marchShader.setFloat("u_detailRise", detailRise);
        marchShader.setMat4("u_invViewProj", invViewProj.set(projection).mul(view).invert());
        marchShader.setVec3("u_camPos", renderCameraPos);
        marchShader.setInt("u_frame", frame++);
        marchShader.setVec3("u_sunDir", sunDirection);
        marchShader.setVec3("u_sunColor", sunColor(sunDirection));
        marchShader.setVec3("u_ambientColor", ambientColor(skyColor, ambientLight));
        marchShader.setVec3("u_hazeColor", skyColor);
        fog.applyTo(marchShader);
        marchShader.setFloat("u_slabBottom", CloudType.slabBottom());
        marchShader.setFloat("u_slabTop", CloudType.slabTop());
        float fadeEnd = Math.min(FADE_END, reach);
        marchShader.setFloat("u_fadeStart", fadeEnd * (1f - FADE_SHARE));
        marchShader.setFloat("u_fadeEnd", fadeEnd);
        for (int i = 0; i < FLASH_UNIFORMS.length; i++) {
            marchShader.setVec4(FLASH_UNIFORMS[i], i < flashes.length ? flashes[i] : NO_FLASH);
        }
        quad.draw();

        // Pass 2: blend into the reprojected history. Camera-relative, so a
        // render-origin rebase between frames doesn't break the reprojection.
        double camX = renderCameraPos.x + (double) RenderOrigin.x();
        double camY = renderCameraPos.y; // the origin rebases X/Z only
        double camZ = renderCameraPos.z + (double) RenderOrigin.z();
        camDelta.set((float) (camX - prevCamX), (float) (camY - prevCamY), (float) (camZ - prevCamZ));
        viewProjRel.set(view).setTranslation(0f, 0f, 0f).mulLocal(projection);
        RenderTarget resolved = history[historyIndex];
        RenderTarget previous = history[1 - historyIndex];
        resolved.bind();
        glBindTexture(GL_TEXTURE_2D, target.getColorTexture());
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + HISTORY_TEXTURE_UNIT);
        int previousHistoryUnitTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        glBindTexture(GL_TEXTURE_2D, previous.getColorTexture());
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + DISTANCE_TEXTURE_UNIT);
        int previousDistanceUnitTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        glBindTexture(GL_TEXTURE_2D, target.getExtraColorTexture());
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + VISIBLE_TEXTURE_UNIT);
        int previousVisibleUnitTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        glBindTexture(GL_TEXTURE_2D, previous.getExtraColorTexture());
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + SCRATCH_TEXTURE_UNIT);
        temporalShader.bind();
        temporalShader.setInt("u_current", SCRATCH_TEXTURE_UNIT);
        temporalShader.setInt("u_cloudDistance", DISTANCE_TEXTURE_UNIT);
        temporalShader.setInt("u_history", HISTORY_TEXTURE_UNIT);
        temporalShader.setInt("u_historyVisible", VISIBLE_TEXTURE_UNIT);
        temporalShader.setBool("u_historyValid", historyValid);
        temporalShader.setMat4("u_invViewProj", invViewProj);
        temporalShader.setVec3("u_camPos", renderCameraPos);
        temporalShader.setMat4("u_prevViewProjRel", prevViewProjRel);
        temporalShader.setVec3("u_camDelta", camDelta);
        temporalShader.setFloat("u_slabBottom", CloudType.slabBottom());
        temporalShader.setFloat("u_slabTop", CloudType.slabTop());
        quad.draw();
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + VISIBLE_TEXTURE_UNIT);
        glBindTexture(GL_TEXTURE_2D, previousVisibleUnitTexture);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + DISTANCE_TEXTURE_UNIT);
        glBindTexture(GL_TEXTURE_2D, previousDistanceUnitTexture);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + HISTORY_TEXTURE_UNIT);
        glBindTexture(GL_TEXTURE_2D, previousHistoryUnitTexture);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + SCRATCH_TEXTURE_UNIT);
        prevViewProjRel.set(viewProjRel);
        prevCamX = camX;
        prevCamY = camY;
        prevCamZ = camZ;
        historyValid = true;
        historyIndex = 1 - historyIndex;

        // Pass 3: composite over the scene: scene * transmittance + cloud light.
        // Depth-aware, so it reads the scene depth beside the clouds.
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousFbo);
        glViewport(vx, vy, vw, vh);
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_SRC_ALPHA);
        glBindTexture(GL_TEXTURE_2D, resolved.getColorTexture());
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + HISTORY_TEXTURE_UNIT);
        glBindTexture(GL_TEXTURE_2D, sceneDepth.texture());
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + SCRATCH_TEXTURE_UNIT);
        compositeShader.bind();
        compositeShader.setInt("u_clouds", SCRATCH_TEXTURE_UNIT);
        compositeShader.setInt("u_sceneDepth", HISTORY_TEXTURE_UNIT);
        compositeShader.setMat4("u_invViewProj", invViewProj);
        compositeShader.setVec3("u_camPos", renderCameraPos);
        compositeShader.setFloat("u_slabBottom", CloudType.slabBottom());
        compositeShader.setFloat("u_slabTop", CloudType.slabTop());
        quad.draw();
        compositeShader.unbind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + HISTORY_TEXTURE_UNIT);
        glBindTexture(GL_TEXTURE_2D, previousHistoryUnitTexture);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + SCRATCH_TEXTURE_UNIT);
        drawnThisFrame = true;

        // Restore state.
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFbo);
        glBindTexture(GL_TEXTURE_2D, previousScratch);
        GL13.glActiveTexture(previousUnit);
        glBlendFunc(blendSrc, blendDst);
        if (!blend) glDisable(GL_BLEND);
        if (depthTest) glEnable(GL_DEPTH_TEST);
        if (cull) glEnable(GL_CULL_FACE);
        glDepthMask(depthMask);
    }

    /**
     * The half-resolution cloud buffer drawn this frame (alpha = transmittance),
     * for screen-space effects that should see clouds; 0 when none was drawn.
     */
    public int transmittanceTexture() {
        return drawnThisFrame && target != null ? history[1 - historyIndex].getColorTexture() : 0;
    }

    /** Binds a noise volume on its own unit, which nothing else samples, so it is left bound. */
    private static void bindNoise(int unit, int texture) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        glBindTexture(GL12.GL_TEXTURE_3D, texture);
    }

    /** Warm and dim near the horizon, white overhead, gone below it. */
    private Vector3f sunColor(Vector3f sun) {
        float up = sun.y;
        float strength = smoothstep(-0.05f, 0.12f, up) * 0.85f;
        float white = smoothstep(0.0f, 0.35f, up);
        return sunColor.set(
                strength,
                strength * (0.55f + 0.42f * white),
                strength * (0.32f + 0.58f * white));
    }

    /** Sky-tinted by day, dim blue-grey by night (the old clouds' night tint). */
    private Vector3f ambientColor(Vector3f sky, float ambient) {
        float a = Math.clamp(ambient, 0f, 1f);
        return ambientColor.set(
                lerp(0.16f, 0.85f * (0.6f + 0.4f * sky.x), a),
                lerp(0.17f, 0.85f * (0.6f + 0.4f * sky.y), a),
                lerp(0.24f, 0.85f * (0.6f + 0.4f * sky.z), a));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Math.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    public void cleanup() {
        map.cleanup();
        weatherMap.cleanup();
        terrainMap.cleanup();
        noise.cleanup();
        sceneDepth.cleanup();
        quad.cleanup();
        marchShader.cleanup();
        temporalShader.cleanup();
        compositeShader.cleanup();
        if (target != null) {
            target.cleanup();
            history[0].cleanup();
            history[1].cleanup();
        }
    }
}
