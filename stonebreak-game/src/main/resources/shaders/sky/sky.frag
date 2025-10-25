#version 330 core

in vec3 worldPos;
in vec3 viewDirection;
in vec3 cameraPos;

uniform float time;
uniform vec3 sunDirection;
uniform vec3 skyColor; // Base sky color from TimeOfDay system

out vec4 FragColor;

// Noise functions for procedural clouds
float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
}

float fbm(vec2 p) {
    float value = 0.0;
    float amplitude = 0.5;
    float frequency = 1.0;
    
    for (int i = 0; i < 4; i++) {
        value += amplitude * noise(frequency * p);
        frequency *= 2.0;
        amplitude *= 0.5;
    }
    
    return value;
}

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

vec3 getCloudContribution(vec3 direction) {
    // Only render clouds in upper hemisphere with some margin
    if (direction.y < -0.1) {
        return vec3(0.0);
    }
    
    // Project direction onto cloud plane at fixed altitude
    float cloudAltitude = 0.3;  // Clouds appear at this elevation angle
    
    // Calculate cloud plane intersection
    vec2 cloudPos;
    if (direction.y > 0.01) {
        // Above horizon - project ray to cloud plane
        float t = cloudAltitude / direction.y;
        cloudPos = vec2(direction.x, direction.z) * t;
    } else {
        // Near horizon - use horizontal projection
        cloudPos = vec2(direction.x, direction.z) / max(0.01, abs(direction.y));
    }
    
    // Add time-based movement for animation
    vec2 cloudOffset = vec2(time * 0.02, time * 0.01);
    cloudPos += cloudOffset;
    
    // Scale for cloud detail
    cloudPos *= 0.8;
    
    // Generate cloud density using fractal noise
    float cloudDensity = fbm(cloudPos * 2.0);
    
    // Add larger cloud formations
    cloudDensity += fbm(cloudPos * 0.5) * 0.7;
    
    // Create cloud threshold and softness
    float cloudThreshold = 0.6;
    float cloudSoftness = 0.3;
    
    float cloudMask = smoothstep(cloudThreshold - cloudSoftness, 
                                cloudThreshold + cloudSoftness, 
                                cloudDensity);
    
    // Fade clouds near horizon
    float horizonFade = smoothstep(-0.1, 0.2, direction.y);
    cloudMask *= horizonFade;
    
    // Cloud lighting based on sun position
    float cloudLighting = max(0.3, dot(vec3(0.0, 1.0, 0.0), sunDirection)) * 0.7 + 0.3;
    
    // Cloud colors
    vec3 cloudColor = vec3(0.9, 0.9, 0.95) * cloudLighting;
    vec3 cloudShadow = vec3(0.4, 0.4, 0.5) * cloudLighting;
    
    // Vary cloud brightness
    float brightnesVariation = noise(cloudPos * 4.0) * 0.3 + 0.7;
    vec3 finalCloudColor = mix(cloudShadow, cloudColor, brightnesVariation);
    
    return finalCloudColor * cloudMask;
}

void main() {
    vec3 direction = normalize(viewDirection);

    // Get base sky color (using time-based color from uniform)
    vec3 baseSkyColor = getTimedSkyColor(direction);
    
    // Add sun contribution
    vec3 sunContribution = getSunContribution(direction);
    
    // Add cloud contribution
    vec3 cloudContribution = getCloudContribution(direction);

    // Combine all contributions
    vec3 finalColor = baseSkyColor + sunContribution;
    
    // Blend clouds on top with proper alpha compositing
    finalColor = mix(finalColor, cloudContribution, min(1.0, length(cloudContribution)));
    
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