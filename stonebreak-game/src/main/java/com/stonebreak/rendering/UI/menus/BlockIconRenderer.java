package com.stonebreak.rendering.UI.menus;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.models.blocks.BlockRenderer;
import com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager;
import com.stonebreak.rendering.core.API.commonBlockResources.models.BlockDefinitionRegistry;
import com.stonebreak.rendering.shaders.ShaderProgram;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.rendering.UI.UIRenderer;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Handles rendering of 3D block icons in inventory slots and UI contexts.
 * Provides clean separation of block icon rendering logic from the main renderer.
 */
public class BlockIconRenderer {
    
    private final BlockRenderer blockRenderer;
    private final UIRenderer uiRenderer;
    private final int windowHeight;
    
    public BlockIconRenderer(BlockRenderer blockRenderer, UIRenderer uiRenderer, int windowHeight) {
        this.blockRenderer = blockRenderer;
        this.uiRenderer = uiRenderer;
        this.windowHeight = windowHeight;
    }
    
    /**
     * Renders a 3D block icon in the specified slot area.
     * Handles both 3D cube blocks and flat 2D flower blocks.
     *
     * @param shaderProgram The shader program to use for rendering
     * @param type The block type to render
     * @param screenSlotX X coordinate of the slot
     * @param screenSlotY Y coordinate of the slot
     * @param screenSlotWidth Width of the slot
     * @param screenSlotHeight Height of the slot
     * @param textureAtlas The texture atlas containing block textures
     */
    public void draw3DItemInSlot(ShaderProgram shaderProgram, BlockType type, int screenSlotX, int screenSlotY,
                                int screenSlotWidth, int screenSlotHeight, TextureAtlas textureAtlas) {
        draw3DItemInSlot(shaderProgram, type, screenSlotX, screenSlotY, screenSlotWidth, screenSlotHeight, textureAtlas, false);
    }

    /**
     * Renders a 3D block icon in the specified slot area with optional dragged item positioning.
     * Handles both 3D cube blocks and flat 2D flower blocks.
     *
     * @param shaderProgram The shader program to use for rendering
     * @param type The block type to render
     * @param screenSlotX X coordinate of the slot
     * @param screenSlotY Y coordinate of the slot
     * @param screenSlotWidth Width of the slot
     * @param screenSlotHeight Height of the slot
     * @param textureAtlas The texture atlas containing block textures
     * @param isDraggedItem If true, renders the item closer to camera to avoid z-fighting
     */
    public void draw3DItemInSlot(ShaderProgram shaderProgram, BlockType type, int screenSlotX, int screenSlotY,
                                int screenSlotWidth, int screenSlotHeight, TextureAtlas textureAtlas, boolean isDraggedItem) {
        if (type == null || type.getAtlasX() == -1) {
            return; // Nothing to draw
        }

        // Check if this is a flower block - render as flat 2D texture instead of 3D cube
        // Note: Items (STICK, WOODEN_PICKAXE) are now in ItemType enum and handled separately
        if (type == BlockType.ROSE || type == BlockType.DANDELION) {
            renderFlowerIcon(shaderProgram, type, screenSlotX, screenSlotY, screenSlotWidth, screenSlotHeight, textureAtlas);
            return;
        }

        // Render 3D cube block
        render3DBlockIcon(shaderProgram, type, screenSlotX, screenSlotY, screenSlotWidth, screenSlotHeight, textureAtlas, isDraggedItem);
    }
    
