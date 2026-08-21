#version 330 core
// Dedicated water shader — vertex stage.
//
// Consumes the water mesh emitted by MmsCcoAdapter.addWaterBlockWithCulling.
// Attribute slots reuse the MmsBufferLayout locations with WATER semantics:
//   location 1 (tex)   = face-local UV in [0,1] (V downward on side faces);
//                        currently unread by the fragment stage, which derives
//                        flow coordinates in world space instead
//   location 3 (flags) = x: surface-height fraction (0..0.875, sewn corner
//                        heights baked by MmsWaterGenerator), y: falling flag,
//                        z: source flag, w: light (currently 1.0)
// Positions are world-space (chunk meshes carry no model matrix).
layout (location = 0) in vec3 aPos;
layout (location = 1) in vec2 aUV;
layout (location = 2) in vec3 aNormal;
layout (location = 3) in vec4 aFlags;
// Per-mesh origin + position scale (compact vertex formats; identity otherwise).
// w < -2.5 = pulled water quads (MmsWaterQuadCodec) read from u_quads by gl_VertexID.
layout (location = 5) in vec4 aOrigin;
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

void pullWaterQuad(out vec3 localPos, out vec2 uv, out vec3 nrm, out vec4 flags, out bool sheet) {
    int qi = gl_VertexID >> 2;
    int corner = gl_VertexID & 3;
    uvec4 q = texelFetch(u_quads, qi);
    uint w0 = q.x;
    int face = int((w0 >> 25u) & 7u);
    float falling = float((w0 >> 28u) & 1u);
    float source = float((w0 >> 29u) & 1u);
    sheet = ((w0 >> 30u) & 1u) != 0u;
    float w = float(q.w & 15u) + 1.0;
    float h = float((q.w >> 4u) & 15u) + 1.0;
    vec3 c = QUAD_CORNER[face * 4 + corner];
    int ua = QUAD_UAXIS[face];
    int va = QUAD_VAXIS[face];
    float a = c[ua];
    float b = c[va];
    vec3 off = c;
    off[ua] = a * w;
    off[va] = b * h;
    // Vertex Y comes from the record (1/128 block from one block below the cell).
    float vy = float((q.y >> (uint(corner) * 8u)) & 255u) / 128.0 - 1.0;
    localPos = vec3(float(w0 & 255u) + off.x, float((w0 >> 8u) & 511u) + vy, float((w0 >> 17u) & 255u) + off.z);
    uv = vec2(a, face >= 2 ? 1.0 - b : b);
    nrm = QUAD_NORMAL[face];
    float surface = float((q.z >> (uint(corner) * 8u)) & 255u) / 255.0;
    flags = vec4(surface, falling, source, 1.0);
}

uniform mat4 uProjection;
uniform mat4 uView;
uniform float uTime;
uniform bool uWavesEnabled;
// Distance (blocks) at which the wave amplitude reaches zero — the near-chunk
// range edge, where flat FastLOD sea sheets take over. Fades in over the outer 40%.
uniform float uWaveFadeEnd;
uniform vec3 uCameraPos;

out vec3 vWorldPos;
out vec3 vNormal;
out vec2 vUV;
out float vFalling;
out float vSource;
out float vSurfaceHeight;

// Sum of directional sine waves (height-only — no horizontal displacement, since the
// CPU-side corner-sewing in MmsWaterGenerator only guarantees adjacent blocks agree on
// shared-corner *height*; per-vertex horizontal displacement here would reopen seams
// between blocks). Each term has its own direction, wavelength, amplitude, and speed so
// the surface doesn't read as obviously periodic/axis-aligned. Total amplitude is kept
// close to the old 2-term wave's ~0.16 so it stays within MAX_WAVE_DELTA's clamp.
float gerstnerHeight(vec2 xz, float t) {
    float h = 0.0;
    h += 0.055 * sin(dot(xz, vec2(0.800,  0.600)) * 0.45 + t * 1.20);
    h += 0.040 * sin(dot(xz, vec2(-0.352, 0.936)) * 0.70 + t * 1.65);
    h += 0.030 * sin(dot(xz, vec2(0.981, -0.196)) * 0.30 + t * 0.85);
    h += 0.020 * sin(dot(xz, vec2(-0.555,-0.832)) * 1.10 + t * 2.10);
    h += 0.015 * sin(dot(xz, vec2(0.148,  0.989)) * 1.60 + t * 2.60);
    return h;
}

void main() {
    vec3 pos;
    vec2 uvIn;
    vec3 nrmIn;
    vec4 flagsIn;
    bool sheet = false;
    if (aOrigin.w < -2.5) {
        pullWaterQuad(pos, uvIn, nrmIn, flagsIn, sheet);
        pos += aOrigin.xyz;
    } else {
        pos = aOrigin.xyz + aPos * aOrigin.w;
        uvIn = aUV;
        nrmIn = aNormal;
        flagsIn = aFlags;
    }
    float surfH = flagsIn.x;
    float falling = flagsIn.y;

    // GPU-side wave displacement (no remesh for waves). World-space seamless
    // functions; constants ported verbatim from the old world-shader water
    // block so seam behavior is unchanged. Falling columns are full-height
    // sheets — they skip the vertical wave.
    // LOD sea sheets skip the displacement: merged rectangles of different sizes
    // would interpolate the wave differently along a shared edge and open seams.
    if (uWavesEnabled && falling < 0.5 && !sheet) {
        const float MIN_WATER_SURFACE = 0.125;
        const float MAX_WAVE_DELTA = 0.18;
        float wave = gerstnerHeight(pos.xz, uTime);
        if (uWaveFadeEnd > 0.0) {
            float dist = length(pos.xz - uCameraPos.xz);
            wave *= 1.0 - smoothstep(uWaveFadeEnd * 0.6, uWaveFadeEnd, dist);
        }

        bool isTopFace = nrmIn.y > 0.5;
        bool isBottomFace = nrmIn.y < -0.5;
        if (isTopFace) {
            // Top faces ride the wave without dipping below adjacent sides.
            float blockBase = floor(pos.y + 0.0001);
            float minAllowed = blockBase + max(MIN_WATER_SURFACE, surfH - MAX_WAVE_DELTA);
            float maxAllowed = blockBase + min(0.875, surfH + MAX_WAVE_DELTA);
            pos.y = clamp(blockBase + surfH + wave, minAllowed, maxAllowed);
        } else if (!isBottomFace) {
            // Stretch side faces so their top edge follows the displaced surface.
            float blockBase = floor(pos.y + 0.0001);
            float normalizedHeight = 0.0;
            if (surfH > 0.0001) {
                normalizedHeight = clamp((pos.y - blockBase) / surfH, 0.0, 1.0);
            }
            float minAllowed = blockBase + max(MIN_WATER_SURFACE, surfH - MAX_WAVE_DELTA);
            float maxAllowed = blockBase + surfH + MAX_WAVE_DELTA;
            float displacedTopY = clamp(blockBase + surfH + wave, minAllowed, maxAllowed);
            // Bottom vertices (normalizedHeight 0) keep their meshed position —
            // the CPU clips side-face bottoms to the neighbor column's water
            // surface, and snapping them to blockBase would reopen that seam.
            float target = mix(pos.y, displacedTopY, normalizedHeight);
            pos.y = max(target, blockBase + normalizedHeight * MIN_WATER_SURFACE);
        }
    }

    gl_Position = uProjection * uView * vec4(pos, 1.0);
    vWorldPos = pos;
    vNormal = nrmIn;
    vUV = uvIn;
    vFalling = falling;
    vSource = flagsIn.z;
    vSurfaceHeight = surfH;
}
