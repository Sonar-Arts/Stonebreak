#version 330 core

// Volumetric voxel clouds. Each pixel's view ray walks the cloud map's cells
// with a 2D DDA; inside a cell the cloud is a solid block column, so the
// stretch of ray inside it is exact, not sampled. Light behind the cloud is
// kept by exp(-sigma * distance) — the water shader's Beer-Lambert rule — so
// thin cloud is see-through and thick cloud is opaque.
//
// Output (half resolution): rgb = cloud light, premultiplied; a = how much of
// the scene behind survives. Composite with blend (ONE, SRC_ALPHA).

#include "/shaders/clouds/cloud_field.glsl"

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D u_sceneDepth;
uniform mat4 u_invViewProj;     // render space
uniform vec3 u_camPos;          // render space
uniform vec3 u_sunDir;
uniform vec3 u_sunColor;
uniform vec3 u_ambientColor;
uniform vec3 u_hazeColor;       // distant clouds fade toward this (the sky)
uniform float u_slabBottom;
uniform float u_slabTop;
uniform float u_fadeStart;      // clouds thin out with distance...
uniform float u_fadeEnd;        // ...and are gone here (inside the map's reach)
// Lightning: up to four live flashes, xyz = render-space light point inside
// the storm cloud, w = intensity (0 = unused). Each lights only the cloud
// within FLASH_RADIUS of it.
uniform vec4 u_flashes[4];

const int MAX_CELLS = 384;
const float MIN_TRANSMITTANCE = 0.01;

// Face shades of the old flat clouds, so lit sides still read as blocks.
// Only faces the ray actually enters from clear air get one: walls between
// two cloudy cells are interior and keep the shade of the face the ray came
// in through, so a cloud reads as one solid shape, not a grid of cells.
const float SHADE_TOP = 1.0;
const float SHADE_X = 0.86;
const float SHADE_Z = 0.78;
const float SHADE_BOTTOM = 0.62;

const float FLASH_RADIUS = 110.0;
const vec3 FLASH_COLOR = vec3(0.75, 0.8, 1.0);
// Lowest sun elevation the sunlight path through a cloud is computed for.
const float MIN_SUN_Y = 0.15;

// Lightning light at p: each flash lights the cloud near it, not the whole deck.
float flashAt(vec3 p) {
    float sum = 0.0;
    for (int k = 0; k < 4; k++) {
        vec4 f = u_flashes[k];
        if (f.w > 0.0) sum += f.w * (1.0 - smoothstep(0.0, FLASH_RADIUS, distance(p, f.xyz)));
    }
    return sum;
}