    /**
     * Renders flower blocks as flat 2D textures by delegating to UI renderer.
     */
    private void renderFlowerIcon(ShaderProgram shaderProgram, BlockType type, int screenSlotX, int screenSlotY,
                                 int screenSlotWidth, int screenSlotHeight, TextureAtlas textureAtlas) {
        // Get current matrices from shader for restoration after UI rendering
        float[] projArray = new float[16];
        float[] viewArray = new float[16];
        
        // Get the current matrices from the shader program uniforms
        try {
            shaderProgram.getUniformMatrix4fv("projectionMatrix", projArray);
            shaderProgram.getUniformMatrix4fv("viewMatrix", viewArray);
        } catch (Exception e) {
            // Fallback to identity matrices if getting uniforms fails
            new Matrix4f().identity().get(projArray);
            new Matrix4f().identity().get(viewArray);
        }
        
        // Convert arrays to FloatBuffers for the UIRenderer method
        FloatBuffer projBuffer = BufferUtils.createFloatBuffer(16);
        FloatBuffer viewBuffer = BufferUtils.createFloatBuffer(16);
        projBuffer.put(projArray).flip();
        viewBuffer.put(viewArray).flip();
        
        // Delegate to UI renderer's flat 2D item drawing functionality
        uiRenderer.drawFlat2DItemInSlot(shaderProgram, type, screenSlotX, screenSlotY, screenSlotWidth, screenSlotHeight, 
                                      textureAtlas, projBuffer, viewBuffer);
    }
    
    /**
     * Renders 3D cube blocks with proper viewport isolation and state management.
     */
    private void render3DBlockIcon(ShaderProgram shaderProgram, BlockType type, int screenSlotX, int screenSlotY,
                                  int screenSlotWidth, int screenSlotHeight, TextureAtlas textureAtlas, boolean isDraggedItem) {

        // --- Save current GL state ---
        GLState originalState = saveGLState();

        try {
            // --- Setup GL state for 3D item rendering ---
            setupViewportAndScissor(screenSlotX, screenSlotY, screenSlotWidth, screenSlotHeight);
            setupDepthAndBlending(originalState, type);

            // --- Shader setup for 3D item ---
            configureShaderForItem(shaderProgram, textureAtlas, screenSlotX, screenSlotY, screenSlotWidth, screenSlotHeight, isDraggedItem);

            // --- Create and draw cube with proper face textures ---
            renderBlockCube(type, textureAtlas);

        } finally {
            // --- Restore previous GL state ---
            restoreGLState(shaderProgram, originalState);
        }
    }
    
    /**
     * Saves the current OpenGL state that will be modified during rendering.
     */
    private GLState saveGLState() {
        GLState state = new GLState();
        
        state.depthTestWasEnabled = glIsEnabled(GL_DEPTH_TEST);
        state.blendWasEnabled = glIsEnabled(GL_BLEND);
        state.cullFaceWasEnabled = glIsEnabled(GL_CULL_FACE);
        state.scissorWasEnabled = glIsEnabled(GL_SCISSOR_TEST);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer tempInt = stack.mallocInt(1);

            glGetIntegerv(GL_CURRENT_PROGRAM, tempInt);
            state.originalShaderProgram = tempInt.get(0);
            tempInt.clear();

            glGetIntegerv(GL_VERTEX_ARRAY_BINDING, tempInt);
            state.originalVao = tempInt.get(0);
            tempInt.clear();

            glGetIntegerv(GL_ACTIVE_TEXTURE, tempInt);
            state.originalActiveTexture = tempInt.get(0);
            tempInt.clear();

            // Assuming operations are on GL_TEXTURE0, save its binding
            glActiveTexture(GL_TEXTURE0);
            glGetIntegerv(GL_TEXTURE_BINDING_2D, tempInt);
            state.originalTextureBinding2D = tempInt.get(0);
            tempInt.clear();
            
            ByteBuffer tempByte = stack.malloc(1);
            glGetBooleanv(GL_DEPTH_WRITEMASK, tempByte);
            state.originalDepthMask = tempByte.get(0) == GL_TRUE;
            tempByte.clear();

            glGetIntegerv(GL_VIEWPORT, state.originalViewport);
            if (state.scissorWasEnabled) {
                glGetIntegerv(GL_SCISSOR_BOX, state.originalScissorBox);
            }

            if (state.blendWasEnabled) {
                glGetIntegerv(GL_BLEND_SRC_RGB, tempInt); state.originalBlendSrcRgb = tempInt.get(0); tempInt.clear();
                glGetIntegerv(GL_BLEND_DST_RGB, tempInt); state.originalBlendDstRgb = tempInt.get(0); tempInt.clear();
                glGetIntegerv(GL_BLEND_SRC_ALPHA, tempInt); state.originalBlendSrcAlpha = tempInt.get(0); tempInt.clear();
                glGetIntegerv(GL_BLEND_DST_ALPHA, tempInt); state.originalBlendDstAlpha = tempInt.get(0); tempInt.clear();
            } else {
                // Default values if blend was not enabled
                state.originalBlendSrcRgb = GL_SRC_ALPHA;
                state.originalBlendDstRgb = GL_ONE_MINUS_SRC_ALPHA;
                state.originalBlendSrcAlpha = GL_SRC_ALPHA;
                state.originalBlendDstAlpha = GL_ONE_MINUS_SRC_ALPHA;
            }
        }
        
