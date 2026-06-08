#version 330 core

in vec3 worldPos;
in vec3 viewDirection;
in vec3 cameraPos;

uniform vec3 sunDirection;
uniform vec3 skyColor; // Base sky color from TimeOfDay system

out vec4 FragColor;

vec3 getTimedSkyColor(vec3 direction) {
    // Use full range of elevation (-1 to 1) for smoother transitions
    float elevation = direction.y;

    // Use skyColor uniform as base, then create gradient variants
    // Horizon is brighter, zenith is the base color, ground is slightly darker
    vec3 horizonColor = skyColor * 1.2;          // 20% brighter at horizon
    vec3 zenithColor = skyColor;                 // Base color at zenith
    vec3 groundColor = skyColor * 0.8;           // 20% darker below horizon

    // Add distance-based fading to reduce hard edges
    float horizontalDistance = length(vec2(direction.x, direction.z));
    float edgeFade = 1.0 - smoothstep(0.85, 0.98, horizontalDistance);

    // Smooth transitions with enhanced horizon blending
    float t;
    if (elevation >= 0.0) {
        // Above horizon - blend from horizon to zenith
        t = smoothstep(0.0, 1.0, elevation);
        vec3 color = mix(horizonColor, zenithColor, t * 0.8);
        // Apply edge fading to reduce cube artifacts
        return mix(horizonColor * 0.9, color, edgeFade);
    } else {
        // Below horizon - blend to ground color with stronger fading
        t = smoothstep(-0.3, 0.0, elevation);
        vec3 color = mix(groundColor, horizonColor, t);
        // Apply stronger edge fading below horizon
        return mix(horizonColor * 0.8, color, edgeFade * 0.7 + 0.3);
    }
}

vec3 getSunContribution(vec3 direction) {
    // Calculate angle between view direction and sun direction
    float sunDot = dot(direction, sunDirection);
    
    // Make sun larger and more visible
    float sunRadius = 0.98; // Larger sun disk (closer to 1.0 = bigger)
    float sunIntensity = smoothstep(sunRadius - 0.01, sunRadius + 0.01, sunDot);
    vec3 sunColor = vec3(1.0, 0.95, 0.7);
    
    // Softer, more gradual glow
    float glowRadius = 0.25;
    float glowIntensity = exp(-pow((1.0 - sunDot) / glowRadius, 1.5)) * 0.4;
    vec3 glowColor = vec3(1.0, 0.8, 0.5);
    
    return sunColor * sunIntensity + glowColor * glowIntensity;
}

void main() {
    vec3 direction = normalize(viewDirection);

    // Get base sky color (using time-based color from uniform)
    vec3 baseSkyColor = getTimedSkyColor(direction);
    
    // Add sun contribution
    vec3 sunContribution = getSunContribution(direction);

    // Combine all contributions (clouds are now a separate voxel cloud layer)
    vec3 finalColor = baseSkyColor + sunContribution;

    // Apply atmospheric perspective with enhanced horizon softening
    float distance = length(worldPos - cameraPos);
    float haze = exp(-distance * 0.00001);
    
    // Additional horizon softening based on viewing angle
    float horizonSoft = 1.0 - smoothstep(-0.1, 0.1, abs(direction.y));
    float hazeAmount = mix(0.1, 0.3, horizonSoft);
    
    vec3 hazeColor = vec3(0.8, 0.85, 0.9);
    finalColor = mix(hazeColor, finalColor, haze * (1.0 - hazeAmount));
    
    FragColor = vec4(finalColor, 1.0);
}