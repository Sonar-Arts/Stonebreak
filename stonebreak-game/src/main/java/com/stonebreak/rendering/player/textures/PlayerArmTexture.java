package com.stonebreak.rendering.player.textures;

import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;

/**
 * Manages the creation and management of Minecraft-style player arm textures.
 * Handles procedural generation of skin tones and clothing overlays.
 */
public class PlayerArmTexture {
    
    private int armTextureId;
    
    // Minecraft Steve default skin colors
    private static final int SKIN_R = 245; // Light skin tone
    private static final int SKIN_G = 220;
    private static final int SKIN_B = 165;
    
    private static final int SHIRT_R = 111; // Blue shirt/sleeve color  
    private static final int SHIRT_G = 124;
    private static final int SHIRT_B = 172;
    
    private static final int TEXTURE_WIDTH = 64;  // Minecraft skin texture width
    private static final int TEXTURE_HEIGHT = 64; // Minecraft skin texture height
    
    /**
     * Creates a Minecraft-style arm texture with pixelated skin appearance.
     */
    public int createArmTexture() {
        if (armTextureId != 0) {
            return armTextureId; // Already created
        }
        
        ByteBuffer buffer = BufferUtils.createByteBuffer(TEXTURE_WIDTH * TEXTURE_HEIGHT * 4); // RGBA

        for (int y = 0; y < TEXTURE_HEIGHT; y++) {
            for (int x = 0; x < TEXTURE_WIDTH; x++) {
                byte r, g, b, a = (byte) 255;
                
                // Create Minecraft-style pixelated pattern
                // Arm area in Minecraft skin layout: roughly x=40-48, y=16-32 for right arm
                boolean isArmArea = (x >= 40 && x < 48 && y >= 16 && y < 32);
                boolean isSleeveArea = (x >= 40 && x < 48 && y >= 0 && y < 16); // Sleeve overlay
                
                if (isSleeveArea) {
                    // Blue shirt sleeve
                    r = (byte) SHIRT_R;
                    g = (byte) SHIRT_G;
                    b = (byte) SHIRT_B;
                } else if (isArmArea) {
                    // Skin tone with slight variation for pixelated look
                    int variation = ((x + y) % 3) - 1; // -1, 0, or 1
                    r = (byte) Math.max(0, Math.min(255, SKIN_R + variation * 5));
                    g = (byte) Math.max(0, Math.min(255, SKIN_G + variation * 3));
                    b = (byte) Math.max(0, Math.min(255, SKIN_B + variation * 2));
                } else {
                    // Default skin tone for other areas
                    r = (byte) SKIN_R;
                    g = (byte) SKIN_G;
                    b = (byte) SKIN_B;
                }

                buffer.put(r);
                buffer.put(g);
                buffer.put(b);
                buffer.put(a);
            }
        }
        buffer.flip();

        armTextureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, armTextureId);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); // Keep pixelated look
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST); // Keep pixelated look
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, TEXTURE_WIDTH, TEXTURE_HEIGHT, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        glBindTexture(GL_TEXTURE_2D, 0);
        
        return armTextureId;
    }
    
    /**
     * Gets the arm texture ID.
     */
    public int getArmTextureId() {
        return armTextureId;
    }
    
    /**
     * Cleanup texture resources.
     */
    public void cleanup() {
        if (armTextureId != 0) {
            glDeleteTextures(armTextureId);
            armTextureId = 0;
        }
    }
}