        return state;
    }
    
    /**
     * Sets up viewport and scissor test for the specific slot area.
     */
    private void setupViewportAndScissor(int screenSlotX, int screenSlotY, int screenSlotWidth, int screenSlotHeight) {
        // Set viewport to the slot area and use scissor for clipping
        int currentWindowHeight = com.stonebreak.core.Game.getWindowHeight();
        
        // Convert coordinates from top-left origin (NanoVG) to bottom-left origin (OpenGL)
        int viewportX = screenSlotX;
        int viewportY = currentWindowHeight - (screenSlotY + screenSlotHeight);
        
        // Set up viewport for this slot
        glViewport(viewportX, viewportY, screenSlotWidth, screenSlotHeight);
        
        // Use scissor test to restrict drawing to this slot
        glEnable(GL_SCISSOR_TEST);
        glScissor(viewportX, viewportY, screenSlotWidth, screenSlotHeight);
    }
    
    /**
     * Configures depth testing and blending for 3D block rendering.
     */
    private void setupDepthAndBlending(GLState originalState, BlockType blockType) {
        // Enable depth testing for 3D rendering
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);

        // Handle leaf transparency - enable blending only for transparent leaf blocks
        boolean isLeafBlock = (blockType == BlockType.LEAVES || blockType == BlockType.PINE_LEAVES || blockType == BlockType.ELM_LEAVES);
        if (isLeafBlock && blockType.isTransparent()) {
            // Enable blending for transparent leaves
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        } else {
            // Disable blending for opaque blocks (including opaque leaves)
            if (originalState.blendWasEnabled) {
                glDisable(GL_BLEND);
            }
        }
    }
    
    /**
     * Configures the shader program and matrices for 3D item rendering.
     */
    private void configureShaderForItem(ShaderProgram shaderProgram, TextureAtlas textureAtlas,
                                       int screenSlotX, int screenSlotY, int screenSlotWidth, int screenSlotHeight, boolean isDraggedItem) {
        shaderProgram.bind();
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_transformUVsForItem", false);

        // Enable UI element mode for moderate brightness (80% instead of full lighting)
        shaderProgram.setUniform("u_isUIElement", true);

        // Use a small orthographic projection similar to original but aspect-correct
        float aspect = (float) screenSlotWidth / screenSlotHeight;
        float size = 0.6f;
        Matrix4f itemProjectionMatrix;
        if (aspect >= 1.0f) {
            // Wider than tall
            itemProjectionMatrix = new Matrix4f().ortho(-size * aspect, size * aspect, -size, size, 0.1f, 10.0f);
        } else {
            // Taller than wide
            itemProjectionMatrix = new Matrix4f().ortho(-size, size, -size / aspect, size / aspect, 0.1f, 10.0f);
        }
        shaderProgram.setUniform("projectionMatrix", itemProjectionMatrix);

        // Create view matrix with isometric view
        Matrix4f itemViewMatrix = new Matrix4f().identity();

        // Position dragged items closer to camera to avoid z-fighting with slot items
        float zOffset = isDraggedItem ? -1.0f : -1.5f; // Dragged items are 0.5 units closer
        itemViewMatrix.translate(0, 0, zOffset);

        itemViewMatrix.rotate((float) Math.toRadians(30.0f), 1.0f, 0.0f, 0.0f);
        itemViewMatrix.rotate((float) Math.toRadians(-45.0f), 0.0f, 1.0f, 0.0f);
        itemViewMatrix.scale(0.8f);
        shaderProgram.setUniform("viewMatrix", itemViewMatrix);

        // --- Bind texture atlas with defensive state reset ---
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureAtlas.getTextureId());
        // Force correct texture parameters to prevent corruption from block drops
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        shaderProgram.setUniform("texture_sampler", 0);
    }
    
    /**
     * Renders the actual block cube geometry.
     */
    private void renderBlockCube(BlockType type, TextureAtlas textureAtlas) {
        // Use CBR system for rendering block cube
        if (!blockRenderer.hasCBRSupport()) {
            throw new IllegalStateException("BlockIconRenderer requires CBR support. BlockRenderer must be initialized with BlockDefinitionRegistry.");
        }
        
        CBRResourceManager.BlockRenderResource resource = blockRenderer.getBlockRenderResource(type);
        resource.getMesh().bind();
        glDrawElements(GL_TRIANGLES, resource.getMesh().getIndexCount(), GL_UNSIGNED_INT, 0);
        resource.getMesh().unbind();
    }
    

    
    /**
     * Restores the OpenGL state to its original values.
     */
    private void restoreGLState(ShaderProgram shaderProgram, GLState originalState) {
        // Reset shader specific to this method's item drawing
        shaderProgram.setUniform("u_transformUVsForItem", false);
        shaderProgram.setUniform("u_isUIElement", false);

        // Restore viewport and scissor
        glViewport(originalState.originalViewport[0], originalState.originalViewport[1], 
                  originalState.originalViewport[2], originalState.originalViewport[3]);
        if (originalState.scissorWasEnabled) {
            glScissor(originalState.originalScissorBox[0], originalState.originalScissorBox[1], 
                     originalState.originalScissorBox[2], originalState.originalScissorBox[3]);
        } else {
            glDisable(GL_SCISSOR_TEST);
        }

        // Restore GL states
        glUseProgram(originalState.originalShaderProgram);
        glBindVertexArray(originalState.originalVao);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, originalState.originalTextureBinding2D);
        glActiveTexture(originalState.originalActiveTexture);

        if (originalState.cullFaceWasEnabled) {
            glEnable(GL_CULL_FACE);
        } else {
            glDisable(GL_CULL_FACE);
        }

        glDepthMask(originalState.originalDepthMask);

        if (originalState.blendWasEnabled) {
            glEnable(GL_BLEND);
            glBlendFuncSeparate(originalState.originalBlendSrcRgb, originalState.originalBlendDstRgb, 
                              originalState.originalBlendSrcAlpha, originalState.originalBlendDstAlpha);
        } else {
            glDisable(GL_BLEND);
        }
        
        if (originalState.depthTestWasEnabled) {
            glEnable(GL_DEPTH_TEST);
        } else {
            glDisable(GL_DEPTH_TEST);
        }
    }
    
    /**
     * Helper class to store OpenGL state for restoration.
     */
    private static class GLState {
        boolean depthTestWasEnabled;
        boolean blendWasEnabled;
        boolean cullFaceWasEnabled;
        boolean scissorWasEnabled;
        
        int originalShaderProgram;
        int originalVao;
        int originalActiveTexture;
        int originalTextureBinding2D;
        boolean originalDepthMask;
        int originalBlendSrcRgb, originalBlendDstRgb, originalBlendSrcAlpha, originalBlendDstAlpha;
        
        int[] originalViewport = new int[4];
        int[] originalScissorBox = new int[4];
    }
}