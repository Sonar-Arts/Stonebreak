#version 330 core

// Rain and snow lanes (precip_lanes.glsl), each a quad turned to face the camera
// about the vertical axis, spanning what precip_lane_field.frag found for it:
// from the underside of the cloud drawn above down to the ground. A dry lane
// collapses to nothing. Far from the camera the quad widens to cover a few
// pixels, so a streak never thins to nothing before it reaches the cloud;
// precip.frag keeps its brightness by thinning its alpha to match.

uniform vec2 u_cloudOffset;
#include "/shaders/weather/precip_lanes.glsl"

layout (location = 0) in vec2 aCorner;   // (0..1, 0..1)

uniform mat4 uProjection;
uniform mat4 uView;
uniform sampler2D uLanes;   // precip_lane_field.frag's output for this ring
uniform vec2 uLaneWidth;    // quad width, blocks: rain, snow
uniform float uPixelAngle;  // radians one screen pixel spans

out vec2 vUv;        // x = blocks from the lane's centre, y = world height
out vec2 vParams;    // intensity, snow
out vec2 vSize;      // quad width, pixel width (blocks) at this point
out float vSeed;
out float vDist;     // horizontal distance to the camera
out float vEyeDist;  // distance to the camera

// Pixels a streak is widened to cover, at least.
const float MIN_PIXELS = 1.5;

void collapse() {
    gl_Position = vec4(2.0, 2.0, 2.0, 1.0); // every corner at one point outside the view
    vUv = vec2(0.0);
    vParams = vec2(0.0);
    vSize = vec2(1.0);
    vSeed = 0.0;
    vDist = 0.0;
    vEyeDist = 0.0;
}

void main() {
    ivec2 index = ivec2(gl_InstanceID % uLaneGrid, gl_InstanceID / uLaneGrid);
    vec4 field = texelFetch(uLanes, index, 0);
    if (field.x <= 0.0) { collapse(); return; }
    Lane lane = laneAt(index);

    vec2 toCam = uCameraPos.xz - lane.xz;
    float dist = length(toCam);
    float snow = field.w;
    float y = mix(field.z, field.y, aCorner.y);
    float eyeDist = length(vec3(lane.xz.x, y, lane.xz.y) - uCameraPos);
    float pixel = eyeDist * uPixelAngle;
    float width = max(mix(uLaneWidth.x, uLaneWidth.y, snow), pixel * MIN_PIXELS * 2.0);
    vec2 facing = dist > 1e-3 ? toCam / dist : vec2(0.0, 1.0);
    vec2 right = vec2(-facing.y, facing.x);
    vec2 xz = lane.xz + right * (aCorner.x - 0.5) * width;

    vUv = vec2((aCorner.x - 0.5) * width, y);
    vParams = vec2(field.x, snow);
    vSize = vec2(width, pixel);
    vSeed = lane.seed;
    vDist = dist;
    vEyeDist = eyeDist;
    gl_Position = uProjection * uView * vec4(xz.x, y, xz.y, 1.0);
}
