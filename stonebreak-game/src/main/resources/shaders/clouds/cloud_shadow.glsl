// Cloud shadows: how much direct sunlight reaches a point after crossing the
// cloud above it. The same Beer-Lambert rule the clouds are drawn with, so a
// thin wisp only dims the ground a little and a storm tower blacks out the sun.
// Requires cloud_field.glsl. Multiply into DIRECT light only (diffuse,
// specular), never ambient, exactly like csmShadowFactor.

uniform bool u_cloudShadowsEnabled;
uniform vec3 u_cloudSunDir;     // normalized, toward the sun
uniform float u_cloudShadowY;   // altitude the sun ray is projected to

float cloudShadow(vec3 renderPos) {
    if (!u_cloudShadowsEnabled || u_cloudSunDir.y < 0.05) return 1.0;
    // Where this point's ray toward the sun crosses the cloud layer.
    float rise = max(u_cloudShadowY - renderPos.y, 0.0);
    vec2 xz = renderPos.xz + u_cloudSunDir.xz * (rise / u_cloudSunDir.y);
    float clip;
    vec2 shift;
    CloudColumn col = cloudColumnLocal(vec3(xz.x, u_cloudShadowY, xz.y), clip, shift);
    col.sigma *= clip;
    if (col.density <= 0.0 || renderPos.y >= col.top) return 1.0;
    // Path length through the column along the sun ray.
    float path = (col.top - max(col.base, renderPos.y)) / u_cloudSunDir.y;
    return exp(-col.sigma * path);
}