vec3 worldAt(vec2 uv, float depth) {
    vec4 p = u_invViewProj * vec4(vec3(uv, depth) * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

void main() {
    vec3 ro = u_camPos;
    vec3 rd = normalize(worldAt(vUv, 1.0) - ro);

    // How far the ray may go: the opaque scene, or the fade-out distance.
    float depth = texture(u_sceneDepth, vUv).r;
    float tLimit = u_fadeEnd;
    if (depth < 1.0) tLimit = min(tLimit, length(worldAt(vUv, depth) - ro));

    // Clip to the cloud slab.
    float tNear = 0.0, tFar = tLimit;
    if (abs(rd.y) < 1e-5) {
        if (ro.y < u_slabBottom || ro.y > u_slabTop) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
    } else {
        float ta = (u_slabBottom - ro.y) / rd.y;
        float tb = (u_slabTop - ro.y) / rd.y;
        tNear = max(tNear, min(ta, tb));
        tFar = min(tFar, max(ta, tb));
    }
    if (tNear >= tFar) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }

    // 2D DDA over cloud-space cells.
    vec2 pos = (ro.xz + u_cloudOffset) / u_cloudCell;       // in cells
    vec2 dir = rd.xz / u_cloudCell;                          // cells per block of t
    vec2 start = pos + dir * tNear;
    ivec2 cell = ivec2(floor(start));
    ivec2 stepDir = ivec2(sign(dir));
    vec2 invDir = 1.0 / max(abs(dir), vec2(1e-8));
    vec2 tNext = vec2(
        (dir.x >= 0.0 ? (float(cell.x) + 1.0 - pos.x) : (pos.x - float(cell.x))) * invDir.x,
        (dir.y >= 0.0 ? (float(cell.y) + 1.0 - pos.y) : (pos.y - float(cell.y))) * invDir.y);
    // lastAxis: which wall the ray came through (0 = x, 1 = z, -1 = none yet).
    int lastAxis = -1;
    // Whether the ray was still inside cloud when it left the previous cell,
    // and the face it entered that cloud through.
    bool inCloud = false;
    vec3 entryNormal = vec3(0.0, 1.0, 0.0);
    float entryShade = SHADE_TOP;

    float t = tNear;
    float transmittance = 1.0;
    vec3 light = vec3(0.0);

    for (int i = 0; i < MAX_CELLS && t < tFar; i++) {
        float tExit = min(min(tNext.x, tNext.y), tFar);
        CloudColumn col = cloudColumnAt(cell);
        bool leftInCloud = false;
        if (col.density > 0.0) {
            // Where along [t, tExit] the ray is inside the column's height range.
            float lo = t, hi = tExit;
            if (abs(rd.y) < 1e-5) {
                if (ro.y < col.base || ro.y >= col.top) hi = lo;
            } else {
                float ta = (col.base - ro.y) / rd.y;
                float tb = (col.top - ro.y) / rd.y;
                lo = max(lo, min(ta, tb));
                hi = min(hi, max(ta, tb));
            }
            if (hi > lo) {
                bool throughWall = lo <= t + 1e-3 && lastAxis >= 0;
                if (!throughWall) {
                    bool fromAbove = rd.y < 0.0;
                    entryNormal = vec3(0.0, fromAbove ? 1.0 : -1.0, 0.0);
                    entryShade = fromAbove ? SHADE_TOP : SHADE_BOTTOM;
                } else if (!inCloud) {
                    // An exposed side: the cell the ray came from was clear here.
                    if (lastAxis == 0) {
                        entryNormal = vec3(-float(stepDir.x), 0.0, 0.0);
                        entryShade = SHADE_X;
                    } else {
                        entryNormal = vec3(0.0, 0.0, -float(stepDir.y));
                        entryShade = SHADE_Z;
                    }
                }
                leftInCloud = hi >= tExit - 1e-3 && tExit < tFar;

                // Light the stretch where most of what we see comes from: one
                // mean free path in (Beer-Lambert), capped to the stretch.
                float tLit = lo + min(hi - lo, 1.0 / max(col.sigma, 1e-4)) * 0.5;
                float tMid = 0.5 * (lo + hi);
                vec3 p = ro + rd * tLit;
                // Optics from the bilinearly smoothed field, so brightness
                // flows across cells; the blocky shape stays per cell.
                CloudColumn soft = cloudColumnSmooth(p.xz + u_cloudOffset);
                float sigma = 0.5 * (col.sigma + soft.sigma);
                float albedo = soft.density > 0.0 ? 0.5 * (col.albedo + soft.albedo) : col.albedo;
                float softTop = soft.density > 0.0 ? soft.top : col.top;
                float softBase = soft.density > 0.0 ? soft.base : col.base;
                // Sunlight reaching p: through the cloud above it, along the sun.
                // Depends on height in the cloud, not on the cell's walls, so the
                // base is dark and the top bright with no per-cell gradient.
                float sunPath = max(softTop - p.y, 0.0) / max(u_sunDir.y, MIN_SUN_Y);
                float sunT = exp(-sigma * sunPath);
                float facing = 0.5 + 0.5 * max(dot(entryNormal, u_sunDir), 0.0);
                // Skylight: the underside of a thick cloud sees less of the sky.
                float height = clamp((p.y - softBase) / max(softTop - softBase, 1.0), 0.0, 1.0);
                float skyLit = mix(0.7, 1.0, height);
                // Forward scattering: clouds between the eye and the sun glow
                // instead of going black.
                float phase = 1.0 + 1.5 * pow(max(dot(rd, u_sunDir), 0.0), 6.0);
                vec3 lit = albedo * (u_ambientColor * entryShade * skyLit
                        + u_sunColor * sunT * facing * phase);
                lit += FLASH_COLOR * flashAt(p);

                // Aerial perspective, like the terrain's distance fog...
                lit = mix(lit, u_hazeColor, 0.65 * smoothstep(0.0, u_fadeEnd, tMid));
                // ...and clouds thin out toward the fade distance instead of ending in a wall.
                float fade = 1.0 - smoothstep(u_fadeStart, u_fadeEnd, tMid);
                float stepT = exp(-sigma * fade * (hi - lo));
                light += transmittance * (1.0 - stepT) * lit;
                transmittance *= stepT;
                if (transmittance < MIN_TRANSMITTANCE) break;
            }
        }
        inCloud = leftInCloud;
        // Advance to the next cell.
        if (tNext.x < tNext.y) {
            t = tNext.x;
            tNext.x += invDir.x;
            cell.x += stepDir.x;
            lastAxis = 0;
        } else {
            t = tNext.y;
            tNext.y += invDir.y;
            cell.y += stepDir.y;
            lastAxis = 1;
        }
    }

    fragColor = vec4(light, transmittance);
}
