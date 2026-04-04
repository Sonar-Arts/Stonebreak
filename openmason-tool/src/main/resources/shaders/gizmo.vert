#version 330 core

// Input vertex attributes
layout(location = 0) in vec3 aPosition;  // Local position
layout(location = 1) in vec3 aColor;     // Vertex color

// Uniforms
uniform mat4 uModelMatrix;        // Gizmo world transform
uniform mat4 uViewProjection;     // View * Projection
uniform float uIntensity;         // Highlight intensity multiplier
uniform float uAlpha;             // Per-draw-call opacity (0.0 - 1.0)

// Output to fragment shader
out vec3 vColor;
out float vIntensity;
out float vAlpha;

void main() {
    // Transform vertex position to clip space
    gl_Position = uViewProjection * uModelMatrix * vec4(aPosition, 1.0);

    // Pass base color and intensity separately for fragment shader processing
    vColor = aColor;
    vIntensity = uIntensity;
    vAlpha = uAlpha;
}
