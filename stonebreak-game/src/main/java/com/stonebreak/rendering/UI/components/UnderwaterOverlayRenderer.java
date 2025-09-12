package com.stonebreak.rendering.UI.components;

import com.stonebreak.player.Player;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * Renders a blue overlay effect when the player is underwater.
 * Provides a visual indication that the player is submerged.
 */
public class UnderwaterOverlayRenderer {
    
    private static final float OVERLAY_OPACITY = 0.3f;
    private static final float OVERLAY_RED = 0.0f;
    private static final float OVERLAY_GREEN = 0.4f;
    private static final float OVERLAY_BLUE = 0.8f;
    
    private float currentOpacity = 0.0f;
    private static final float FADE_SPEED = 3.0f;
    
    /**
     * Updates the underwater overlay state based on player position.
     * @param player The player to check
     * @param deltaTime Time elapsed since last frame
     */
    public void update(Player player, float deltaTime) {
        if (player == null) return;
        
        boolean shouldShowOverlay = player.isInWater();
        float targetOpacity = shouldShowOverlay ? OVERLAY_OPACITY : 0.0f;
        
        // Smooth fade transition
        if (currentOpacity != targetOpacity) {
            if (currentOpacity < targetOpacity) {
                currentOpacity = Math.min(targetOpacity, currentOpacity + FADE_SPEED * deltaTime);
            } else {
                currentOpacity = Math.max(targetOpacity, currentOpacity - FADE_SPEED * deltaTime);
            }
        }
    }
    
    /**
     * Renders the underwater overlay if the player is underwater.
     * This should be called after all other rendering is complete.
     * @param windowWidth Width of the window
     * @param windowHeight Height of the window
     */
    public void render(int windowWidth, int windowHeight) {
        if (currentOpacity <= 0.0f) {
            return;
        }
        
        // Save current OpenGL state
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        
        // Set up for 2D overlay rendering
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(0, windowWidth, windowHeight, 0, -1, 1);
        
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        
        // Disable depth testing and enable blending
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        
        // Disable texturing
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        
        // Set the overlay color with current opacity
        GL11.glColor4f(OVERLAY_RED, OVERLAY_GREEN, OVERLAY_BLUE, currentOpacity);
        
        // Draw a fullscreen quad
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(0, 0);
        GL11.glVertex2f(windowWidth, 0);
        GL11.glVertex2f(windowWidth, windowHeight);
        GL11.glVertex2f(0, windowHeight);
        GL11.glEnd();
        
        // Restore OpenGL state
        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }
    
    /**
     * Gets the current overlay opacity for debugging purposes.
     * @return Current opacity value (0.0 to 1.0)
     */
    public float getCurrentOpacity() {
        return currentOpacity;
    }
    
    /**
     * Checks if the underwater overlay is currently visible.
     * @return true if overlay has any opacity
     */
    public boolean isVisible() {
        return currentOpacity > 0.0f;
    }
}