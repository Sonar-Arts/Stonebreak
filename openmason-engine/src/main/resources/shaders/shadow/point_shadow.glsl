// Six 90-degree depth views per point light; matches PointShadowProjection.
// A single depth array avoids sampler-array indexing restrictions in GLSL 330.
uniform sampler2DArrayShadow u_pointShadowMap;
uniform int u_pointShadowsEnabled;

float pointShadowTap(vec3 d, vec3 normal, float planeDistance, float radius, int slot) {
    vec3 a = abs(d);
    float major;
    vec2 uv;
    vec3 faceNormal; // normal expressed along this face's right, up, forward axes
    int face;
    if (a.x >= a.y && a.x >= a.z) {
        major = a.x;
        face = d.x >= 0.0 ? 0 : 1;
        uv = vec2(d.x >= 0.0 ? -d.z : d.z, -d.y);
        faceNormal = vec3(d.x >= 0.0 ? -normal.z : normal.z, -normal.y,
                          d.x >= 0.0 ? normal.x : -normal.x);
    } else if (a.y >= a.z) {
        major = a.y;
        face = d.y >= 0.0 ? 2 : 3;
        uv = vec2(d.x, d.y >= 0.0 ? d.z : -d.z);
        faceNormal = vec3(normal.x, d.y >= 0.0 ? normal.z : -normal.z,
                          d.y >= 0.0 ? normal.y : -normal.y);
    } else {
        major = a.z;
        face = d.z >= 0.0 ? 4 : 5;
        uv = vec2(d.z >= 0.0 ? d.x : -d.x, -d.y);
        faceNormal = vec3(d.z >= 0.0 ? normal.x : -normal.x, -normal.y,
                          d.z >= 0.0 ? normal.z : -normal.z);
    }
    const float nearPlane = 0.05;
    if (major <= nearPlane || major >= radius) return 1.0;
    uv = uv / major * 0.5 + 0.5;
    // Hardware 2x2 PCF; clamp to the face's texel centers to prevent lit seams.
    vec2 texel = 0.5 / vec2(textureSize(u_pointShadowMap, 0).xy);
    uv = clamp(uv, texel, vec2(1.0) - texel);
    // Intersect this tap's ray with the receiver plane. Offsetting d without this
    // correction puts samples behind the wall itself, producing rings/bands of acne.
    // Perspective depth on a plane is affine in UV, including across cube faces.
    float projectionScale = radius * nearPlane / (radius - nearPlane);
    float inverseDepth = dot(faceNormal, vec3(uv * 2.0 - 1.0, 1.0)) / planeDistance;
    float depth = radius / (radius - nearPlane) - projectionScale * inverseDepth;
    // A hardware PCF tap compares the same reference to four neighboring depths.
    // Cover their receiver-plane slope within one texel, without increasing the
    // world-space normal bias (which would detach genuine contact shadows).
    vec2 depthGradient = -2.0 * projectionScale * faceNormal.xy / planeDistance;
    depth -= dot(abs(depthGradient), texel * 2.0) + 0.000002;
    return texture(u_pointShadowMap, vec4(uv, float(slot * 6 + face), depth));
}

float pointShadowVisibility(vec3 worldPos, vec3 normal, vec3 lightPos, float radius, int slot) {
    if (u_pointShadowsEnabled == 0) return 1.0;
    vec3 d = worldPos + normal * 0.015 - lightPos;
    float distance = length(d);
    if (distance <= 0.05) return 1.0;
    float planeDistance = dot(normal, d);
    if (abs(planeDistance) < 0.0001) return 1.0; // no usable plane at exact tangency
    vec3 direction = d / distance;
    vec3 axis = abs(direction.y) < 0.9 ? vec3(0, 1, 0) : vec3(1, 0, 0);
    vec3 tangent = normalize(cross(direction, axis));
    vec3 bitangent = cross(direction, tangent);
    // Small flame-sized filtering footprint, with a bounded distance-dependent spread.
    // Each tap resolves its own cube face so filtering crosses face seams correctly.
    // This is filtered point lighting, not a full area-light/PCSS simulation.
    float spread = min(0.14, 0.025 + distance * 0.012);
    return 0.4 * pointShadowTap(d, normal, planeDistance, radius, slot)
         + 0.15 * (pointShadowTap(d + tangent * spread, normal, planeDistance, radius, slot)
                 + pointShadowTap(d - tangent * spread, normal, planeDistance, radius, slot)
                 + pointShadowTap(d + bitangent * spread, normal, planeDistance, radius, slot)
                 + pointShadowTap(d - bitangent * spread, normal, planeDistance, radius, slot));
}
