// Matches IndirectLightAtlas: sixteen 23^3 volumes, tiled 4x4 in XY.
uniform sampler3D u_pointIndirectMap;
uniform int u_pointIndirectEnabled;

float pointIndirectLight(vec3 worldPos, vec3 normal, vec3 lightPos, int slot) {
    if (u_pointIndirectEnabled == 0) return 0.0;
    vec3 origin = floor(lightPos) - vec3(11.0);
    vec3 p = worldPos + normal * 0.05 - origin;
    if (any(lessThan(p, vec3(0.5))) || any(greaterThan(p, vec3(22.5)))) return 0.0;
    // Clamp inside this volume's texel centers; filtering must never bleed into another source.
    vec3 tile = vec3(float(slot % 4) * 23.0, float(slot / 4) * 23.0, 0.0);
    return texture(u_pointIndirectMap, (tile + p) / vec3(92.0, 92.0, 23.0)).r;
}
