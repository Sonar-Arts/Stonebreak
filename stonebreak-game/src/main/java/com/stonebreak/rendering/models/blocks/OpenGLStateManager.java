package com.stonebreak.rendering.models.blocks;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Manages OpenGL state save/restore operations for isolated rendering.
 */
public class OpenGLStateManager {
    
    public static class SavedState {
        public final boolean depthTestEnabled;
        public final boolean blendEnabled;
        public final boolean cullFaceEnabled;
        public final int[] viewport;
        
        SavedState(boolean depthTest, boolean blend, boolean cullFace, int[] viewport) {
            this.depthTestEnabled = depthTest;
            this.blendEnabled = blend;
            this.cullFaceEnabled = cullFace;
            this.viewport = viewport.clone();
        }
    }
    
    /**
     * Save current OpenGL state.
     */
    public static SavedState saveState() {
        boolean depthTest = glIsEnabled(GL_DEPTH_TEST);
        boolean blend = glIsEnabled(GL_BLEND);
        boolean cullFace = glIsEnabled(GL_CULL_FACE);
        int[] viewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, viewport);
        
        return new SavedState(depthTest, blend, cullFace, viewport);
    }
    
    /**
     * Configure OpenGL state for block drop rendering.
     */
    public static void configureForBlockDrops() {
        glEnable(GL_DEPTH_TEST);
        glDepthMask(true);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    }
    
    /**
     * Configure OpenGL state for 2D sprite rendering.
     */
    public static void configureFor2DSprites() {
        configureForBlockDrops();
        glDisable(GL_CULL_FACE);
    }
    
    /**
     * Restore OpenGL state from saved state.
     */
    public static void restoreState(SavedState state) {
        // Reset to clean defaults first
        glBindVertexArray(0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);
        
        // Restore viewport
        glViewport(state.viewport[0], state.viewport[1], state.viewport[2], state.viewport[3]);
        
        // Restore blending state
        if (state.blendEnabled) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        } else {
            glDisable(GL_BLEND);
        }
        
        // Restore depth testing state
        if (state.depthTestEnabled) {
            glEnable(GL_DEPTH_TEST);
        } else {
            glDisable(GL_DEPTH_TEST);
        }
        
        // Restore face culling state
        if (state.cullFaceEnabled) {
            glEnable(GL_CULL_FACE);
        } else {
            glDisable(GL_CULL_FACE);
        }
        
        glDepthMask(true);
    }
}