#version 330 core

// Procedural rain streaks / snowflakes: each lane is a train of segments
// scrolling down the world-height axis. No textures. A streak narrower than a
// pixel is drawn a pixel wide at proportionally lower alpha, so distant rain
// keeps its brightness instead of flickering out, and fades into the rain fog
// between it and the eye like everything else. Cloud nearer than the lane
// hides it (clouds_in_front.glsl): rain drew over the clouds before.

#include "/shaders/weather/precip_fog.glsl"
#include "/shaders/clouds/clouds_in_front.glsl"

in vec2 vUv;
in vec2 vParams;
in vec2 vSize;
in float vSeed;
in float vDist;
in float vEyeDist;
in vec3 vPos;
out vec4 fragColor;

uniform vec3 uCameraPos;    // render space

uniform float uTime;
uniform float uAmbient;
uniform vec2 uFade;         // lanes fade in from x to y blocks out (the far ring takes over from the near one)...
uniform float uRadius;      // ...and fade out toward this distance
// Lanes per lane: a far lane stands in for the near lanes its wider spacing
// leaves out. It only makes up for the streak's sub-pixel width, never
// brightens a lane past a near streak, so far rain is no heavier than near.
uniform float uDensity;

void main() {
    float intensity = vParams.x;
    float snow = vParams.y;
    float speed = mix(16.0, 1.5, snow);       // blocks per second
    float period = mix(5.0, 2.2, snow);       // blocks between drops in a lane
    float length_ = mix(0.30, 0.07, snow);    // fraction of the period that is drop
    float width = mix(0.022, 0.08, snow);     // blocks

    float s = vSeed;
    // Blocks from the lane's centre; snowflakes sway as they fall.
    float x = vUv.x - snow * 0.12 * sin(uTime * 1.3 + s * 20.0 + vUv.y * 0.5);
    float drawn = max(width, vSize.y);
    float across = 1.0 - smoothstep(drawn * 0.5, drawn, abs(x));
    float v = fract((vUv.y + uTime * speed) / period + s);
    float along = smoothstep(0.0, 0.02, v) * (1.0 - smoothstep(length_ * 0.6, length_, v));

    float fade = smoothstep(uFade.x, uFade.y, vDist) * (1.0 - smoothstep(uRadius * 0.6, uRadius, vDist));
    float coverage = min(uDensity * width / drawn, 1.0);
    float alpha = across * along * intensity * mix(0.32, 0.9, snow) * fade * coverage
            * precipFog(vec3(0.0), vec3(0.0, 1.0, 0.0), vEyeDist).a;
    if (alpha < 0.01) discard;

    vec3 color = mix(vec3(0.62, 0.68, 0.78), vec3(0.96), snow) * mix(0.25, 1.0, clamp(uAmbient, 0.0, 1.0));
    fragColor = vec4(behindClouds(color, uCameraPos, vPos), min(alpha, 1.0));
}
