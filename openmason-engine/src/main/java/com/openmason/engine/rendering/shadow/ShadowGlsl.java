package com.openmason.engine.rendering.shadow;

/**
 * Reusable GLSL for sampling the cascaded shadow map. Fragment shaders that want
 * sun shadows splice {@link #UNIFORMS} into their declarations and
 * {@link #FUNCTIONS} above {@code main()}, then call
 * {@code csmShadowFactor(worldPos, normal, viewDepth)} and multiply the result
 * into their direct-light (diffuse/specular) terms — never into ambient.
 *
 * <p>{@code viewDepth} is the positive view-space distance of the fragment
 * ({@code -(viewMatrix * worldPos).z}). Uniform values are applied host-side via
 * {@link ShadowUniforms}. Requires GLSL 330 and hard-codes
 * {@link ShadowSettings#CASCADE_COUNT} (3) cascades.
 */
public final class ShadowGlsl {

    private ShadowGlsl() {}

    /** Uniform declarations. Defaults (all-zero) mean "shadows off" — safe unconfigured. */
    public static final String UNIFORMS = """
            uniform bool u_shadowsEnabled;
            uniform sampler2DArrayShadow u_shadowMap;
            uniform mat4 u_lightSpaceMatrices[3];
            uniform vec3 u_cascadeSplits;      // far distance of cascades 0/1/2 (view space)
            uniform float u_shadowStrength;    // 0..1 max darkening of direct light
            uniform float u_shadowTexelWorld[3]; // world size of one texel per cascade
            uniform vec3 u_shadowSunDir;       // normalized, toward the sun
            """;

    /** {@code csmSampleCascade} + {@code csmShadowFactor}. Paste above {@code main()}. */
    public static final String FUNCTIONS = """
            float csmSampleCascade(int idx, vec3 worldPos) {
                vec4 ls = u_lightSpaceMatrices[idx] * vec4(worldPos, 1.0);
                vec3 proj = ls.xyz / ls.w * 0.5 + 0.5;
                if (proj.z >= 1.0) return 1.0;
                float texel = 1.0 / float(textureSize(u_shadowMap, 0).x);
                // 3x3 PCF on top of the hardware 2x2 — soft penumbra at low cost.
                float sum = 0.0;
                for (int x = -1; x <= 1; x++) {
                    for (int y = -1; y <= 1; y++) {
                        sum += texture(u_shadowMap, vec4(
                            proj.xy + vec2(float(x), float(y)) * texel,
                            float(idx), proj.z - 0.0005));
                    }
                }
                return sum / 9.0;
            }

            // Sun visibility in [1 - u_shadowStrength, 1]. Multiply into diffuse and
            // specular only; ambient must stay untouched or shadows read as blackness.
            float csmShadowFactor(vec3 worldPos, vec3 normal, float viewDepth) {
                if (!u_shadowsEnabled || u_shadowStrength <= 0.001) return 1.0;
                if (viewDepth >= u_cascadeSplits.z) return 1.0;
                float ndl = dot(normal, u_shadowSunDir);
                // Faces turned from the sun get no direct light anyway — skip the taps.
                if (ndl <= 0.0) return 1.0;
                int idx = viewDepth < u_cascadeSplits.x ? 0
                        : (viewDepth < u_cascadeSplits.y ? 1 : 2);
                // Normal-offset bias: slide the receiver along its normal by ~1 texel,
                // growing at grazing angles where acne is worst.
                float grazing = clamp(1.0 - ndl, 0.0, 1.0);
                vec3 offsetPos = worldPos + normal * (u_shadowTexelWorld[idx] * (1.0 + 2.5 * grazing));
                float vis = csmSampleCascade(idx, offsetPos);
                // Fade over the last 15% of range so the shadow boundary never pops.
                float fade = clamp((u_cascadeSplits.z - viewDepth)
                        / max(u_cascadeSplits.z * 0.15, 0.001), 0.0, 1.0);
                return mix(1.0, mix(1.0, vis, u_shadowStrength), fade);
            }
            """;
}
