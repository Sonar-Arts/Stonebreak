// Distant point lights — the torches past the shadowed near set in
// point_lights.glsl: beyond its 64-block reach or its 16-light budget, and
// in chunks that have unloaded into the FastLOD ring. Uploaded per frame by
// com.stonebreak.rendering.lighting.DynamicLights (MAX_FAR_LIGHTS there).
// Same falloff and diffuse as the near lights; no shadow or bounce term,
// since neither has a map this far out. Include after point_lights.glsl and
// add the result to the near sum before applyPointLight(), so both share
// one soft cap.
#define MAX_FAR_POINT_LIGHTS 64
uniform int u_farLightCount;
uniform vec4 u_farLightPos[MAX_FAR_POINT_LIGHTS];   // xyz = render-space position, w = radius
uniform vec4 u_farLightColor[MAX_FAR_POINT_LIGHTS]; // rgb = color * intensity

vec3 farLightContribution(vec3 worldPos, vec3 normal, float weight) {
    if (weight <= 0.0 || u_farLightCount == 0) return vec3(0.0);
    vec3 sum = vec3(0.0);
    for (int i = 0; i < MAX_FAR_POINT_LIGHTS; i++) {
        if (i >= u_farLightCount) break;
        vec4 light = u_farLightPos[i];
        vec3 toLight = light.xyz - worldPos;
        float distSq = dot(toLight, toLight);
        if (distSq >= light.w * light.w) continue;
        float dist = sqrt(distSq);
        float diffuse = max(dot(normal, toLight / max(dist, 1e-4)), 0.0);
        sum += u_farLightColor[i].rgb * (pointLightFalloff(dist, light.w) * diffuse);
    }
    return sum * weight;
}
