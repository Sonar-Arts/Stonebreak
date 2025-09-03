package com.stonebreak.rendering.UI.components;

import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.rendering.pipeline.DepthCurtainRenderer;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;

public class OpenGLQuadRenderer {
    private final UIQuadRenderer uiQuadRenderer;
    private DepthCurtainRenderer depthCurtainRenderer;
    private int windowWidth;
    private int windowHeight;
    
    public OpenGLQuadRenderer() {
        uiQuadRenderer = new UIQuadRenderer();
    }
    
    public void initialize() {
        uiQuadRenderer.initialize();
    }
    
    public void initializeDepthCurtainRenderer(com.stonebreak.rendering.ShaderProgram shaderProgram, 
                                             int windowWidth, int windowHeight, 
                                             org.joml.Matrix4f projectionMatrix) {
        this.depthCurtainRenderer = new DepthCurtainRenderer(shaderProgram, windowWidth, windowHeight, projectionMatrix);
    }
    
    public void setWindowDimensions(int width, int height) {
        this.windowWidth = width;
        this.windowHeight = height;
    }
    
    public void drawQuad(com.stonebreak.rendering.ShaderProgram shaderProgram, int x, int y, int width, int height, int r, int g, int b, int a) {
        float red = r / 255.0f;
        float green = g / 255.0f;
        float blue = b / 255.0f;
        float alpha = a / 255.0f;

        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_color", new Vector4f(red, green, blue, alpha));

        float x_pixel = (float)x;
        float y_pixel = (float)y;
        float x_plus_width_pixel = (float)(x + width);
        float y_plus_height_pixel = (float)(y + height);

        float[] vertices = {
            x_pixel,             y_pixel,               0.0f, 0.0f, 0.0f,
            x_plus_width_pixel,  y_pixel,               0.0f, 0.0f, 0.0f,
            x_plus_width_pixel,  y_plus_height_pixel,   0.0f, 0.0f, 0.0f,
            x_pixel,             y_plus_height_pixel,   0.0f, 0.0f, 0.0f
        };

        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();

        uiQuadRenderer.bind();
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertexBuffer);

