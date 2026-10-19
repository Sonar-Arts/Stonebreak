#version 330 core

// Temporal accumulation of the half-resolution cloud march. The march dithers
// its first step differently every frame; blending each frame into the
// reprojected history averages that grain away, where a fixed per-pixel
// dither left a stripe pattern no spatial filter could hide.
//
// Reprojection is camera-relative, so a render-origin rebase between frames
// changes nothing: a pixel's point is its ray at the distance the march found
// its cloud at, moved into last frame's camera by the world-space camera
// delta. (A guessed distance, the slab's entry or a fixed one inside the slab,
// put far clouds hundreds of blocks too near: climbing or sinking dragged the
// history along with the camera, so clouds bobbed and smeared.) History is
// clamped to the current neighbourhood, so what reprojects wrongly (parallax,
// drift, disocclusion) cannot ghost. Texels the scene blocks before the slab
// (a leaf, a block edge) are left out of that clamp: they read as clear sky, and
// with them in the box, history fetched from where a leaf stood last frame
// passed the clamp and drew a cloud-free outline around foliage while moving.
// Nor is history read where the scene hid the slab last frame: those texels
// hold clear sky, and inside noisy or thin cloud the clamp box is wide enough
// to let it through, so the outline survived the clamp fix and faded only once
// the camera stopped. A mask of where history is cloud records that.

in vec2 vUv;
layout(location = 0) out vec4 fragColor;
layout(location = 1) out float visible; // 1 = this texel saw the slab, 0 = the scene hid it

uniform sampler2D u_current;        // this frame's march: rgb light, a transmittance
uniform sampler2D u_cloudDistance;  // this frame's march: distance to the cloud, < 0 = none
uniform sampler2D u_history;        // last frame's result
uniform sampler2D u_historyVisible; // last frame's `visible`
uniform bool u_historyValid;
uniform mat4 u_invViewProj;         // render space, this frame
uniform vec3 u_camPos;              // render space, this frame
uniform mat4 u_prevViewProjRel;     // last frame's projection * view rotation (camera at origin)
uniform vec3 u_camDelta;            // camera world position now minus last frame's
uniform float u_slabBottom;
uniform float u_slabTop;

// Share of each frame that is history: about ten frames are averaged.
const float HISTORY_WEIGHT = 0.9;
// Distance a cloudless ray is reprojected at when it never enters the slab,
// or starts inside it.
const float FAR_DISTANCE = 2000.0;
// clouds_march.frag's cloudDistance for a texel the scene hides the slab behind.
const float OCCLUDED = -2.0;

bool occluded(float cloudDistance) {
    return cloudDistance <= OCCLUDED + 0.5;
}

// Bilinear history at uv over only the texels that saw the slab; a < 0 when
// none did.
vec4 visibleHistory(vec2 uv) {
    ivec2 size = textureSize(u_history, 0);
    vec2 pos = uv * vec2(size) - 0.5;
    ivec2 base = ivec2(floor(pos));
    vec2 f = pos - vec2(base);
    vec4 sum = vec4(0.0);
    float weight = 0.0;
    for (int y = 0; y <= 1; y++) {
        for (int x = 0; x <= 1; x++) {
            ivec2 q = clamp(base + ivec2(x, y), ivec2(0), size - 1);
            float w = (x == 0 ? 1.0 - f.x : f.x) * (y == 0 ? 1.0 - f.y : f.y)
                    * texelFetch(u_historyVisible, q, 0).r;
            sum += texelFetch(u_history, q, 0) * w;
            weight += w;
        }
    }
    return weight > 1e-3 ? sum / weight : vec4(-1.0);
}

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    vec4 current = texelFetch(u_current, px, 0);
    float t = texelFetch(u_cloudDistance, px, 0).r;
    visible = occluded(t) ? 0.0 : 1.0;
    // Hidden behind the scene: nothing to accumulate (the composite ignores it).
    if (!u_historyValid || occluded(t)) { fragColor = current; return; }

    // Neighbourhood bounds of this frame's (noisy) samples.
    ivec2 size = textureSize(u_current, 0);
    // Also the nearest cloud among them: a clear pixel at a cloud's edge may
    // have shown that cloud last frame, so it moves with it.
    vec4 lo = current, hi = current;
    float nearest = 1e30;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            ivec2 q = clamp(px + ivec2(x, y), ivec2(0), size - 1);
            float d = texelFetch(u_cloudDistance, q, 0).r;
            if (occluded(d)) continue;
            vec4 c = texelFetch(u_current, q, 0);
            lo = min(lo, c);
            hi = max(hi, c);
            if (d >= 0.0) nearest = min(nearest, d);
        }
    }

    vec4 far = u_invViewProj * vec4(vUv * 2.0 - 1.0, 1.0, 1.0);
    vec3 rd = normalize(far.xyz / far.w - u_camPos);
    if (t < 0.0) t = nearest;
    if (t > 1e29) {
        // No cloud anywhere near: the slab's entry, as good a guess as any.
        float y = u_camPos.y;
        t = FAR_DISTANCE;
        if (y < u_slabBottom) {
            if (rd.y > 1e-4) t = (u_slabBottom - y) / rd.y;
        } else if (y > u_slabTop) {
            if (rd.y < -1e-4) t = (u_slabTop - y) / rd.y;
        }
        t = min(t, FAR_DISTANCE);
    }

    vec4 prevClip = u_prevViewProjRel * vec4(rd * t + u_camDelta, 1.0);
    vec2 prevUv = prevClip.xy / prevClip.w * 0.5 + 0.5;
    if (prevClip.w <= 0.0 || any(lessThan(prevUv, vec2(0.0))) || any(greaterThan(prevUv, vec2(1.0)))) {
        fragColor = current;
        return;
    }
    vec4 history = visibleHistory(prevUv);
    // Just uncovered: the scene hid this point last frame, so start afresh.
    if (history.a < 0.0) { fragColor = current; return; }
    fragColor = mix(current, clamp(history, lo, hi), HISTORY_WEIGHT);
}
