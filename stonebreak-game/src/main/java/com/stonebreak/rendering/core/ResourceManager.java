package com.stonebreak.rendering.core;

import com.stonebreak.ui.Font;
import com.stonebreak.rendering.shaders.ShaderProgram;
import com.stonebreak.rendering.textures.TextureAtlas;

public class ResourceManager {
    private ShaderProgram shaderProgram;
    private TextureAtlas textureAtlas;
    private Font font;
    
    public ResourceManager() {
    }
    
    public void initialize(int textureAtlasSize) {
        textureAtlas = new TextureAtlas(textureAtlasSize);
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
        shaderProgram.createUniform("projectionMatrix");
        shaderProgram.createUniform("viewMatrix");
        shaderProgram.createUniform("modelMatrix");
        shaderProgram.createUniform("texture_sampler");
        shaderProgram.createUniform("u_color");
        shaderProgram.createUniform("u_useSolidColor");
        shaderProgram.createUniform("u_isText");
        shaderProgram.createUniform("u_transformUVsForItem");
        shaderProgram.createUniform("u_atlasUVOffset");
        shaderProgram.createUniform("u_atlasUVScale");
        shaderProgram.createUniform("u_renderPass");
        shaderProgram.createUniform("u_isUIElement");
        shaderProgram.createUniform("u_time");
    }
    
    private String getVertexShaderSource() {
        return """
               #version 330 core
               layout (location=0) in vec3 position;
               layout (location=1) in vec2 texCoord;
               layout (location=2) in vec3 normal;
               layout (location=3) in float waterHeight;
               layout (location=4) in float isAlphaTested;
               out vec2 outTexCoord;
               out vec3 outNormal;
               out vec3 fragPos;
               out float v_waterHeight;
               out float v_isAlphaTested;
               uniform mat4 projectionMatrix;
               uniform mat4 viewMatrix;
               uniform mat4 modelMatrix;
               uniform bool u_transformUVsForItem;
               uniform vec2 u_atlasUVOffset;
               uniform vec2 u_atlasUVScale;
               uniform float u_time;
               uniform bool u_isUIElement;
               void main() {
                   // Compute world-space position first for stable, seamless waves
                   vec3 worldPos = (modelMatrix * vec4(position, 1.0)).xyz;
                   vec3 pos = position;
                   // Apply GPU-side water surface displacement to avoid remeshing for waves
                   if (waterHeight > 0.0 && !u_isUIElement) {
                       // World-space wave using simple, seamless functions
                       float s = 0.50;             // spatial scale
                       float speed1 = 1.5;         // primary speed
                       float speed2 = 2.0;         // secondary speed
                       float amp1 = 0.12;          // primary amplitude
                       float amp2 = 0.04;          // secondary amplitude
                       float wave = sin(worldPos.x * s + u_time * speed1) * cos(worldPos.z * s * 0.8 + u_time * speed1 * 0.9) * amp1
                                 + sin((worldPos.x + worldPos.z) * s * 1.7 + u_time * speed2) * amp2;
                       float topFactor = clamp(normal.y, 0.0, 1.0);
                       bool isBottomFace = normal.y < -0.5;
                       if (topFactor > 0.5) {
                           // Top faces ride the full wave height
                           pos.y += wave;
                       } else if (!isBottomFace) {
                           // Stretch side faces so their top edge follows the displaced surface
                           float blockBaseY = floor(worldPos.y + 0.0001);
                           float normalizedHeight = 0.0;
                           if (waterHeight > 0.0001) {
                               normalizedHeight = clamp((worldPos.y - blockBaseY) / waterHeight, 0.0, 1.0);
                           }
                           float displacedTopY = blockBaseY + waterHeight + wave;
                           pos.y = mix(blockBaseY, displacedTopY, normalizedHeight);
                       }
                   }
                   gl_Position = projectionMatrix * viewMatrix * modelMatrix * vec4(pos, 1.0);
                   if (u_transformUVsForItem) {
                       outTexCoord = u_atlasUVOffset + texCoord * u_atlasUVScale;
                   } else {
                       outTexCoord = texCoord;
                   }
                   outNormal = normal;
                   fragPos = (modelMatrix * vec4(pos, 1.0)).xyz;
                   v_waterHeight = waterHeight;
                   v_isAlphaTested = isAlphaTested;
               }""";
    }
    
    private String getFragmentShaderSource() {
        return """
               #version 330 core
               in vec2 outTexCoord;
               in vec3 outNormal;
               in vec3 fragPos;
               in float v_waterHeight;
               in float v_isAlphaTested;
               out vec4 fragColor;
               uniform sampler2D texture_sampler;
               uniform vec4 u_color;
               uniform bool u_useSolidColor;
               uniform bool u_isText;
               uniform bool u_isUIElement;
               uniform int u_renderPass;
               void main() {
                   if (u_isText) {
                       float alpha = texture(texture_sampler, outTexCoord).a;
                       fragColor = vec4(u_color.rgb, u_color.a * alpha);
                   } else if (u_useSolidColor) {
                       fragColor = u_color;
                   } else {
                       vec3 lightDir = normalize(vec3(0.5, 1.0, 0.3));
                       float ambient = u_isUIElement ? 0.8 : 0.3;
                       float diffuse = max(dot(outNormal, lightDir), 0.0);
                       float brightness = u_isUIElement ? 0.8 : (ambient + diffuse * 0.7);
                       vec4 textureColor = texture(texture_sampler, outTexCoord);
                       float sampledAlpha = textureColor.a;

                       if (v_isAlphaTested > 0.5) {
                           if (sampledAlpha < 0.1) {
                               discard;
                           }
                           fragColor = vec4(textureColor.rgb * brightness, 1.0);
                       } else if (v_waterHeight > 0.0) {
                           if (u_renderPass == 0) {
                               discard;
                           } else {
                               fragColor = vec4(textureColor.rgb * brightness, sampledAlpha);
                           }
                       } else {
                           if (u_renderPass == 0) {
                               fragColor = vec4(textureColor.rgb * brightness, 1.0);
                           } else {
                               discard;
                           }
                       }
                   }
               }""";
    }
    
    public ShaderProgram getShaderProgram() {
        return shaderProgram;
    }
    
    public TextureAtlas getTextureAtlas() {
        return textureAtlas;
    }
    
    public Font getFont() {
        return font;
    }
    
    public void cleanup() {
        if (shaderProgram != null) {
            shaderProgram.cleanup();
        }
        if (textureAtlas != null) {
            textureAtlas.cleanup();
        }
        if (font != null) {
            font.cleanup();
        }
    }
}
