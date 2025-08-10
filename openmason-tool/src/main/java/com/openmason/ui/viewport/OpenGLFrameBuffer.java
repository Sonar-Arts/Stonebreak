package com.openmason.ui.viewport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Complete OpenGL framebuffer implementation for off-screen rendering.
 * 
 * This class creates and manages OpenGL framebuffers with color and depth attachments
 * for rendering 3D content to textures that can be displayed in Dear ImGui.
 * 
 * Key features:
 * - Color texture attachment for rendered content
 * - Depth buffer attachment for proper 3D depth testing
 * - Automatic framebuffer completeness validation
 * - Proper resource management and cleanup
 * - Resize support for dynamic viewport changes
 */
public class OpenGLFrameBuffer {
    
    private static final Logger logger = LoggerFactory.getLogger(OpenGLFrameBuffer.class);
    
    private int framebufferID = 0;
    private int colorTextureID = 0;
    private int depthTextureID = 0;
    private int width = 0;
    private int height = 0;
    private boolean initialized = false;
    
    /**
     * Creates a new framebuffer with the specified dimensions.
     */
    public OpenGLFrameBuffer(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Framebuffer dimensions must be positive: " + width + "x" + height);
        }
        
        this.width = width;
        this.height = height;
        initialize();
    }
    
    /**
     * Initialize the OpenGL framebuffer and attachments.
     */
    private void initialize() {
        logger.debug("Initializing OpenGL framebuffer: {}x{}", width, height);
        
        try {
            // Generate framebuffer
            framebufferID = glGenFramebuffers();
            if (framebufferID == 0) {
                throw new RuntimeException("Failed to generate framebuffer");
            }
            
            glBindFramebuffer(GL_FRAMEBUFFER, framebufferID);
            
            // Create color texture attachment
            createColorTexture();
            
            // Create depth texture attachment
            createDepthTexture();
            
            // Attach textures to framebuffer
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTextureID, 0);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthTextureID, 0);
            
            // Specify which color attachments to draw to
            glDrawBuffer(GL_COLOR_ATTACHMENT0);
            
            // Check framebuffer completeness
            int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            if (status != GL_FRAMEBUFFER_COMPLETE) {
                String errorMsg = getFramebufferStatusString(status);
                throw new RuntimeException("Framebuffer is not complete: " + errorMsg);
            }
            
            // Unbind framebuffer
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            
            initialized = true;
            logger.info("OpenGL framebuffer initialized successfully: {}x{}", width, height);
            
        } catch (Exception e) {
            logger.error("Failed to initialize OpenGL framebuffer", e);
            cleanup();
            throw new RuntimeException("Framebuffer initialization failed", e);
        }
    }
    
    /**
     * Create color texture attachment.
     */
    private void createColorTexture() {
        colorTextureID = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, colorTextureID);
        
        // Allocate texture storage
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        
        // Set texture parameters
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        
        glBindTexture(GL_TEXTURE_2D, 0);
        
        logger.debug("Color texture created: ID={}, size={}x{}", colorTextureID, width, height);
    }
    
    /**
     * Create depth texture attachment.
     */
    private void createDepthTexture() {
        depthTextureID = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, depthTextureID);
        
        // Allocate depth texture storage
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, width, height, 0, GL_DEPTH_COMPONENT, GL_FLOAT, (java.nio.ByteBuffer) null);
        
        // Set texture parameters
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        
        glBindTexture(GL_TEXTURE_2D, 0);
        
        logger.debug("Depth texture created: ID={}, size={}x{}", depthTextureID, width, height);
    }
    
    /**
     * Bind this framebuffer for rendering.
     */
    public void bind() {
        if (!initialized) {
            throw new IllegalStateException("Framebuffer not initialized");
        }
        
        glBindFramebuffer(GL_FRAMEBUFFER, framebufferID);
        glViewport(0, 0, width, height);
    }
    
    /**
     * Unbind this framebuffer (bind default framebuffer).
     */
    public void unbind() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }
    
    /**
     * Clear the framebuffer with specified color and depth values.
     */
    public void clear(float r, float g, float b, float a, float depth) {
        if (!initialized) {
            return;
        }
        
        bind();
        glClearColor(r, g, b, a);
        glClearDepth(depth);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }
    
    /**
     * Clear the framebuffer with default values.
     */
    public void clear() {
        clear(0.2f, 0.2f, 0.2f, 1.0f, 1.0f);
    }
    
    /**
     * Resize the framebuffer to new dimensions.
     */
    public void resize(int newWidth, int newHeight) {
        if (newWidth <= 0 || newHeight <= 0) {
            logger.warn("Invalid resize dimensions: {}x{}", newWidth, newHeight);
            return;
        }
        
        if (newWidth == width && newHeight == height) {
            return; // No change needed
        }
        
        logger.debug("Resizing framebuffer from {}x{} to {}x{}", width, height, newWidth, newHeight);
        
        // Clean up existing resources
        cleanup();
        
        // Update dimensions and reinitialize
        this.width = newWidth;
        this.height = newHeight;
        initialize();
    }
    
    /**
     * Get the color texture ID for rendering in ImGui.
     */
    public int getColorTextureID() {
        return colorTextureID;
    }
    
    /**
     * Get the depth texture ID.
     */
    public int getDepthTextureID() {
        return depthTextureID;
    }
    
    /**
     * Get the framebuffer ID.
     */
    public int getFramebufferID() {
        return framebufferID;
    }
    
    /**
     * Get framebuffer width.
     */
    public int getWidth() {
        return width;
    }
    
    /**
     * Get framebuffer height.
     */
    public int getHeight() {
        return height;
    }
    
    /**
     * Check if framebuffer is initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * Clean up OpenGL resources.
     */
    public void cleanup() {
        if (colorTextureID != 0) {
            glDeleteTextures(colorTextureID);
            colorTextureID = 0;
            logger.debug("Color texture deleted");
        }
        
        if (depthTextureID != 0) {
            glDeleteTextures(depthTextureID);
            depthTextureID = 0;
            logger.debug("Depth texture deleted");
        }
        
        if (framebufferID != 0) {
            glDeleteFramebuffers(framebufferID);
            framebufferID = 0;
            logger.debug("Framebuffer deleted");
        }
        
        initialized = false;
        logger.debug("Framebuffer cleanup completed");
    }
    
    /**
     * Get human-readable framebuffer status string.
     */
    private String getFramebufferStatusString(int status) {
        return switch (status) {
            case GL_FRAMEBUFFER_COMPLETE -> "GL_FRAMEBUFFER_COMPLETE";
            case GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT -> "GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT";
            case GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT -> "GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT";
            case GL_FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER -> "GL_FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER";
            case GL_FRAMEBUFFER_INCOMPLETE_READ_BUFFER -> "GL_FRAMEBUFFER_INCOMPLETE_READ_BUFFER";
            case GL_FRAMEBUFFER_UNSUPPORTED -> "GL_FRAMEBUFFER_UNSUPPORTED";
            case GL_FRAMEBUFFER_INCOMPLETE_MULTISAMPLE -> "GL_FRAMEBUFFER_INCOMPLETE_MULTISAMPLE";
            default -> "Unknown status: " + status;
        };
    }
    
    /**
     * Validate current framebuffer state.
     */
    public boolean validate() {
        if (!initialized) {
            return false;
        }
        
        bind();
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        unbind();
        
        boolean isValid = status == GL_FRAMEBUFFER_COMPLETE;
        if (!isValid) {
            logger.warn("Framebuffer validation failed: {}", getFramebufferStatusString(status));
        }
        
        return isValid;
    }
    
    @Override
    public String toString() {
        return String.format("OpenGLFrameBuffer{id=%d, size=%dx%d, colorTex=%d, depthTex=%d, initialized=%s}",
            framebufferID, width, height, colorTextureID, depthTextureID, initialized);
    }
}