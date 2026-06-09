#version 330 core

in float vShade;
in vec3 vWorldPos;

uniform vec3 cameraPosition;
uniform vec3 cloudColor;  // Time-of-day tinted base color
uniform float cloudAlpha; // Base cloud opacity

out vec4 FragColor;

void main() {
    // Horizontal distance fade so the cloud layer dissolves smoothly into the
    // sky rather than ending at a hard edge of the tiled mesh.
    float dist = length(vWorldPos.xz - cameraPosition.xz);
    // Fully faded well before the 1000-unit far plane to avoid a hard clip edge.
    float fade = 1.0 - smoothstep(550.0, 950.0, dist);
    if (fade <= 0.0) {
        discard;
    }

    // Flat directional shading: top faces bright, sides medium, bottom dark.
    vec3 color = cloudColor * vShade;

    FragColor = vec4(color, cloudAlpha * fade);
}
