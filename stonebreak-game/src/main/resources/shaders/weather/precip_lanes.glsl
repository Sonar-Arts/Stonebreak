// The lanes rain and snow fall in: one thin lane per slot of a grid fixed in
// CLOUD space, so the pattern rides the wind with the cloud above it. Shared
// by the pass that asks the cloud field about each lane (precip_lane_field.frag)
// and the pass that draws them (precip.vert), which must agree on where every
// lane is. The includer declares u_cloudOffset (cloud_field.glsl does).

uniform vec3 uCameraPos;    // render space
uniform float uLaneSpacing; // blocks between lane slots, in cloud space
uniform int uLaneGrid;      // slots per side of the grid around the camera
uniform int uLanePeriod;    // slots per wrap of u_cloudOffset: the slot hash repeats with it

// Integer hash (pcg2d, Jarzynski & Olano 2020).
uvec2 pcg2d(uvec2 v) {
    v = v * 1664525u + 1013904223u;
    v.x += v.y * 1664525u;
    v.y += v.x * 1664525u;
    v = v ^ (v >> 16u);
    v.x += v.y * 1664525u;
    v.y += v.x * 1664525u;
    v = v ^ (v >> 16u);
    return v;
}

struct Lane {
    vec2 xz;    // render space
    float seed; // 0..1, fixed for the lane's slot
};

// Lane `index` (0..uLaneGrid-1 on each axis) of the grid centred on the camera.
Lane laneAt(ivec2 index) {
    ivec2 slot = ivec2(floor((uCameraPos.xz + u_cloudOffset) / uLaneSpacing)) + index - uLaneGrid / 2;
    // Wrapped with the offset, so a lane keeps its seed when the offset wraps.
    // (GLSL's % is undefined for negative operands, so floor division.)
    ivec2 wrapped = slot - uLanePeriod * ivec2(floor(vec2(slot) / float(uLanePeriod)));
    uvec2 h = pcg2d(uvec2(wrapped));
    Lane lane;
    lane.xz = (vec2(slot) + vec2(h >> 8u) / 16777216.0) * uLaneSpacing - u_cloudOffset;
    lane.seed = float((h.x ^ h.y) >> 8u) / 16777216.0;
    return lane;
}
