// Dynamic point lights (torches) — shared by the world, water and entity
// shaders. Uploaded per frame by com.stonebreak.rendering.lighting.DynamicLights;
// the host picks the lights nearest the camera. Each light is a warm-tinted
// radiance premultiplied by its flicker intensity; falloff is a smooth
// quadratic-ish curve that reaches exactly zero at the radius so lights never
// pop at their edge. Direct diffuse and obstruction-aware indirect fill are separate.
#define MAX_POINT_LIGHTS 16
uniform int u_pointLightCount;
uniform vec4 u_pointLightPos[MAX_POINT_LIGHTS];   // xyz = world position, w = radius
uniform vec4 u_pointLightColor[MAX_POINT_LIGHTS]; // rgb = color * intensity, w = shadow slot

#include "/shaders/shadow/point_shadow.glsl"
#include "/shaders/shadow/indirect_light.glsl"

// How much torchlight a surface should receive given the time of day and its
// sky exposure: directly sunlit surfaces at noon take none, caves and night
// take all. A sun-shadowed surface still receives unblocked torchlight.
// ambient is the 0.3 (night) .. 1.0 (noon) level.
float pointLightWeight(float ambient, float skyExposure, float sunVisibility) {
    float daylight = clamp((ambient - 0.3) / 0.7, 0.0, 1.0);
    return 1.0 - daylight * clamp(skyExposure, 0.0, 1.0) * clamp(sunVisibility, 0.0, 1.0);
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

// Apply the summed, individually shadowed radiance ONCE, using the remaining
// brightness headroom. A hard clamp after adding to ambient flattens nearby
// overlapping lights to the same value and erases their shadow contrast.
// This approaches the ceiling smoothly; ambient/sun lighting is never reduced.
vec3 applyPointLight(vec3 lit, vec3 albedo, vec3 torch) {
    vec3 headroom = max(albedo * POINT_LIGHT_MAX - lit, vec3(0.0));
    return lit + headroom * (pointLightSoftCap(torch) / POINT_LIGHT_MAX);
}

vec3 pointLightContribution(vec3 worldPos, vec3 normal, float weight) {
    if (weight <= 0.0 || u_pointLightCount == 0) return vec3(0.0);
    vec3 sum = vec3(0.0);
    for (int i = 0; i < MAX_POINT_LIGHTS; i++) {
        if (i >= u_pointLightCount) break;
        vec4 light = u_pointLightPos[i];
        vec3 toLight = light.xyz - worldPos;
        float distSq = dot(toLight, toLight);
        if (distSq >= light.w * light.w) continue;
        float dist = sqrt(distSq);
        vec3 l = toLight / max(dist, 1e-4);
        float diffuse = max(dot(normal, l), 0.0);
        float x = 1.0 - dist / light.w;
        float falloff = x * x * (3.0 - 2.0 * x);   // smoothstep(0,1,x)
        falloff *= falloff;                         // tighter core, soft tail
        int slot = int(u_pointLightColor[i].w);
        float visibility = diffuse > 0.0 ? pointShadowVisibility(worldPos, normal, light.xyz, light.w, slot) : 0.0;
        float indirect = pointIndirectLight(worldPos, normal, light.xyz, slot)
                * smoothstep(0.0, 2.0, light.w - dist);
        // Occlusion removes only THIS source's contribution. Multiplying shadow
        // factors together would incorrectly darken other lights and ambient.
        // Reflected light has its own obstruction-aware path; the direct shadow
        // must not erase it when the torch itself is hidden around a corner.
        sum += u_pointLightColor[i].rgb * (falloff * diffuse * visibility + indirect);
    }
    return sum * weight;
}
