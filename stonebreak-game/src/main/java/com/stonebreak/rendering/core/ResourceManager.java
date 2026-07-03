package com.stonebreak.rendering.core;

import com.stonebreak.ui.Font;
import com.openmason.engine.rendering.shaders.ShaderProgram;

public class ResourceManager {
    private ShaderProgram shaderProgram;
    private Font font;

    public ResourceManager() {
    }

    public void initialize(int textureAtlasSize) {
        font = new Font("fonts/Roboto-VariableFont_wdth,wght.ttf", 24f);
    }
    
    public void initializeShaderProgram() {
        shaderProgram = new ShaderProgram();
        shaderProgram.createVertexShader(getVertexShaderSource());
        shaderProgram.createFragmentShader(getFragmentShaderSource());
        shaderProgram.link();
        
        createShaderUniforms();
    }
    
    private void createShaderUniforms() {
        // Bind the program before any setUniform call below — glUniform* operates
        // on the *currently bound* program, and nothing has bound it since link().
        // Without this, sampler assignments (e.g. block_sampler -> unit 1) silently
        // fail, leaving block_sampler and texture_sampler both on unit 0. Two
        // different sampler types on one unit make every draw call a no-op
        // (GL_INVALID_OPERATION).
        shaderProgram.bind();
        shaderProgram.createUniform("projectionMatrix");
        shaderProgram.createUniform("viewMatrix");
        shaderProgram.createUniform("modelMatrix");
        shaderProgram.createUniform("texture_sampler");
        // Block texture array sampler (texture unit 1). World/voxel geometry
        // samples this; text/UI keep using the 2D texture_sampler on unit 0.
        shaderProgram.createUniform("block_sampler");
        shaderProgram.setUniform("block_sampler", 1);
        shaderProgram.createUniform("u_useTextureArray");
        shaderProgram.setUniform("u_useTextureArray", false);
        // Layer override for geometry without a per-vertex layer attribute
        // (e.g. flat UI quads). -1 = use the per-vertex v_layer.
        shaderProgram.createUniform("u_layerOverride");
        shaderProgram.setUniform("u_layerOverride", -1.0f);
        // Forces alpha-test discard for geometry without a per-vertex alpha
        // flag (e.g. flower cross meshes used for drops/icons).
        shaderProgram.createUniform("u_forceAlphaTest");
        shaderProgram.setUniform("u_forceAlphaTest", false);
        shaderProgram.createUniform("u_color");
        shaderProgram.createUniform("u_useSolidColor");
        shaderProgram.createUniform("u_isText");
        shaderProgram.createUniform("u_transformUVsForItem");
        shaderProgram.createUniform("u_atlasUVOffset");
        shaderProgram.createUniform("u_atlasUVScale");
        shaderProgram.createUniform("u_renderPass");
        shaderProgram.createUniform("u_isUIElement");
        shaderProgram.createUniform("u_cameraPos");
        shaderProgram.createUniform("u_underwaterFogDensity");
        shaderProgram.createUniform("u_underwaterFogColor");
        shaderProgram.createUniform("u_ambientLight");
        shaderProgram.createUniform("u_sunDirection");
        shaderProgram.createUniform("u_viewPos");
        shaderProgram.createUniform("u_playerLight");
        // Default to -1 so terrain (which never sets this) falls through to the per-vertex light.
        shaderProgram.setUniform("u_playerLight", -1.0f);
        // Shadow map sampler MUST be moved off unit 0 immediately: it is a
        // sampler2DArrayShadow, and leaving it on the same unit as the 2D
        // texture_sampler makes every draw GL_INVALID_OPERATION on strict
        // drivers even while shadows are disabled. Remaining shadow uniforms
        // are auto-registered by ShadowUniforms' tolerant setters per frame.
        shaderProgram.setInt("u_shadowMap",
                com.stonebreak.rendering.gameWorld.shadow.ShadowMapRenderer.SHADOW_TEXTURE_UNIT);
        shaderProgram.setBool("u_shadowsEnabled", false);
    }
    
    private String getVertexShaderSource() {
        return """
               #version 330 core
               layout (location=0) in vec3 position;
               layout (location=1) in vec2 texCoord;
               layout (location=2) in vec3 normal;
               // Packed flags attribute: x=reserved (was water-height; water now has
               // its own mesh + shader), y=alphaTest, z=translucent, w=light.
               // GL provides this as a normalized [0,1] vec4 from 4 unsigned bytes — saves
               // 12 bytes per vertex compared to 4 separate float attributes.
               layout (location=3) in vec4 aFlags;
               // Texture-array layer index. Unbound VAOs (text/UI) read 0 — harmless.
               layout (location=4) in float aLayer;
               out vec2 outTexCoord;
               out vec3 outNormal;
               out vec3 fragPos;
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
                   v_isAlphaTested = aFlags.y;
                   v_isTranslucent = aFlags.z;
                   v_light = aFlags.w;
                   v_layer = aLayer;
               }""";
    }
    
    private String getFragmentShaderSource() {
        return """
               #version 330 core
               in vec2 outTexCoord;
               in vec3 outNormal;
               in vec3 fragPos;
               in float v_isAlphaTested;
               in float v_isTranslucent;
               in float v_light;
               in float v_layer;
               in float v_viewDepth;
               out vec4 fragColor;
               """
               + com.openmason.engine.rendering.shadow.ShadowGlsl.UNIFORMS
               + com.openmason.engine.rendering.shadow.ShadowGlsl.FUNCTIONS
               + """
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
               void main() {
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

                       // Apply underwater fog effect if not UI element and fog density > 0
                       if (!u_isUIElement && u_underwaterFogDensity > 0.0) {
                           float distance = length(fragPos - u_cameraPos);
                           float fogFactor = exp(-u_underwaterFogDensity * distance);
                           fogFactor = clamp(fogFactor, 0.0, 1.0);

                           // Blend fragment color with fog color, preserving alpha
                           fragColor = mix(vec4(u_underwaterFogColor, fragColor.a), fragColor, fogFactor);
                       }
                   }
               }""";
    }
    
    public ShaderProgram getShaderProgram() {
        return shaderProgram;
    }
    
    public Font getFont() {
        return font;
    }
    
    public void cleanup() {
        if (shaderProgram != null) {
            shaderProgram.cleanup();
        }
        if (font != null) {
            font.cleanup();
        }
    }
}
