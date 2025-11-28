package com.openmason.ui;

import imgui.ImGui;
import imgui.ImVec2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.IOException;
import org.lwjgl.BufferUtils;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Manages the Open Mason logo display and application icon functionality.
 * Handles loading, texture creation, and rendering of the logo in the ImGui interface.
 */
public class LogoManager {
    
    private static final Logger logger = LoggerFactory.getLogger(LogoManager.class);
    
    // Singleton instance
    private static LogoManager instance;
    
    // Logo texture and metadata
    private int logoTextureId = -1;
    private int logoWidth = 0;
    private int logoHeight = 0;
    private boolean logoLoaded = false;
    
    // Logo resource path
    private static final String LOGO_RESOURCE_PATH = "/icons/Logo/Open Mason Logo.png";
    
    // Default logo display sizes
    public static final float SMALL_LOGO_SIZE = 24.0f;
    public static final float LARGE_LOGO_SIZE = 96.0f;
    
    private LogoManager() {
        loadLogo();
    }
    
    public static synchronized LogoManager getInstance() {
        if (instance == null) {
            instance = new LogoManager();
        }
        return instance;
    }
    
    /**
     * Load the Open Mason logo from resources and create OpenGL texture
     */
    private void loadLogo() {
        try {
            // Load image from resources
            InputStream logoStream = getClass().getResourceAsStream(LOGO_RESOURCE_PATH);
            if (logoStream == null) {
                logger.error("Logo resource not found: {}", LOGO_RESOURCE_PATH);
                return;
            }
            
            BufferedImage logoImage = ImageIO.read(logoStream);
            if (logoImage == null) {
                logger.error("Failed to read logo image from: {}", LOGO_RESOURCE_PATH);
                return;
            }
            
            logoWidth = logoImage.getWidth();
            logoHeight = logoImage.getHeight();
            
            // Convert BufferedImage to ByteBuffer for OpenGL
            ByteBuffer imageBuffer = BufferUtils.createByteBuffer(logoWidth * logoHeight * 4);
            
            for (int y = 0; y < logoHeight; y++) {
                for (int x = 0; x < logoWidth; x++) {
                    int pixel = logoImage.getRGB(x, y);
                    imageBuffer.put((byte) ((pixel >> 16) & 0xFF)); // Red
                    imageBuffer.put((byte) ((pixel >> 8) & 0xFF));  // Green
                    imageBuffer.put((byte) (pixel & 0xFF));         // Blue
                    imageBuffer.put((byte) ((pixel >> 24) & 0xFF)); // Alpha
                }
            }
            imageBuffer.flip();
            
            // Create OpenGL texture
            logoTextureId = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, logoTextureId);
            
            // Set texture parameters
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            
            // Upload texture data
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, logoWidth, logoHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, imageBuffer);
            
            // Unbind texture
            glBindTexture(GL_TEXTURE_2D, 0);
            
            logoLoaded = true;
            logger.info("Open Mason logo loaded successfully: {}x{} pixels, texture ID: {}", 
                       logoWidth, logoHeight, logoTextureId);
            
        } catch (IOException e) {
            logger.error("Failed to load Open Mason logo", e);
        } catch (Exception e) {
            logger.error("Unexpected error while loading logo", e);
        }
    }
    
    /**
     * Render the logo in ImGui at the specified size
     * @param size The desired size for the logo (width and height will be the same)
     */
    public void renderLogo(float size) {
        renderLogo(size, size);
    }
    
    /**
     * Render the logo in ImGui with specified width and height
     * @param width The desired width
     * @param height The desired height
     */
    public void renderLogo(float width, float height) {
        if (!logoLoaded || logoTextureId == -1) {
            // Show placeholder if logo not loaded
            ImGui.button("OM", width, height);
            return;
        }
        
        ImGui.image(logoTextureId, width, height);
    }

    
    /**
     * Render logo in the main menu bar
     */
    public void renderMenuBarLogo() {
        if (!logoLoaded) return;
        
        // Add some spacing before the logo
        ImGui.spacing();
        ImGui.sameLine();
        
        // Render small logo
        renderLogo(SMALL_LOGO_SIZE);
        
        // Add tooltip on hover
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Open Mason - 3D Model & Texture Tool");
        }
    }
    
    /**
     * Render logo in about dialog or splash screen
     */
    public void renderAboutLogo() {
        if (!logoLoaded) return;
        
        // Center the logo
        float windowWidth = ImGui.getWindowSize().x;
        float logoSize = LARGE_LOGO_SIZE;
        ImGui.setCursorPosX((windowWidth - logoSize) * 0.5f);
        
        renderLogo(logoSize);
        
        // Add some spacing after the logo
        ImGui.spacing();
        ImGui.spacing();
    }
    
    /**
     * Get the calculated size for the logo to fit within given constraints while maintaining aspect ratio
     */
    public ImVec2 getScaledLogoSize(float maxWidth, float maxHeight) {
        if (!logoLoaded) {
            return new ImVec2(maxWidth, maxHeight);
        }
        
        float aspectRatio = (float) logoWidth / logoHeight;
        
        float scaledWidth = maxWidth;
        float scaledHeight = maxWidth / aspectRatio;
        
        if (scaledHeight > maxHeight) {
            scaledHeight = maxHeight;
            scaledWidth = maxHeight * aspectRatio;
        }
        
        return new ImVec2(scaledWidth, scaledHeight);
    }
    
    /**
     * Cleanup resources when shutting down
     */
    public void dispose() {
        if (logoTextureId != -1) {
            glDeleteTextures(logoTextureId);
            logoTextureId = -1;
            logoLoaded = false;
            logger.info("Logo texture resources cleaned up");
        }
    }
    
}