#version 330 core

// Input from vertex shader
in vec3 vColor;

// Output fragment color
out vec4 FragColor;

void main() {
    // Simple unlit shading with color from vertex shader
    // Color already has intensity multiplier applied from vertex shader
    FragColor = vec4(vColor, 1.0);
}
