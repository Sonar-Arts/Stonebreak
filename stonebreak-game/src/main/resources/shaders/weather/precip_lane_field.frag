#version 330 core

// Asks the cloud field about every rain lane once per frame, one texel per
// lane, for precip.vert to read: how much falls there, and the span it falls
// through, from the underside of the lowest cloud drawn above it
// (cloud_shape.glsl) down to the ground. Done here rather than in the vertex
// shader because the underside search is dozens of cloud samples, and every
// corner of a lane's quad would repeat it.
//
// Output: x = intensity (0 = dry, the lane is not drawn), y = top, z = bottom
// (render-space heights), w = snow (0..1).

#include "/shaders/clouds/cloud_field.glsl"
#include "/shaders/clouds/cloud_shape.glsl"
#include "/shaders/weather/precip_lanes.glsl"

out vec4 laneOut;

uniform float uRadius;      // lanes reach this far from the camera...
uniform float uInnerRadius; // ...and start this far out (the far ring leaves the near one's lanes to it)
uniform float uBelow;       // and fall no further than this below it
uniform bool uFar;          // true: ground from the coarse relief, snow from uSnow
// Near ring. RG: ground height (very low where unknown), snow (0 or 1), per
// block column; texel (i, j) is the block whose corner is uGroundOrigin + (i, j).
uniform sampler2D uGround;
uniform vec2 uGroundOrigin; // render space
// Far ring: share of the ground around the camera that is snowy.
uniform float uSnow;

// Blocks the underside search filters the cloud's noise over: the full detail
// the march draws at these distances.
const float LANE_FOOTPRINT = 1.0;
// The relief is coarse; a far lane reaches this far below it, and the depth
// test hides whatever ends up underground.
const float FAR_SINK = 12.0;

void main() {
    laneOut = vec4(0.0);
    Lane lane = laneAt(ivec2(gl_FragCoord.xy));
    float dist = length(uCameraPos.xz - lane.xz);
    if (dist > uRadius || dist < uInnerRadius) return;

    CloudSite site = cloudSiteAt(lane.xz);
    float intensity = cloudPrecipitation(site.col);
    // Light rain fills fewer lanes.
    if (intensity <= 0.0 || fract(lane.seed * 31.7) > intensity + 0.25) return;

    float ground, snow;
    if (uFar) {
        ground = min(site.ground, uCameraPos.y) - FAR_SINK;
        snow = uSnow;
    } else {
        ivec2 g = clamp(ivec2(floor(lane.xz - uGroundOrigin)), ivec2(0), textureSize(uGround, 0) - 1);
        vec2 column = texelFetch(uGround, g, 0).rg;
        ground = column.r;
        snow = column.g;
    }
    float bottom = max(ground, uCameraPos.y - uBelow);
    // Nothing drawn above (NO_UNDERSIDE), or the camera above the cloud: no lane.
    float top = cloudUnderside(site, LANE_FOOTPRINT, true);
    if (top <= bottom) return;
    laneOut = vec4(intensity, top, bottom, snow);
}
