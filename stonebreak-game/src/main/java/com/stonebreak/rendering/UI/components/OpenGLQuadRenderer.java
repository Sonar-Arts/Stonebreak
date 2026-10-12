package com.stonebreak.rendering.UI.components;

import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.stonebreak.rendering.textures.BlockTextureArray;
import com.stonebreak.rendering.pipeline.DepthCurtainRenderer;
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
    
    public void initializeDepthCurtainRenderer(ShaderProgram shaderProgram,
                                               int windowWidth, int windowHeight,
                                               org.joml.Matrix4f projectionMatrix) {
        this.depthCurtainRenderer = new DepthCurtainRenderer(shaderProgram, windowWidth, windowHeight, projectionMatrix);
    }
    
    public void setWindowDimensions(int width, int height) {
        this.windowWidth = width;
        this.windowHeight = height;
    }
    
    public void drawFlat2DItemInSlot(ShaderProgram shaderProgram,
                                     com.stonebreak.blocks.BlockType type,
                                     int screenSlotX, int screenSlotY,
                                     int screenSlotWidth, int screenSlotHeight,
                                     BlockTextureArray blockTextureArray,
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
        // Sample the block texture array (unit 1); the flat quad has no
        // per-vertex layer, so the layer is supplied via u_layerOverride.
        shaderProgram.setUniform("block_sampler", 1);
        shaderProgram.setUniform("u_useTextureArray", true);
        shaderProgram.setUniform("u_layerOverride",
                (float) blockTextureArray.getBlockFaceLayer(type, com.stonebreak.blocks.BlockType.Face.TOP.getIndex()));

        int padding = 6;
        float textureX = screenSlotX + padding;
        float textureY = screenSlotY + padding;
        float textureWidth = screenSlotWidth - (padding * 2);
        float textureHeight = screenSlotHeight - (padding * 2);

        glActiveTexture(GL_TEXTURE1);
        blockTextureArray.bind();
        glActiveTexture(GL_TEXTURE0);

        // Unit-square UVs — the layer covers the whole quad.
        float[] vertices = {
            textureX,              textureY,               0.0f, 0.0f, 0.0f,
            textureX + textureWidth, textureY,              0.0f, 1.0f, 0.0f,
            textureX + textureWidth, textureY + textureHeight, 0.0f, 1.0f, 1.0f,
            textureX,              textureY + textureHeight, 0.0f, 0.0f, 1.0f
        };

        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();

        uiQuadRenderer.bind();
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertexBuffer);

        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);

        uiQuadRenderer.unbind();
        glBindTexture(GL_TEXTURE_2D, 0);

        // Restore default sampling state for other UI quads.
        shaderProgram.setUniform("u_useTextureArray", false);
        shaderProgram.setUniform("u_layerOverride", -1.0f);

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
    
    public void renderPauseMenuDepthCurtain() {
        if (depthCurtainRenderer != null) {
            depthCurtainRenderer.renderPauseMenuDepthCurtain();
        }
    }
    
    public void cleanup() {
        if (uiQuadRenderer != null) {
            uiQuadRenderer.cleanup();
        }
    }
}