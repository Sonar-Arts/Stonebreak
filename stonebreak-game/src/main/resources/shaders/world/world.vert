#version 330 core
layout (location=0) in vec3 position;
layout (location=1) in vec2 texCoord;
layout (location=2) in vec3 normal;
// Packed flags attribute: x=LOD-water (FastLOD sea sheets — shaded by
// the procedural distant-water branch; near water has its own mesh +
// shader), y=alphaTest, z=translucent, w=light.
// GL provides this as a normalized [0,1] vec4 from 4 unsigned bytes — saves
// 12 bytes per vertex compared to 4 separate float attributes.
layout (location=3) in vec4 aFlags;
// Texture-array layer index. Unbound VAOs (text/UI) read 0 — harmless.
layout (location=4) in float aLayer;
out vec2 outTexCoord;
out vec3 outNormal;
out vec3 fragPos;
out float v_isWater;
out float v_isAlphaTested;
out float v_isTranslucent;
out float v_light;
out float v_layer;
out float v_viewDepth;
uniform mat4 projectionMatrix;
uniform mat4 viewMatrix;
uniform mat4 modelMatrix;
uniform bool u_transformUVsForItem;
uniform vec2 u_atlasUVOffset;
uniform vec2 u_atlasUVScale;
uniform bool u_isUIElement;
void main() {
    gl_Position = projectionMatrix * viewMatrix * modelMatrix * vec4(position, 1.0);
    if (u_transformUVsForItem) {
        outTexCoord = u_atlasUVOffset + texCoord * u_atlasUVScale;
    } else {
        outTexCoord = texCoord;
    }
    outNormal = normal;
    fragPos = (modelMatrix * vec4(position, 1.0)).xyz;
    // Positive view-space distance — drives shadow cascade selection.
    v_viewDepth = -(viewMatrix * vec4(fragPos, 1.0)).z;
    v_isWater = aFlags.x;
    v_isAlphaTested = aFlags.y;
    v_isTranslucent = aFlags.z;
    v_light = aFlags.w;
    v_layer = aLayer;
}
