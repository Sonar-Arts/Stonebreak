#version 330 core
in vec2 outTexCoord;
in vec3 outNormal;
in vec3 fragPos;
in float v_isWater;
in float v_isAlphaTested;
in float v_isTranslucent;
in float v_light;
in float v_layer;
in float v_viewDepth;
out vec4 fragColor;

#include "/shaders/shadow/csm_uniforms.glsl"
#include "/shaders/shadow/csm_functions.glsl"

uniform sampler2D texture_sampler;
// Block texture array — sampled when u_useTextureArray is true.
uniform sampler2DArray block_sampler;
uniform bool u_useTextureArray;
// >=0 overrides the per-vertex layer (used by flat UI quads).
uniform float u_layerOverride;
// Forces alpha-test discard regardless of the per-vertex flag.
uniform bool u_forceAlphaTest;
uniform vec4 u_color;
uniform bool u_useSolidColor;
uniform bool u_isText;
uniform bool u_isUIElement;
uniform int u_renderPass;
uniform vec3 u_cameraPos;
uniform float u_underwaterFogDensity;
uniform vec3 u_underwaterFogColor;
uniform float u_ambientLight;
uniform vec3 u_sunDirection;
uniform vec3 u_viewPos;
uniform float u_playerLight;
// Atmospheric distance fog (fog-to-sky at the LOD outer ring).
// Disabled whenever u_fogEnd <= u_fogStart.
uniform vec3 u_fogColor;
uniform float u_fogStart;
uniform float u_fogEnd;
// FastLOD crossfade opacity (1.0 = solid). <1 engages the
// screen-door dither below so LOD nodes dissolve over/under the
// native chunk mesh without blending or depth artifacts.
uniform float u_lodFade;

// 4x4 Bayer thresholds for the LOD crossfade dither.
const float LOD_BAYER[16] = float[16](
     0.0,  8.0,  2.0, 10.0,
    12.0,  4.0, 14.0,  6.0,
     3.0, 11.0,  1.0,  9.0,
    15.0,  7.0, 13.0,  5.0);

