#version 330 core

layout (location = 0) in vec3 aPos;

uniform mat4 projectionMatrix;
uniform mat4 viewMatrix;
uniform vec3 cameraPosition;

out vec3 worldPos;
out vec3 viewDirection;
out vec3 cameraPos;

void main() {
    // Create large sky dome around camera
    vec3 skyPos = cameraPosition + aPos * 1000.0;
    
    // Pass world position for fragment shader calculations
    worldPos = skyPos;
    cameraPos = cameraPosition;
    
    // Calculate view direction from camera to sky position
    viewDirection = normalize(skyPos - cameraPosition);
    
    // Transform to screen space
    gl_Position = projectionMatrix * viewMatrix * vec4(skyPos, 1.0);
    
    // Ensure sky is always at maximum depth
    gl_Position = gl_Position.xyww;
}