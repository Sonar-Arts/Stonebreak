// Dynamic point lights (torches) — shared by the world, water and entity
// shaders. Uploaded per frame by com.stonebreak.rendering.lighting.DynamicLights;
// the host picks the lights nearest the camera. Each light is a warm-tinted
// radiance premultiplied by its flicker intensity; falloff is a smooth
// quadratic-ish curve that reaches exactly zero at the radius so lights never
// pop at their edge. The wrap term keeps faces pointing away from a torch
// from going pitch black (a torch on a wall still lights that wall).
#define MAX_POINT_LIGHTS 256
uniform int u_pointLightCount;
uniform vec4 u_pointLightPos[MAX_POINT_LIGHTS];   // xyz = world position, w = radius
uniform vec3 u_pointLightColor[MAX_POINT_LIGHTS]; // color * intensity

// How much torchlight a surface should receive given the time of day and its
// sky exposure: sunlit surfaces at noon take none (the sun already owns them),
// caves and night take all. ambient is the 0.3 (night) .. 1.0 (noon) level.
float pointLightWeight(float ambient, float skyExposure) {
    float daylight = clamp((ambient - 0.3) / 0.7, 0.0, 1.0);
    return 1.0 - daylight * clamp(skyExposure, 0.0, 1.0);
}

// Upper bound on the torchlight a surface can receive, as a multiple of its
// albedo. Several overlapping torches approach this smoothly instead of
// stacking linearly (which bleaches textures to white/yellow).
#define POINT_LIGHT_MAX 0.95

// Soft-knee saturation of a summed radiance: linear for small values, asymptotic
// to POINT_LIGHT_MAX. Per channel, so the warm tint survives saturation.
vec3 pointLightSoftCap(vec3 radiance) {
    return POINT_LIGHT_MAX * (vec3(1.0) - exp(-radiance / POINT_LIGHT_MAX));
}

// Fold torchlight into an already-lit colour: it can only raise the surface
// up to albedo * POINT_LIGHT_MAX (a fully torch-lit texel is the texture at
// full brightness, never brighter). Surfaces the sun already lit past that
// keep their sun result untouched — torches never stack on top of daylight.
vec3 applyPointLight(vec3 lit, vec3 albedo, vec3 torch) {
    vec3 capped = pointLightSoftCap(torch);
    vec3 ceiling = max(lit, albedo * POINT_LIGHT_MAX);
    return min(lit + albedo * capped, ceiling);
}

vec3 pointLightContribution(vec3 worldPos, vec3 normal) {
    vec3 sum = vec3(0.0);
    for (int i = 0; i < MAX_POINT_LIGHTS; i++) {
        if (i >= u_pointLightCount) break;
        vec4 light = u_pointLightPos[i];
        vec3 toLight = light.xyz - worldPos;
        float dist = length(toLight);
        if (dist >= light.w) continue;
        vec3 l = toLight / max(dist, 1e-4);
        float wrap = mix(0.35, 1.0, max(dot(normal, l), 0.0));
        float x = 1.0 - dist / light.w;
        float falloff = x * x * (3.0 - 2.0 * x);   // smoothstep(0,1,x)
        falloff *= falloff;                         // tighter core, soft tail
        sum += u_pointLightColor[i] * falloff * wrap;
    }
    return sum;
}
