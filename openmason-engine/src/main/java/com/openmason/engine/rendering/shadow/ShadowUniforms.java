package com.openmason.engine.rendering.shadow;

import com.openmason.engine.rendering.shaders.ShaderProgram;
import org.joml.Vector3f;

/**
 * Applies the {@link ShadowGlsl} uniform block to a shader. Uses the tolerant
 * auto-registering setters so shaders that declare but never call the shadow
 * functions (uniforms optimized out) don't crash.
 *
 * <p>The shader must already be bound. The shadow map itself is bound separately
 * via {@link CascadedShadowMap#bindForSampling(int)} — pass the same unit here.
 */
public final class ShadowUniforms {

    private ShadowUniforms() {}

    /** Disables shadow sampling on {@code shader} (still assigns the sampler unit). */
    public static void applyDisabled(ShaderProgram shader, int textureUnit) {
        shader.setInt("u_shadowMap", textureUnit);
        shader.setBool("u_shadowsEnabled", false);
    }

    /**
     * Applies the full cascade state for this frame.
     *
     * @param shader       bound target shader
     * @param cascades     computed cascades, length {@link ShadowSettings#CASCADE_COUNT}
     * @param sunDirection normalized, pointing toward the sun
     * @param strength     effective shadow strength (already faded for sun elevation)
     * @param pcfRadius    receiver PCF kernel radius in texels (see {@link ShadowSettings#pcfRadius()})
     * @param textureUnit  unit the shadow map array is bound to
     */
    public static void apply(ShaderProgram shader, ShadowCascade[] cascades,
                             Vector3f sunDirection, float strength, int pcfRadius, int textureUnit) {
        shader.setInt("u_shadowMap", textureUnit);
        shader.setBool("u_shadowsEnabled", true);
        shader.setFloat("u_shadowStrength", strength);
        shader.setInt("u_shadowPcfRadius", pcfRadius);
        shader.setVec3("u_shadowSunDir", sunDirection);
        shader.setVec3("u_cascadeSplits", new Vector3f(
                cascades[0].splitFar, cascades[1].splitFar, cascades[2].splitFar));
        for (int i = 0; i < cascades.length; i++) {
            shader.setMat4("u_lightSpaceMatrices[" + i + "]", cascades[i].lightViewProj);
            shader.setFloat("u_shadowTexelWorld[" + i + "]", cascades[i].texelWorldSize);
        }
    }
}
