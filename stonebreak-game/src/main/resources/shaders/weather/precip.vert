#version 330 core

// One rain/snow sheet per block column around the camera: a 1-block-wide
// quad turned to face the camera about the vertical axis, spanning the
// column's ground to the cloud base (or the top of the falling range).

layout (location = 0) in vec2 aCorner;   // (0..1, 0..1)
layout (location = 1) in vec4 aColumn;   // render-space centre x, z; bottom y; top y
layout (location = 2) in vec2 aParams;   // intensity, snow (0 or 1)

uniform mat4 uProjection;
uniform mat4 uView;
uniform vec3 uCameraPos;    // render space
uniform vec2 uRenderOrigin; // so each column's seed is tied to the world, not the origin

out vec2 vUv;        // x across the sheet, y = world height
out vec2 vParams;
out float vSeed;
out float vDist;

void main() {
    vec2 center = aColumn.xy;
    vec2 toCam = uCameraPos.xz - center;
    vec2 facing = length(toCam) > 1e-3 ? normalize(toCam) : vec2(0.0, 1.0);
    vec2 right = vec2(-facing.y, facing.x);
    float y = mix(aColumn.z, aColumn.w, aCorner.y);
    vec2 xz = center + right * (aCorner.x - 0.5);

    vec2 worldColumn = floor(center + uRenderOrigin);
    vSeed = fract(sin(dot(worldColumn, vec2(12.9898, 78.233))) * 43758.5453);
    vUv = vec2(aCorner.x, y);
    vParams = aParams;
    vDist = length(toCam);
    gl_Position = uProjection * uView * vec4(xz.x, y, xz.y, 1.0);
}
