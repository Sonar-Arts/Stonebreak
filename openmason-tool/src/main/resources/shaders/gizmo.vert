#version 330 core

// Input vertex attributes
layout(location = 0) in vec3 aPosition;  // Local position
layout(location = 1) in vec3 aColor;     // Vertex color

// Uniforms
uniform mat4 uModelMatrix;        // Gizmo world transform
uniform mat4 uViewProjection;     // View * Projection
uniform float uIntensity;         // Highlight intensity multiplier

// Output to fragment shader
out vec3 vColor;

void main() {
    // Transform vertex position to clip space
    gl_Position = uViewProjection * uModelMatrix * vec4(aPosition, 1.0);

    // Pass color with intensity multiplier applied
    vColor = aColor * uIntensity;
}
