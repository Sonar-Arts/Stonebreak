// Cascaded shadow map sampling. Include above main(), then call
// csmShadowFactor(worldPos, normal, viewDepth) and multiply the result into
// direct light (diffuse + specular) only — never into ambient.
// Requires csm_uniforms.glsl, GLSL 330, and ShadowSettings.CASCADE_COUNT (3).
float csmSampleCascade(int idx, vec3 worldPos) {
    vec4 ls = u_lightSpaceMatrices[idx] * vec4(worldPos, 1.0);
    vec3 proj = ls.xyz / ls.w * 0.5 + 0.5;
    if (proj.z >= 1.0) return 1.0;
    float texel = 1.0 / float(textureSize(u_shadowMap, 0).x);
    // PCF on top of the hardware 2x2 — kernel radius set by the quality tier.
    int r = clamp(u_shadowPcfRadius, 0, 2);
    float sum = 0.0;
    for (int x = -r; x <= r; x++) {
        for (int y = -r; y <= r; y++) {
            sum += texture(u_shadowMap, vec4(
                proj.xy + vec2(float(x), float(y)) * texel,
                float(idx), proj.z - 0.0005));
        }
    }
    float side = float(2 * r + 1);
    return sum / (side * side);
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
