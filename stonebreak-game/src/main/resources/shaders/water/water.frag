#version 330 core
// Dedicated water shader — fragment stage.
//
// Fully procedural surface (value-noise fbm + time drift): no texture binds,
// no CPU tile regeneration. Top faces sample the pattern in world XZ so it is
// seamless across faces and chunks; side faces sample face-local UV scrolled
// downward (fast on falling columns, slow on flowing sides). Fresnel-style
// view-angle alpha: looking straight down is see-through, grazing angles read
// as a reflective sheet. Culling is disabled by the WaterRenderer, so
// gl_FrontFacing flips the normal for underwater views.
in vec3 vWorldPos;
in vec3 vNormal;
in vec2 vUV;
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

    // Pattern domain per face kind.
    vec2 p;
    if (abs(vNormal.y) > 0.5) {
        // Top/bottom: world-space XZ with a slow directional drift.
        p = vWorldPos.xz * 0.35 + vec2(t * 0.060, t * 0.045);
    } else {
        // Sides: face-local UV, V scrolls downward — fast for falling
        // columns, gentle for ordinary flowing/side faces. Offset by the
        // block column position so neighboring faces don't repeat.
        float scroll = vFalling > 0.5 ? 1.4 : 0.25;
        p = vec2(vUV.x, vUV.y - t * scroll) * 1.5
          + vec2(dot(floor(vWorldPos.xz + 0.5), vec2(0.7, 0.3)));
    }
    float n = fbm(p);
    float n2 = fbm(p * 1.7 + vec2(3.1, -2.4) + t * 0.03);

    // Cheap analytic normal perturbation (two extra fbm taps).
    float e = 0.15;
    float gx = fbm(p + vec2(e, 0.0)) - n;
    float gz = fbm(p + vec2(0.0, e)) - n;
    vec3 N = normalize(vNormal + vec3(gx, 0.0, gz) * 0.9);
    if (!gl_FrontFacing) {
        N = -N;
    }

    vec3 deep = vec3(0.09, 0.23, 0.42);
    vec3 shallow = vec3(0.20, 0.46, 0.62);
    vec3 baseColor = mix(deep, shallow, clamp(n * 0.75 + n2 * 0.25, 0.0, 1.0));

    vec3 L = normalize(uSunDirection);
    vec3 V = normalize(uCameraPos - vWorldPos);

    float ambient = uAmbientLight * 0.55;
    float diffuse = max(dot(N, L), 0.0) * 0.6 * uAmbientLight;
    vec3 H = normalize(L + V);
    float spec = pow(max(dot(N, H), 0.0), 64.0) * 0.6 * uAmbientLight;

    // Fresnel-style soft-edge transparency.
    float fres = pow(1.0 - max(dot(N, V), 0.0), 3.0);
    float alpha = mix(0.52, 0.88, fres);
    // Falling columns read better slightly denser.
    alpha = max(alpha, vFalling * 0.62);

    vec3 color = baseColor * (ambient + diffuse) + vec3(1.0) * spec;
    fragColor = vec4(color, alpha);
}
