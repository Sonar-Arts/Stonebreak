package com.stonebreak.rendering.gameWorld.clouds;

import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.stonebreak.world.weather.CloudColumn;
import com.stonebreak.world.weather.CloudField;
import com.stonebreak.world.weather.CloudType;
import com.stonebreak.world.weather.TerrainFlow;
import com.stonebreak.world.weather.WeatherMap;
import com.stonebreak.world.weather.WeatherBias;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL13;

import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.glBindTexture;

/**
 * This frame's cloud field state as shader uniforms: everything
 * {@code cloud_field.glsl} and {@code cloud_shadow.glsl} declare. Prepared once
 * per frame by {@link CloudRenderer}, then applied to the cloud passes and to
 * every shader that receives cloud shadows (world, water), the way
 * {@code ShadowMapRenderer.applyToShader} hands out the sun shadow map.
 *
 * <p>Uses the tolerant auto-registering setters, so a shader that declares the
 * uniforms but optimises some away doesn't fail.</p>
 */
public final class CloudUniforms {

    /**
     * Texture unit of the cloud map. Clear of every unit the world and water
     * shaders already sample (5 sun shadows, 6 torch shadows, 7 MMS quads,
     * 8 torch indirect, 9 water scene depth).
     */
    public static final int CLOUD_MAP_TEXTURE_UNIT = 10;
    /** Units of the local weather and the terrain relief (two textures); clear of the cloud passes' 11-14 too. */
    public static final int WEATHER_MAP_TEXTURE_UNIT = 15;
    public static final int TERRAIN_MAP_TEXTURE_UNIT = 16;
    public static final int TERRAIN_FLOW_TEXTURE_UNIT = 17;
    /** Blocks per wrap of {@code u_cloudOffset} (and of the cloud map). */
    public static final int MAP_PERIOD_BLOCKS = CloudMap.PERIOD_BLOCKS;

    /** Altitude the shadow lookup projects sun rays to: the heart of the cumulus layer. */
    private static final float SHADOW_Y = CloudType.CUMULUS.baseY + 16f;

    private static final Vector4f TYPE_BASE = new Vector4f();
    private static final Vector4f TYPE_HEIGHT = new Vector4f();
    private static final Vector4f TYPE_SIGMA = new Vector4f();
    private static final Vector4f TYPE_ALBEDO = new Vector4f();
    private static final Vector4f TYPE_RAIN = new Vector4f();
    private static final Vector2f FLOW_COVER = new Vector2f(TerrainFlow.CONVERGE, TerrainFlow.PILEUP_COVERAGE);
    private static final Vector2f FLOW_PASS = new Vector2f(TerrainFlow.PASS_MASS_LO, TerrainFlow.PASS_MASS_HI);
    private static final Vector3f WET_SPLITS = new Vector3f(
            CloudType.STRATUS_MAX_WETNESS,
            CloudType.NIMBOSTRATUS_MIN_WETNESS,
            CloudType.CUMULONIMBUS_MIN_WETNESS);

    static {
        CloudType[] types = CloudType.values();
        if (types.length > 4) {
            throw new IllegalStateException("cloud_field.glsl packs cloud types into vec4s; got " + types.length);
        }
        for (CloudType t : types) {
            TYPE_BASE.setComponent(t.ordinal(), t.baseY);
            TYPE_HEIGHT.setComponent(t.ordinal(), t.maxHeight);
            TYPE_SIGMA.setComponent(t.ordinal(), t.sigma);
            TYPE_ALBEDO.setComponent(t.ordinal(), t.albedo);
            TYPE_RAIN.setComponent(t.ordinal(), t.rain);
        }
    }

    private final Vector2f offset = new Vector2f();
    private final Vector2f worldOffset = new Vector2f();
    private final Vector3f sunDir = new Vector3f(0f, 1f, 0f);
    private boolean shadowsEnabled;
    private int mapTexture;
    private int weatherTexture;
    private int terrainTexture;
    private int terrainFlowTexture;

