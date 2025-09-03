package com.stonebreak.rendering.core;

import com.stonebreak.ui.Font;
import com.stonebreak.rendering.ShaderProgram;
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
    }
    
    private String getVertexShaderSource() {
        return """
               #version 330 core
               layout (location=0) in vec3 position;
               layout (location=1) in vec2 texCoord;
               layout (location=2) in vec3 normal;
               layout (location=3) in float isWater;
               layout (location=4) in float isAlphaTested;
               out vec2 outTexCoord;
               out vec3 outNormal;
               out vec3 fragPos;
               out float v_isWater;
               out float v_isAlphaTested;
               uniform mat4 projectionMatrix;
               uniform mat4 viewMatrix;
               uniform mat4 modelMatrix;
               uniform bool u_transformUVsForItem;
               uniform vec2 u_atlasUVOffset;
               uniform vec2 u_atlasUVScale;
               void main() {
                   gl_Position = projectionMatrix * viewMatrix * modelMatrix * vec4(position, 1.0);
                   if (u_transformUVsForItem) {
                       outTexCoord = u_atlasUVOffset + texCoord * u_atlasUVScale;
                   } else {
                       outTexCoord = texCoord;
                   }
                   outNormal = normal;
                   fragPos = position;
                   v_isWater = isWater;
                   v_isAlphaTested = isAlphaTested;
               }""";
    }
    
    private String getFragmentShaderSource() {
        return """
               #version 330 core
               in vec2 outTexCoord;
               in vec3 outNormal;
               in vec3 fragPos;
               in float v_isWater;
               in float v_isAlphaTested;
               out vec4 fragColor;
               uniform sampler2D texture_sampler;
               uniform vec4 u_color;
               uniform bool u_useSolidColor;
               uniform bool u_isText;
               uniform int u_renderPass;
               void main() {
                   if (u_isText) {
                       float alpha = texture(texture_sampler, outTexCoord).a;
                       fragColor = vec4(u_color.rgb, u_color.a * alpha);
                   } else if (u_useSolidColor) {
                       fragColor = u_color;
                   } else {
                       vec3 lightDir = normalize(vec3(0.5, 1.0, 0.3));
                       float ambient = 0.3;
                       float diffuse = max(dot(outNormal, lightDir), 0.0);
                       float brightness = ambient + diffuse * 0.7;
                       vec4 textureColor = texture(texture_sampler, outTexCoord);
                       float sampledAlpha = textureColor.a;

                       if (v_isAlphaTested > 0.5) {
                           if (sampledAlpha < 0.1) {
                               discard;
                           }
                           fragColor = vec4(textureColor.rgb * brightness, 1.0);
                       } else if (v_isWater > 0.5) {
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