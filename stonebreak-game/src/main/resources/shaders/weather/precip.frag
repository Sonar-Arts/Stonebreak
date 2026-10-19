#version 330 core

// Procedural rain streaks / snowflakes: each lane is a train of segments
// scrolling down the world-height axis. No textures.

in vec2 vUv;
in vec2 vParams;
in float vSeed;
in float vDist;
out vec4 fragColor;

uniform float uTime;
uniform float uAmbient;
uniform float uRadius;      // lanes fade out toward this distance
uniform vec2 uLaneWidth;    // quad width, blocks: rain, snow

void main() {
    float intensity = vParams.x;
    float snow = vParams.y;
    float speed = mix(16.0, 1.5, snow);       // blocks per second
    float period = mix(5.0, 2.2, snow);       // blocks between drops in a lane
    float length_ = mix(0.30, 0.07, snow);    // fraction of the period that is drop
    float width = mix(0.022, 0.08, snow);     // blocks

    float s = vSeed;
    // Blocks from the lane's centre; snowflakes sway as they fall.
    float x = (vUv.x - 0.5) * mix(uLaneWidth.x, uLaneWidth.y, snow);
    x -= snow * 0.12 * sin(uTime * 1.3 + s * 20.0 + vUv.y * 0.5);
    float across = 1.0 - smoothstep(width * 0.5, width, abs(x));
    float v = fract((vUv.y + uTime * speed) / period + s);
    float along = smoothstep(0.0, 0.02, v) * (1.0 - smoothstep(length_ * 0.6, length_, v));

    float alpha = across * along * intensity * mix(0.32, 0.9, snow)
            * (1.0 - smoothstep(uRadius * 0.6, uRadius, vDist));
    if (alpha < 0.01) discard;

    vec3 color = mix(vec3(0.62, 0.68, 0.78), vec3(0.96), snow) * mix(0.25, 1.0, clamp(uAmbient, 0.0, 1.0));
    fragColor = vec4(color, min(alpha, 1.0));
}
