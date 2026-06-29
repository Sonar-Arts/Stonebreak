#version 330 core

// Radial blur light scattering (GPU Gems 3, ch. 13 "Volumetric Light Scattering
// as a Post-Process"). Samples the occlusion mask stepping toward the sun's
// screen position; output is composited additively by the caller.

in vec2 vUv;

out vec4 fragColor;

uniform sampler2D occlusionTex;
uniform vec2 sunUv;
uniform float exposure; // pre-multiplied on CPU by strength * edge fade
uniform float decay;
uniform float density;
uniform float weight;

const int NUM_SAMPLES = 64;

void main() {
    vec2 uv = vUv;
    vec2 deltaUv = (uv - sunUv) * (density / float(NUM_SAMPLES));
    float illumination = decay;
    vec3 color = vec3(0.0);

    for (int i = 0; i < NUM_SAMPLES; i++) {
        uv -= deltaUv;
        color += texture(occlusionTex, uv).rgb * illumination * weight;
        illumination *= decay;
    }

    fragColor = vec4(color * exposure, 1.0);
}
