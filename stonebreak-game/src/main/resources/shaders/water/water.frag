#version 330 core
// Dedicated water shader — fragment stage.
//
// Fully procedural surface (domain-warped value-noise fbm): no texture binds,
// no CPU tile regeneration. Top/bottom faces sample the pattern in world XZ so
// it is seamless across faces and chunks; side faces sample world-space
// coordinates along the face plane (U = horizontal world axis, V = world Y),
// so wide waterfalls are seamless across neighboring columns — the pattern
// scrolls downward, fast and vertically streaked on falling sheets, gentle on
// ordinary flowing sides. Fine detail, normal perturbation and specular all
// fade with distance to prevent shimmer/aliasing. Fresnel-style view-angle
// alpha: looking straight down is see-through, grazing angles read as a
// reflective sheet. Culling is disabled by the WaterRenderer, so
// gl_FrontFacing flips the normal for underwater views.
in vec3 vWorldPos;
in vec3 vNormal;
in float vFalling;
in float vSource;
in float vSurfaceHeight;

uniform vec3 uSunDirection;
uniform float uAmbientLight;
uniform vec3 uCameraPos;
uniform float uTime;
uniform bool uWavesEnabled;

out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

float fbm(vec2 p) {
    return vnoise(p) * 0.65 + vnoise(p * 2.13 + vec2(17.7, 9.2)) * 0.35;
}

void main() {
    float t = uWavesEnabled ? uTime : 0.0;
    bool horizontal = abs(vNormal.y) > 0.5;

    // Pattern domain per face kind.
    vec2 p;
    if (horizontal) {
        // Top/bottom: world-space XZ with a slow directional drift.
        p = vWorldPos.xz * 0.35 + vec2(t * 0.060, t * 0.045);
    } else {
        // Sides: world-space face coords — U runs along the face plane,
        // V is world height. Continuous across adjacent blocks, so a wide
        // waterfall shows one unbroken pattern instead of per-column tiles.
        // Sampling V + t makes features flow DOWNWARD.
        float u = abs(vNormal.x) > 0.5 ? vWorldPos.z : vWorldPos.x;
        float scroll = vFalling > 0.5 ? 2.2 : 0.35;
        vec2 q = vec2(u, vWorldPos.y + t * scroll);
        if (vFalling > 0.5) {
            // Stretch the lattice vertically into falling rivulet streaks.
            q = vec2(q.x * 2.4, q.y * 0.55);
        }
        p = q * 1.5;
    }

    // Fine detail aliases into shimmer at distance — fade it out.
    float dist = length(uCameraPos - vWorldPos);
    float detail = clamp(1.0 - dist / 72.0, 0.0, 1.0);

    // Domain warp: bends the noise lattice so the pattern reads as ripples
    // and rivulets rather than grid-aligned blobs. The warp doubles as the
    // surface slope for normal perturbation (no extra noise taps).
    vec2 warp = vec2(fbm(p + vec2(0.0, 3.7)), fbm(p + vec2(5.2, 1.3))) - 0.5;
    float n = fbm(p + warp * 0.9);
    float n2 = fbm(p * 2.3 - warp * 0.6 + vec2(3.1, -2.4) + t * 0.05);
    float pattern = clamp(mix(n, n * 0.68 + n2 * 0.32, detail), 0.0, 1.0);

    vec3 N = normalize(vNormal + vec3(warp.x, 0.0, warp.y) * (0.15 + 0.75 * detail));
    if (!gl_FrontFacing) {
        N = -N;
    }

    // Tuned to land near the legacy water tile (~RGB 45,155,232) once the
    // ambient+diffuse lighting factor (~1.15 at noon) is applied.
    vec3 deep = vec3(0.12, 0.36, 0.62);
    vec3 shallow = vec3(0.22, 0.58, 0.88);
    vec3 baseColor = mix(deep, shallow, pattern);

    vec3 L = normalize(uSunDirection);
    vec3 V = normalize(uCameraPos - vWorldPos);

    float ambient = uAmbientLight * 0.55;
    float diffuse = max(dot(N, L), 0.0) * 0.6 * uAmbientLight;
    vec3 H = normalize(L + V);
    float spec = pow(max(dot(N, H), 0.0), 64.0) * 0.6 * uAmbientLight * (0.35 + 0.65 * detail);

    // White rivulet highlights on falling sheets.
    float streaks = smoothstep(0.60, 0.85, n) * vFalling * 0.30 * uAmbientLight;

    // Fresnel-style soft-edge transparency.
    float fres = pow(1.0 - max(dot(N, V), 0.0), 3.0);
    float alpha = mix(0.52, 0.88, fres);
    // Falling columns read better slightly denser; streaks denser still.
    alpha = max(alpha, vFalling * 0.62);
    alpha = clamp(alpha + streaks * 0.4, 0.0, 0.92);

    vec3 color = baseColor * (ambient + diffuse) + vec3(1.0) * spec + vec3(streaks);
    fragColor = vec4(color, alpha);
}
