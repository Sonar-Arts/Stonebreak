package com.openmason.main.systems.viewport.resources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Manages framebuffer resources (framebuffer, color texture, depth renderbuffer).
 * Follows RAII pattern for automatic resource cleanup.
 */
public class FramebufferResource implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(FramebufferResource.class);

    private int framebufferId = -1;
    private int colorTextureId = -1;
    private int depthRenderbufferId = -1;
    private int width;
    private int height;
    private boolean initialized = false;

    /**
     * Create framebuffer with specified dimensions.
     */
    public void create(int width, int height) {
        if (initialized) {
            // Silently cleanup and recreate (resize operations trigger this frequently)
            cleanup();
        }

        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid framebuffer dimensions: " + width + "x" + height);
        }

        this.width = width;
        this.height = height;

        try {
            // Generate framebuffer
            framebufferId = glGenFramebuffers();
            if (framebufferId == 0) {
                throw new RuntimeException("Failed to generate framebuffer");
            }
            glBindFramebuffer(GL_FRAMEBUFFER, framebufferId);

            // Create color texture
            colorTextureId = glGenTextures();
            if (colorTextureId == 0) {
                throw new RuntimeException("Failed to generate color texture");
            }
            glBindTexture(GL_TEXTURE_2D, colorTextureId);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, width, height, 0, GL_RGB, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTextureId, 0);

            // Create depth renderbuffer
            depthRenderbufferId = glGenRenderbuffers();
            if (depthRenderbufferId == 0) {
                throw new RuntimeException("Failed to generate depth renderbuffer");
            }
            glBindRenderbuffer(GL_RENDERBUFFER, depthRenderbufferId);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT, width, height);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthRenderbufferId);

            // Check framebuffer completeness
            int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            if (status != GL_FRAMEBUFFER_COMPLETE) {
                String errorMessage = getFramebufferErrorMessage(status);
                throw new RuntimeException("Framebuffer not complete: " + errorMessage);
            }

            // Unbind framebuffer
            glBindFramebuffer(GL_FRAMEBUFFER, 0);

            initialized = true;
            logger.trace("Framebuffer created successfully: {}x{} (FBO: {}, Texture: {}, Depth: {})",
                        width, height, framebufferId, colorTextureId, depthRenderbufferId);

        } catch (Exception e) {
            logger.error("Failed to create framebuffer", e);
            cleanup(); // Clean up partial resources
            throw new RuntimeException("Framebuffer creation failed", e);
        }
    }

    /**
     * Resize framebuffer (recreates resources).
     */
    public void resize(int newWidth, int newHeight) {
        if (newWidth == width && newHeight == height) {
            return; // No change needed
        }

        logger.trace("Resizing framebuffer from {}x{} to {}x{}", width, height, newWidth, newHeight);
        create(newWidth, newHeight);
    }

    /**
     * Bind this framebuffer for rendering.
     */
    public void bind() {
        if (!initialized) {
            throw new IllegalStateException("Framebuffer not initialized");
        }
        glBindFramebuffer(GL_FRAMEBUFFER, framebufferId);
        glViewport(0, 0, width, height);
    }

    /**
     * Unbind framebuffer (bind default framebuffer 0).
     */
    public void unbind() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * Get human-readable framebuffer error message.
     */
    private String getFramebufferErrorMessage(int status) {
        return switch (status) {
            case GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT -> "Incomplete attachment";
            case GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT -> "Missing attachment";
            case GL_FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER -> "Incomplete draw buffer";
            case GL_FRAMEBUFFER_INCOMPLETE_READ_BUFFER -> "Incomplete read buffer";
            case GL_FRAMEBUFFER_UNSUPPORTED -> "Unsupported framebuffer format";
            default -> "Unknown error (status: " + status + ")";
        };
    }

    /**
     * Clean up OpenGL resources.
     */
    private void cleanup() {
        if (colorTextureId != -1) {
            glDeleteTextures(colorTextureId);
            colorTextureId = -1;
        }
        if (depthRenderbufferId != -1) {
            glDeleteRenderbuffers(depthRenderbufferId);
            depthRenderbufferId = -1;
        }
        if (framebufferId != -1) {
            glDeleteFramebuffers(framebufferId);
            framebufferId = -1;
        }
        initialized = false;
    }

    @Override
    public void close() {
        if (initialized) {
            logger.debug("Closing framebuffer resource: {}x{}", width, height);
            cleanup();
        }
    }

    // Getters
    public int getColorTextureId() { return colorTextureId; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public boolean isInitialized() { return initialized; }

    /**
     * Validate that framebuffer is ready for use.
     */
    public void validate() {
        if (!initialized) {
            throw new IllegalStateException("Framebuffer not initialized");
        }
        if (framebufferId == -1 || colorTextureId == -1 || depthRenderbufferId == -1) {
            throw new IllegalStateException("Framebuffer resources invalid");
        }
    }
}
