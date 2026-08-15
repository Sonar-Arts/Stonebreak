#version 330 core

// Input from vertex shader
in vec3 vColor;
in float vIntensity;
in float vAlpha;

// Output fragment color
out vec4 FragColor;

void main() {
    vec3 finalColor;

    if (vIntensity <= 1.0) {
        // Default state: use base color as-is
        finalColor = vColor;
    } else {
        // Hover/active glow: blend toward white instead of oversaturating
        float glowFactor = (vIntensity - 1.0) / 1.5;  // Normalized 0..1 for intensity range 1.0..2.5
        finalColor = mix(vColor, vec3(1.0), glowFactor * 0.4);  // Subtle white blend
    }

    FragColor = vec4(finalColor, vAlpha);
}
