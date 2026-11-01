#version 330 core

in vec2 vUv;

out vec4 fragColor;

uniform sampler2D depthTex;
uniform vec2 sunUv;
uniform float aspect; // width / height, keeps the glow circular
uniform sampler2D skyTransmittance; // alpha = the sky's light that reaches the eye (cloud, rain)
uniform bool hasTransmittance;
uniform vec3 sunColor;              // sunlight's colour, reddening as the sun sets

// The sun disc's tint over sunlight's colour, as in sky.frag
const vec3 SUN_DISC = vec3(1.0, 0.95, 0.7);

void main() {
    // The sky leaves the cleared depth of exactly 1.0, so sky vs. occluder is a
    // pure depth test - no geometry re-render needed.
    float depth = texture(depthTex, vUv).r;
    float sky = step(0.999999, depth);
    if (hasTransmittance) sky *= texture(skyTransmittance, vUv).a;

    vec2 delta = (vUv - sunUv) * vec2(aspect, 1.0);
    float glow = exp(-dot(delta, delta) * 14.0);

    fragColor = vec4(SUN_DISC * sunColor * sky * glow, 1.0);
}