void main() {
    // FastLOD crossfade: per-pixel screen-door discard. Only LOD
    // node draws ever set u_lodFade below 1.0, so this costs one
    // coherent branch for all other geometry. Covers the LOD
    // water branch too — sea sheets fade with their node.
    if (u_lodFade < 1.0) {
        int di = (int(gl_FragCoord.y) & 3) * 4 + (int(gl_FragCoord.x) & 3);
        if (u_lodFade < (LOD_BAYER[di] + 0.5) / 16.0) discard;
    }
    if (u_isText) {
        float alpha = texture(texture_sampler, outTexCoord).a;
        fragColor = vec4(u_color.rgb, u_color.a * alpha);
    } else if (u_useSolidColor) {
        // Voxelized held sprites draw per-voxel solid colors — darken
        // by player world light so held tools fade out in caves too.
        float playerFactor = (u_playerLight >= 0.0)
            ? mix(0.30, 1.0, u_playerLight)
            : 1.0;
        fragColor = vec4(u_color.rgb * playerFactor, u_color.a);
    } else {
        float arrayLayer = (u_layerOverride >= 0.0) ? u_layerOverride : v_layer;
        vec4 textureColor = u_useTextureArray
            ? texture(block_sampler, vec3(outTexCoord, arrayLayer))
            : texture(texture_sampler, outTexCoord);
        float sampledAlpha = textureColor.a;

        // UI elements get simple flat lighting. When u_playerLight is set
        // (first-person arm / held item in the world), scale brightness by
        // the player's current world light so the arm darkens in caves.
        if (u_isUIElement) {
            float playerWorldFactor = (u_playerLight >= 0.0)
                ? mix(0.30, 1.0, u_playerLight)
                : 1.0;
            float brightness = 0.9 * playerWorldFactor;

            if (v_isAlphaTested > 0.5 || u_forceAlphaTest) {
                if (sampledAlpha < 0.1) discard;
                fragColor = vec4(textureColor.rgb * brightness, 1.0);
            } else if (v_isTranslucent > 0.5) {
                if (u_renderPass == 0) discard;
                else fragColor = vec4(textureColor.rgb * brightness, sampledAlpha);
            } else {
                if (u_renderPass == 0) fragColor = vec4(textureColor.rgb * brightness, 1.0);
                else discard;
            }
            return;
        }

        // --- Phong Lighting Model for World Objects ---
        // (FastLOD water sheets no longer render through this
        // shader — they have their own mesh drawn by the
        // dedicated WaterRenderer, same as native water.)
        vec3 norm = normalize(outNormal);
        vec3 lightDir = normalize(u_sunDirection);
        vec3 viewDir = normalize(u_viewPos - fragPos);

        // Ambient component (base lighting from sky/environment)
        float ambientStrength = u_ambientLight * 0.4; // Scale down ambient
        vec3 ambient = ambientStrength * textureColor.rgb;

        // Cascaded sun-shadow visibility — attenuates direct light only
        // (diffuse + specular); ambient stays so shadows never go black.
        // Player-held geometry (u_playerLight >= 0) renders in arm-local
        // coordinates, so its fragPos is meaningless in light space — skip.
        float shadowFactor = (u_playerLight >= 0.0)
            ? 1.0
            : csmShadowFactor(fragPos, norm, v_viewDepth);

        // Diffuse component (directional sunlight)
        float diff = max(dot(norm, lightDir), 0.0);
        // Only apply full diffuse during daytime
        float diffuseStrength = 0.6 * u_ambientLight;
        vec3 diffuse = diff * diffuseStrength * shadowFactor * textureColor.rgb;

        // Specular component (shiny highlights)
        float specularStrength = 0.3;
        vec3 halfwayDir = normalize(lightDir + viewDir);
        float spec = pow(max(dot(norm, halfwayDir), 0.0), 32.0);
        // Only ice gets strong specular (water has its own shader now)
        float specularIntensity = (v_isTranslucent > 0.5) ? 0.5 : 0.1;
        vec3 specular = specularIntensity * spec * specularStrength * u_ambientLight * shadowFactor * vec3(1.0);

        // Combine lighting components
        vec3 result = ambient + diffuse + specular;

        // World light — per-vertex by default. Player-held geometry (arm, held item)
        // overrides via u_playerLight so it shades with whatever cell the player is in.
        float worldLight = (u_playerLight >= 0.0) ? u_playerLight : clamp(v_light, 0.0, 1.0);
        // Linear ramp with a 30% floor: per-vertex shadow values already
        // encode sky × AO, and real sun shadows now come from the shadow
        // map — the baked term only needs to suggest occlusion, so keep
        // it light or the two stack into pitch-black corners.
        float worldLightFactor = mix(0.30, 1.0, worldLight);
        result *= worldLightFactor;

        if (v_isAlphaTested > 0.5) {
            if (sampledAlpha < 0.1) {
                discard;
            }
            // Always render alpha-tested blocks in opaque pass, discard in transparent
            if (u_renderPass == 0) {
                fragColor = vec4(result, 1.0);
            } else {
                discard;
            }
        } else if (v_isTranslucent > 0.5) {
            // Translucent blocks (e.g. ice): rendered in transparent
            // pass only, depth-write ON so they occlude distant
            // translucents. Water no longer renders through this
            // shader — it has its own mesh + WaterRenderer pass.
            if (u_renderPass == 0) {
                discard;
            } else {
                fragColor = vec4(result, sampledAlpha);
            }
        } else {
            // Regular opaque blocks are rendered in opaque pass only
            if (u_renderPass == 0) {
                fragColor = vec4(result, 1.0);
            } else {
                discard;
            }
        }

        // Atmospheric distance fog — dissolves the far LOD ring into
        // the sky. Horizontal distance so tall peaks fade with their
        // bases; skipped for player-held geometry (arm-local fragPos)
        // and disabled by the CPU (fogEnd=0) when LOD is off or the
        // underwater fog owns the look.
        if (!u_isUIElement && u_playerLight < 0.0 && u_fogEnd > u_fogStart) {
            float horizDist = length(fragPos.xz - u_viewPos.xz);
            float fogF = smoothstep(u_fogStart, u_fogEnd, horizDist);
            fragColor = vec4(mix(fragColor.rgb, u_fogColor, fogF), fragColor.a);
        }

        // Apply underwater fog effect if not UI element and fog density > 0
        if (!u_isUIElement && u_underwaterFogDensity > 0.0) {
            float distance = length(fragPos - u_cameraPos);
            float fogFactor = exp(-u_underwaterFogDensity * distance);
            fogFactor = clamp(fogFactor, 0.0, 1.0);

            // Blend fragment color with fog color, preserving alpha
            fragColor = mix(vec4(u_underwaterFogColor, fragColor.a), fragColor, fogFactor);
        }
    }
}