        boolean texture2DWasEnabled = glIsEnabled(GL_TEXTURE_2D);
        if (texture2DWasEnabled) {
            glDisable(GL_TEXTURE_2D);
        }

        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);

        if (texture2DWasEnabled) {
            glEnable(GL_TEXTURE_2D);
        }
        
        uiQuadRenderer.unbind();
    }
    
    public void drawTexturedQuadUI(com.stonebreak.rendering.ShaderProgram shaderProgram, int x, int y, int width, int height, int textureId, float u1, float v1, float u2, float v2) {
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("texture_sampler", 0);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureId);

        float ndcX = (x / (float)windowWidth) * 2.0f - 1.0f;
        float ndcY = 1.0f - (y / (float)windowHeight) * 2.0f;
        float ndcWidth = (width / (float)windowWidth) * 2.0f;
        float ndcHeight = (height / (float)windowHeight) * 2.0f;

        float x_1 = ndcX;
        float y_1 = ndcY;
        float x_2 = ndcX + ndcWidth;
        float y_2 = ndcY - ndcHeight;

        float[] vertices = {
            x_1, y_1, 0.0f, u1, v1,
            x_2, y_1, 0.0f, u2, v1,
            x_2, y_2, 0.0f, u2, v2,
            x_1, y_2, 0.0f, u1, v2
        };

        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();

        uiQuadRenderer.bind();
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertexBuffer);

        boolean blendWasEnabled = glIsEnabled(GL_BLEND);
        if (!blendWasEnabled) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }

        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);

        if (!blendWasEnabled) {
            glDisable(GL_BLEND);
        }

        uiQuadRenderer.unbind();
        glBindTexture(GL_TEXTURE_2D, 0);
    }
    
    public void drawFlat2DItemInSlot(com.stonebreak.rendering.ShaderProgram shaderProgram, 
                                   com.stonebreak.blocks.BlockType type, 
                                   int screenSlotX, int screenSlotY, 
                                   int screenSlotWidth, int screenSlotHeight,
                                   TextureAtlas textureAtlas,
                                   FloatBuffer projectionMatrixBuffer,
                                   FloatBuffer viewMatrixBuffer) {
        
        int[] originalViewport = new int[4];
        int[] originalScissorBox = new int[4];
        boolean scissorWasEnabled = glIsEnabled(GL_SCISSOR_TEST);
        glGetIntegerv(GL_VIEWPORT, originalViewport);
        if (scissorWasEnabled) {
            glGetIntegerv(GL_SCISSOR_BOX, originalScissorBox);
        }
        
        org.joml.Matrix4f orthoProjection = new org.joml.Matrix4f().ortho(0, windowWidth, windowHeight, 0, -1, 1);
        org.joml.Matrix4f identityView = new org.joml.Matrix4f().identity();
        
        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", orthoProjection);
        shaderProgram.setUniform("viewMatrix", identityView);
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("texture_sampler", 0);

        float[] uvCoords = textureAtlas.getBlockFaceUVs(type, com.stonebreak.blocks.BlockType.Face.TOP);
        
        int padding = 6;
        float textureX = screenSlotX + padding;
        float textureY = screenSlotY + padding;
        float textureWidth = screenSlotWidth - (padding * 2);
        float textureHeight = screenSlotHeight - (padding * 2);
        
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureAtlas.getTextureId());
        
        float[] vertices = {
            textureX,              textureY,               0.0f, uvCoords[0], uvCoords[1],
            textureX + textureWidth, textureY,              0.0f, uvCoords[2], uvCoords[1],
            textureX + textureWidth, textureY + textureHeight, 0.0f, uvCoords[2], uvCoords[3],
            textureX,              textureY + textureHeight, 0.0f, uvCoords[0], uvCoords[3]
        };

        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();

        uiQuadRenderer.bind();
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertexBuffer);

        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);

        uiQuadRenderer.unbind();
        glBindTexture(GL_TEXTURE_2D, 0);
        
        org.joml.Matrix4f originalProjection = new org.joml.Matrix4f();
        originalProjection.set(projectionMatrixBuffer);
        org.joml.Matrix4f originalView = new org.joml.Matrix4f();
        originalView.set(viewMatrixBuffer);
        shaderProgram.setUniform("projectionMatrix", originalProjection);
        shaderProgram.setUniform("viewMatrix", originalView);
        
        glViewport(originalViewport[0], originalViewport[1], originalViewport[2], originalViewport[3]);
        if (scissorWasEnabled) {
            glScissor(originalScissorBox[0], originalScissorBox[1], originalScissorBox[2], originalScissorBox[3]);
        } else {
            glDisable(GL_SCISSOR_TEST);
        }
    }
    
    public void renderInventoryDepthCurtain() {
        if (depthCurtainRenderer != null) {
            depthCurtainRenderer.renderInventoryDepthCurtain();
        }
    }
    
    public void renderHotbarDepthCurtain() {
        if (depthCurtainRenderer != null) {
            depthCurtainRenderer.renderHotbarDepthCurtain();
        }
    }
    
    public void renderPauseMenuDepthCurtain() {
        if (depthCurtainRenderer != null) {
            depthCurtainRenderer.renderPauseMenuDepthCurtain();
        }
    }
    
    public void renderRecipeBookDepthCurtain() {
        if (depthCurtainRenderer != null) {
            depthCurtainRenderer.renderRecipeBookDepthCurtain();
        }
    }
    
    public void renderWorkbenchDepthCurtain() {
        if (depthCurtainRenderer != null) {
            depthCurtainRenderer.renderWorkbenchDepthCurtain();
        }
    }
    
    public void cleanup() {
        if (uiQuadRenderer != null) {
            uiQuadRenderer.cleanup();
        }
    }
}