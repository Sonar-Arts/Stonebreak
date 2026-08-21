#version 330 core
layout (location=0) in vec3 position;
layout (location=1) in vec2 texCoord;
layout (location=2) in vec3 normal;
// Packed flags attribute: x=LOD-water (FastLOD sea sheets — shaded by
// the procedural distant-water branch; near water has its own mesh +
// shader), y=alphaTest, z=translucent, w=light.
// GL provides this as a normalized [0,1] vec4 from 4 unsigned bytes — saves
// 12 bytes per vertex compared to 4 separate float attributes.
layout (location=3) in vec4 aFlags;
// Texture-array layer index. Unbound VAOs (text/UI) read 0 — harmless.
layout (location=4) in float aLayer;
// Per-mesh origin + position scale for compact vertex formats (MmsVertexFormat):
// a divisor-1 attribute on the mesh VAO, so positions arrive as fixed-point
// offsets and are rebuilt here. VAOs that don't enable it (UI, items, legacy
// chunk meshes) read the generic default (0,0,0,1) = identity.
layout (location=5) in vec4 aOrigin;
out vec2 outTexCoord;
out vec3 outNormal;
out vec3 fragPos;
out float v_isWater;
out float v_isAlphaTested;
out float v_isTranslucent;
out float v_light;
out float v_layer;
out float v_viewDepth;
uniform mat4 projectionMatrix;
uniform mat4 viewMatrix;
uniform mat4 modelMatrix;
uniform bool u_transformUVsForItem;
uniform vec2 u_atlasUVOffset;
uniform vec2 u_atlasUVScale;
uniform bool u_isUIElement;
// ── Vertex pulling (MmsVertexFormat.QUAD16) ──────────────────────────────
// When a VAO's origin attribute carries w < 0 the mesh has NO per-vertex
// attributes: each quad is one RGBA32UI texel (MmsQuadCodec) and this stage
// rebuilds corner (gl_VertexID & 3) of quad (gl_VertexID >> 2). Tables mirror
// MmsCuboidGenerator.FACE_VERTEX_OFFSETS / FACE_NORMALS exactly.
uniform usamplerBuffer u_quads;
const vec3 QUAD_CORNER[24] = vec3[24](
    vec3(0,1,1), vec3(1,1,1), vec3(1,1,0), vec3(0,1,0),   // 0 top    (+Y)
    vec3(0,0,0), vec3(1,0,0), vec3(1,0,1), vec3(0,0,1),   // 1 bottom (-Y)
    vec3(1,0,0), vec3(0,0,0), vec3(0,1,0), vec3(1,1,0),   // 2 north  (-Z)
    vec3(0,0,1), vec3(1,0,1), vec3(1,1,1), vec3(0,1,1),   // 3 south  (+Z)
    vec3(1,0,1), vec3(1,0,0), vec3(1,1,0), vec3(1,1,1),   // 4 east   (+X)
    vec3(0,0,0), vec3(0,0,1), vec3(0,1,1), vec3(0,1,0));  // 5 west   (-X)
const vec3 QUAD_NORMAL[6] = vec3[6](
    vec3(0,1,0), vec3(0,-1,0), vec3(0,0,-1), vec3(0,0,1), vec3(1,0,0), vec3(-1,0,0));
const int QUAD_UAXIS[6] = int[6](0, 0, 0, 0, 2, 2);
const int QUAD_VAXIS[6] = int[6](2, 2, 1, 1, 1, 1);

// Decodes the pulled quad corner into the same five values the attribute
// path supplies. Positions are relative to aOrigin.xyz.
void pullQuad(out vec3 localPos, out vec2 uv, out vec3 nrm, out vec4 flags, out float layer) {
    int qi = gl_VertexID >> 2;
    int corner = gl_VertexID & 3;
    uvec4 q = texelFetch(u_quads, qi);
    uint w0 = q.x;
    uint w1 = q.y;
    uint w2 = q.z;
    int face = int((w0 >> 25u) & 7u);
    float w = float((w0 >> 28u) & 15u) + 1.0;
    float h = float(w1 & 15u) + 1.0;
    int orient = int((w1 >> 4u) & 7u);
    vec3 c = QUAD_CORNER[face * 4 + corner];
    int ua = QUAD_UAXIS[face];
    int va = QUAD_VAXIS[face];
    float a = c[ua] * w;
    float b = c[va] * h;
    vec3 off = c;
    off[ua] = a;
    off[va] = b;
    // Partial-height cubes (snow layers): word3 lowers the top / shortens the sides.
    uint w3 = q.w;
    if (face == 0) {
        off.y -= float(w3 & 15u) / 8.0;
    } else if (face >= 2 && (w3 & 240u) != 0u) {
        off.y *= float((w3 >> 4u) & 15u) / 8.0;
    }
    localPos = vec3(float(w0 & 255u), float((w0 >> 8u) & 511u), float((w0 >> 17u) & 255u)) + off;
    float u0 = float(orient & 1);
    float v0 = float((orient >> 1) & 1);
    bool swap = (orient & 4) != 0;
    vec2 du = swap ? vec2(0.0, 1.0 - 2.0 * v0) : vec2(1.0 - 2.0 * u0, 0.0);
    vec2 dv = swap ? vec2(1.0 - 2.0 * u0, 0.0) : vec2(0.0, 1.0 - 2.0 * v0);
    uv = vec2(u0, v0) + a * du + b * dv;
    nrm = QUAD_NORMAL[face];
    float light = float((w2 >> (uint(corner) * 8u)) & 255u) / 255.0;
    flags = vec4(0.0, float((w1 >> 7u) & 1u), float((w1 >> 8u) & 1u), light);
    layer = float((w1 >> 9u) & 65535u);
}

