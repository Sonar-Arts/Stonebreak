#version 330 core

// Lays the half-resolution cloud buffer over the scene. Blend (ONE, SRC_ALPHA):
// scene * transmittance + premultiplied cloud light.
//
// Four bilinear taps half a cloud texel off centre make a small tent filter,
// which melts the march's per-pixel step dither into smooth cloud.

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D u_clouds;

void main() {
    vec2 o = 0.5 / vec2(textureSize(u_clouds, 0));
    fragColor = 0.25 * (texture(u_clouds, vUv + vec2(-o.x, -o.y))
            + texture(u_clouds, vUv + vec2(o.x, -o.y))
            + texture(u_clouds, vUv + vec2(-o.x, o.y))
            + texture(u_clouds, vUv + vec2(o.x, o.y)));
}
