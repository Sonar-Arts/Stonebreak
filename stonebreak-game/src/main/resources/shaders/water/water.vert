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

uniform mat4 uProjection;
uniform mat4 uView;
uniform float uTime;
uniform bool uWavesEnabled;

out vec3 vWorldPos;
out vec3 vNormal;
out vec2 vUV;
out float vFalling;
out float vSource;
out float vSurfaceHeight;

void main() {
    float surfH = aFlags.x;
    float falling = aFlags.y;
    vec3 pos = aPos;

    // GPU-side wave displacement (no remesh for waves). World-space seamless
    // functions; constants ported verbatim from the old world-shader water
    // block so seam behavior is unchanged. Falling columns are full-height
    // sheets — they skip the vertical wave.
    if (uWavesEnabled && falling < 0.5) {
        const float MIN_WATER_SURFACE = 0.125;
        const float MAX_WAVE_DELTA = 0.18;
        float s = 0.50;
        float wave = sin(pos.x * s + uTime * 1.5) * cos(pos.z * s * 0.8 + uTime * 1.35) * 0.12
                   + sin((pos.x + pos.z) * s * 1.7 + uTime * 2.0) * 0.04;

        bool isTopFace = aNormal.y > 0.5;
        bool isBottomFace = aNormal.y < -0.5;
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
    vNormal = aNormal;
    vUV = aUV;
    vFalling = falling;
    vSource = aFlags.z;
    vSurfaceHeight = surfH;
}