// FastLOD pulled quads (MmsLodQuadCodec): aOrigin.w < -1.5. Same corner tables;
// half-block y/w/h, unit UVs, and four octahedral corner normals when smooth.
vec3 lodOctDecode(uint p) {
    vec2 e = vec2(float(p & 255u), float((p >> 8u) & 255u)) / 254.0 * 2.0 - 1.0;
    vec3 n = vec3(e.x, 1.0 - abs(e.x) - abs(e.y), e.y);
    if (n.y < 0.0) {
        vec2 s = vec2(n.x >= 0.0 ? 1.0 : -1.0, n.z >= 0.0 ? 1.0 : -1.0);
        n.xz = (1.0 - abs(n.zx)) * s;
    }
    return normalize(n);
}

void pullLodQuad(out vec3 localPos, out vec2 uv, out vec3 nrm, out vec4 flags, out float layer) {
    int qi = gl_VertexID >> 2;
    int corner = gl_VertexID & 3;
    uvec4 q = texelFetch(u_quads, qi);
    uint w0 = q.x;
    uint w1 = q.y;
    float x = float(w0 & 511u) - 8.0;
    float z = float((w0 >> 9u) & 511u) - 8.0;
    float y = float((w0 >> 18u) & 511u) * 0.5;
    int face = int((w0 >> 27u) & 7u);
    bool smoothNormals = ((w0 >> 30u) & 1u) != 0u;
    float light = float(w0 >> 31u);
    float w = float(w1 & 63u) * 0.5;
    float h = float((w1 >> 6u) & 1023u) * 0.5;
    layer = float((w1 >> 16u) & 32767u);
    float alpha = float(w1 >> 31u);
    vec3 c = QUAD_CORNER[face * 4 + corner];
    int ua = QUAD_UAXIS[face];
    int va = QUAD_VAXIS[face];
    float a = c[ua];
    float b = c[va];
    vec3 off = vec3(0.0); // LOD records hold the plane coordinate: no normal-axis offset
    off[ua] = a * w;
    off[va] = b * h;
    localPos = vec3(x, y, z) + off;
    uv = vec2(a, b);
    if (smoothNormals) {
        uint pairWord = corner < 2 ? q.z : q.w;
        nrm = lodOctDecode((pairWord >> (uint(corner & 1) * 16u)) & 65535u);
    } else {
        nrm = QUAD_NORMAL[face];
    }
    flags = vec4(0.0, alpha, 0.0, light);
}

void main() {
    vec3 localPos;
    vec2 uv;
    vec3 nrm;
    vec4 flags;
    float layer;
    if (aOrigin.w < -1.5) {
        pullLodQuad(localPos, uv, nrm, flags, layer);
        localPos += aOrigin.xyz;
    } else if (aOrigin.w < 0.0) {
        pullQuad(localPos, uv, nrm, flags, layer);
        localPos += aOrigin.xyz;
    } else {
        localPos = aOrigin.xyz + position * aOrigin.w;
        uv = texCoord;
        nrm = normal;
        flags = aFlags;
        layer = aLayer;
    }
    gl_Position = projectionMatrix * viewMatrix * modelMatrix * vec4(localPos, 1.0);
    if (u_transformUVsForItem) {
        outTexCoord = u_atlasUVOffset + uv * u_atlasUVScale;
    } else {
        outTexCoord = uv;
    }
    outNormal = nrm;
    fragPos = (modelMatrix * vec4(localPos, 1.0)).xyz;
    // Positive view-space distance — drives shadow cascade selection.
    v_viewDepth = -(viewMatrix * vec4(fragPos, 1.0)).z;
    v_isWater = flags.x;
    v_isAlphaTested = flags.y;
    v_isTranslucent = flags.z;
    v_light = flags.w;
    v_layer = layer;
}
