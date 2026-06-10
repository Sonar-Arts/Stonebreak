#version 330 core

in vec2 vUv;

out vec4 fragColor;

uniform sampler2D depthTex;
uniform vec2 sunUv;
uniform float aspect; // width / height, keeps the glow circular

// Matches the sun disc color in sky.frag
const vec3 SUN_COLOR = vec3(1.0, 0.95, 0.7);

void main() {
    // The sky dome writes depth exactly 1.0 (gl_Position.xyww), so sky vs. occluder
    // is a pure depth test - no geometry re-render needed.
    float depth = texture(depthTex, vUv).r;
    float sky = step(0.999999, depth);

    vec2 delta = (vUv - sunUv) * vec2(aspect, 1.0);
    float glow = exp(-dot(delta, delta) * 14.0);

    fragColor = vec4(SUN_COLOR * sky * glow, 1.0);
}