    void prepare(int mapTexture, int weatherTexture, int terrainTexture, int terrainFlowTexture,
                 double renderToCloudX, double renderToCloudZ, double renderToWorldX, double renderToWorldZ,
                 Vector3f sunDirection, boolean shadowsEnabled) {
        this.mapTexture = mapTexture;
        this.weatherTexture = weatherTexture;
        this.terrainTexture = terrainTexture;
        this.terrainFlowTexture = terrainFlowTexture;
        // Reduced modulo the map period in double precision; the shader's cell
        // lookups wrap at the same period, so nothing it sees changes. The
        // weather and terrain maps wrap at that period too.
        offset.set((float) wrap(renderToCloudX), (float) wrap(renderToCloudZ));
        worldOffset.set((float) wrap(renderToWorldX), (float) wrap(renderToWorldZ));
        this.sunDir.set(sunDirection).normalize();
        this.shadowsEnabled = shadowsEnabled;
    }

    private static final WeatherBias NO_BIAS = new WeatherBias(0f, 0f);

    private static void bind(int unit, int texture) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, texture);
    }

    private static double wrap(double blocks) {
        return blocks - Math.floor(blocks / CloudMap.PERIOD_BLOCKS) * CloudMap.PERIOD_BLOCKS;
    }

    /** No clouds this frame: receivers skip the shadow lookup. */
    void disable() {
        this.shadowsEnabled = false;
    }

    /** Applies the uniforms to a BOUND shader and binds the map on its unit. */
    public void applyTo(ShaderProgram shader) {
        int previous = org.lwjgl.opengl.GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        bind(CLOUD_MAP_TEXTURE_UNIT, mapTexture);
        bind(WEATHER_MAP_TEXTURE_UNIT, weatherTexture);
        bind(TERRAIN_MAP_TEXTURE_UNIT, terrainTexture);
        bind(TERRAIN_FLOW_TEXTURE_UNIT, terrainFlowTexture);
        GL13.glActiveTexture(previous);

        shader.setInt("u_cloudMap", CLOUD_MAP_TEXTURE_UNIT);
        shader.setVec2("u_cloudOffset", offset);
        shader.setInt("u_weatherMap", WEATHER_MAP_TEXTURE_UNIT);
        shader.setFloat("u_weatherTexel", WeatherMap.TEXEL_BLOCKS);
        shader.setVec2("u_worldOffset", worldOffset);
        shader.setInt("u_terrainMap", TERRAIN_MAP_TEXTURE_UNIT);
        shader.setInt("u_terrainFlowMap", TERRAIN_FLOW_TEXTURE_UNIT);
        shader.setFloat("u_flowClipSink", TerrainFlow.CLIP_SINK);
        shader.setVec2("u_flowCover", FLOW_COVER);
        shader.setVec2("u_flowPass", FLOW_PASS);
        shader.setFloat("u_cloudCell", CloudField.CELL);
        shader.setFloat("u_cloudVoxel", CloudField.VOXEL);
        shader.setFloat("u_cloudThreshold", CloudField.coverageThreshold(NO_BIAS));
        shader.setFloat("u_cloudBiasSpan", CloudField.COVERAGE_BIAS_SPAN);
        shader.setFloat("u_cloudEdge", CloudField.COVERAGE_EDGE);
        shader.setFloat("u_cloudDomeRamp", CloudField.DOME_RAMP);
        shader.setFloat("u_cloudOrganisation", CloudField.ORGANISATION_WEIGHT);
        shader.setFloat("u_cloudTowerRamp", CloudField.TOWER_RAMP);
        shader.setVec3("u_cloudWetSplits", WET_SPLITS);
        shader.setVec4("u_cloudTypeBase", TYPE_BASE);
        shader.setVec4("u_cloudTypeHeight", TYPE_HEIGHT);
        shader.setVec4("u_cloudTypeSigma", TYPE_SIGMA);
        shader.setVec4("u_cloudTypeAlbedo", TYPE_ALBEDO);
        shader.setVec4("u_cloudTypeRain", TYPE_RAIN);
        shader.setFloat("u_cloudTypeBlend", CloudType.TYPE_BLEND);
        shader.setFloat("u_cloudSmallRain", CloudColumn.SMALL_CLOUD_RAIN);
        shader.setBool("u_cloudShadowsEnabled", shadowsEnabled && mapTexture != 0);
        shader.setVec3("u_cloudSunDir", sunDir);
        shader.setFloat("u_cloudShadowY", SHADOW_Y);
    }
}
