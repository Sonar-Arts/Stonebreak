package com.stonebreak.rendering.gameWorld.clouds;

import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.stonebreak.world.weather.CloudField;
import com.stonebreak.world.weather.CloudType;
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

    /** Altitude the shadow lookup projects sun rays to: the heart of the cumulus layer. */
    private static final float SHADOW_Y = CloudType.CUMULUS.baseY + 16f;

    private static final Vector4f TYPE_BASE = new Vector4f();
    private static final Vector4f TYPE_HEIGHT = new Vector4f();
    private static final Vector4f TYPE_SIGMA = new Vector4f();
    private static final Vector4f TYPE_ALBEDO = new Vector4f();
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
        }
    }

    private final Vector2f offset = new Vector2f();
    private final Vector3f sunDir = new Vector3f(0f, 1f, 0f);
    private float threshold;
    private float wetBias;
    private boolean shadowsEnabled;
    private int mapTexture;

    void prepare(int mapTexture, double renderToCloudX, double renderToCloudZ,
                 WeatherBias bias, Vector3f sunDirection, boolean shadowsEnabled) {
        this.mapTexture = mapTexture;
        // Reduced modulo the map period in double precision; the shader's cell
        // lookups wrap at the same period, so nothing it sees changes.
        offset.set((float) wrap(renderToCloudX), (float) wrap(renderToCloudZ));
        this.threshold = CloudField.coverageThreshold(bias);
        this.wetBias = bias.wetness();
        this.sunDir.set(sunDirection).normalize();
        this.shadowsEnabled = shadowsEnabled;
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
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + CLOUD_MAP_TEXTURE_UNIT);
        glBindTexture(GL_TEXTURE_2D, mapTexture);
        GL13.glActiveTexture(previous);

        shader.setInt("u_cloudMap", CLOUD_MAP_TEXTURE_UNIT);
        shader.setVec2("u_cloudOffset", offset);
        shader.setFloat("u_cloudCell", CloudField.CELL);
        shader.setFloat("u_cloudVoxel", CloudField.VOXEL);
        shader.setFloat("u_cloudThreshold", threshold);
        shader.setFloat("u_cloudEdge", CloudField.COVERAGE_EDGE);
        shader.setFloat("u_cloudDomeRamp", CloudField.DOME_RAMP);
        shader.setFloat("u_cloudTowerRamp", CloudField.TOWER_RAMP);
        shader.setFloat("u_cloudWetBias", wetBias);
        shader.setVec3("u_cloudWetSplits", WET_SPLITS);
        shader.setVec4("u_cloudTypeBase", TYPE_BASE);
        shader.setVec4("u_cloudTypeHeight", TYPE_HEIGHT);
        shader.setVec4("u_cloudTypeSigma", TYPE_SIGMA);
        shader.setVec4("u_cloudTypeAlbedo", TYPE_ALBEDO);
        shader.setBool("u_cloudShadowsEnabled", shadowsEnabled && mapTexture != 0);
        shader.setVec3("u_cloudSunDir", sunDir);
        shader.setFloat("u_cloudShadowY", SHADOW_Y);
    }
}
