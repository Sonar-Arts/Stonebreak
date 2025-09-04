#version 330 core

in vec3 worldPos;
in vec3 viewDirection;
in vec3 cameraPos;

uniform float time;
uniform vec3 sunDirection;

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

vec3 getSkyColor(vec3 direction) {
    // Use full range of elevation (-1 to 1) for smoother transitions
    float elevation = direction.y;
    
    // Colors for different sky regions
    vec3 horizonColor = vec3(0.8, 0.9, 1.0);   // Very light blue at horizon
    vec3 zenithColor = vec3(0.4, 0.7, 1.0);    // Deeper blue at zenith  
    vec3 groundColor = vec3(0.6, 0.8, 0.9);    // Lighter blue below horizon
    
    // Smooth transitions
    float t;
    if (elevation >= 0.0) {
        // Above horizon - blend from horizon to zenith
        t = smoothstep(0.0, 1.0, elevation);
        return mix(horizonColor, zenithColor, t * 0.8);
    } else {
        // Below horizon - blend to ground color
        t = smoothstep(-0.3, 0.0, elevation);
        return mix(groundColor, horizonColor, t);
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
    
    // Get base sky color
    vec3 skyColor = getSkyColor(direction);
    
    // Add sun contribution
    vec3 sunContribution = getSunContribution(direction);
    
    // Add cloud contribution
    vec3 cloudContribution = getCloudContribution(direction);
    
    // Combine all contributions
    vec3 finalColor = skyColor + sunContribution;
    
    // Blend clouds on top with proper alpha compositing
    finalColor = mix(finalColor, cloudContribution, min(1.0, length(cloudContribution)));
    
    // Apply atmospheric perspective (slight haze)
    float distance = length(worldPos - cameraPos);
    float haze = exp(-distance * 0.00001);
    finalColor = mix(vec3(0.8, 0.85, 0.9), finalColor, haze);
    
    FragColor = vec4(finalColor, 1.0);
}