package com.stonebreak;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;

/**
 * Represents the pause menu that appears when the user presses the escape key.
 */
public class PauseMenu {
    
    // Menu state
    private boolean visible = false;
    
    // UI elements
    private int menuPanelVao;
    private int menuButtonVao;
    
    // Vertex counts for drawing
    private int menuPanelVertexCount;
    private int menuButtonVertexCount;
    
    /**
     * Creates a new pause menu.
     */
    public PauseMenu() {
        createMenuPanel();
        createQuitButton();
    }
    
    /**
     * Creates the menu background panel.
     */
    private void createMenuPanel() {
        // Create a semi-transparent panel in the center of the screen
        float[] vertices = {
            -0.3f, -0.4f, 0.0f,  // Bottom left
            0.3f, -0.4f, 0.0f,   // Bottom right
            0.3f, 0.4f, 0.0f,    // Top right
            -0.3f, 0.4f, 0.0f    // Top left
        };
        
        int[] indices = {
            0, 1, 2,  // First triangle
            2, 3, 0   // Second triangle
        };
        
        menuPanelVertexCount = indices.length;
        
        // Create VAO
        menuPanelVao = glGenVertexArrays();
        glBindVertexArray(menuPanelVao);
        
        // Create vertex VBO
        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        
        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();
        glBufferData(GL_ARRAY_BUFFER, vertexBuffer, GL_STATIC_DRAW);
        
        // Define vertex attributes
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(0);
        
        // Create index VBO
        int ibo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo);
        
        IntBuffer indexBuffer = BufferUtils.createIntBuffer(indices.length);
        indexBuffer.put(indices).flip();
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL_STATIC_DRAW);
        
        // Unbind
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }
    
    /**
     * Creates the quit button.
     */
    private void createQuitButton() {
        // Create a button near the center of the screen
        float[] vertices = {
            -0.2f, -0.05f, 0.0f,  // Bottom left
            0.2f, -0.05f, 0.0f,   // Bottom right
            0.2f, 0.05f, 0.0f,    // Top right
            -0.2f, 0.05f, 0.0f    // Top left
        };
        
        int[] indices = {
            0, 1, 2,  // First triangle
            2, 3, 0   // Second triangle
        };
        
        menuButtonVertexCount = indices.length;
        
        // Create VAO
        menuButtonVao = glGenVertexArrays();
        glBindVertexArray(menuButtonVao);
        
        // Create vertex VBO
        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        
        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();
        glBufferData(GL_ARRAY_BUFFER, vertexBuffer, GL_STATIC_DRAW);
        
        // Define vertex attributes
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(0);
        
        // Create index VBO
        int ibo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo);
        
        IntBuffer indexBuffer = BufferUtils.createIntBuffer(indices.length);
        indexBuffer.put(indices).flip();
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL_STATIC_DRAW);
        
        // Unbind
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }
    
    /**
     * Renders the pause menu.
     */
    public void render(ShaderProgram shaderProgram, Renderer renderer) { // Added Renderer parameter
        if (!visible) {
            return;
        }
        
        // Disable depth testing for UI
        glDisable(GL_DEPTH_TEST);
        
        // Use shader program
        shaderProgram.bind();
        
        // Set orthographic projection for UI (NDC: -1 to 1)
        Matrix4f ndcOrthoProjection = new Matrix4f().ortho(-1.0f, 1.0f, -1.0f, 1.0f, -1.0f, 1.0f);
        shaderProgram.setUniform("projectionMatrix", ndcOrthoProjection);
        shaderProgram.setUniform("viewMatrix", new Matrix4f());
        
        // Enable blending for transparent elements
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        
        // Render menu panel (semi-transparent black background)
        glBindVertexArray(menuPanelVao);
        // Set shader for solid color and set the color for the panel
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_color", new Vector4f(0.2f, 0.2f, 0.2f, 0.8f));
        glDrawElements(GL_TRIANGLES, menuPanelVertexCount, GL_UNSIGNED_INT, 0);
          // Render quit button (red)
        glBindVertexArray(menuButtonVao);
        // Set shader for solid color and set the color for the button
        shaderProgram.setUniform("u_color", new Vector4f(0.8f, 0.2f, 0.2f, 1.0f));
        glDrawElements(GL_TRIANGLES, menuButtonVertexCount, GL_UNSIGNED_INT, 0);

        // Draw "Quit" text on the button
        // Adjust x, y, and color as needed.
        // Coordinates for text are tricky; STBTT uses baseline y.
        
        // Enable blending for text rendering
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);        // Switch to pixel-based orthographic projection for text
        // Set Y-origin at top, Y-increases downwards to match normal screen coordinates
        Matrix4f pixelOrthoProjection = new Matrix4f().ortho(
            0.0f, renderer.getWindowWidth(), 
            renderer.getWindowHeight(), 0.0f, // Y flipped (top=0, bottom=height)
            -1.0f, 1.0f);
        shaderProgram.setUniform("projectionMatrix", pixelOrthoProjection);
        
        // Set shader to text rendering mode
        shaderProgram.setUniform("u_isText", true);
        
        Font font = renderer.getFont();
        String quitText = "QUIT";  // Using uppercase for better visibility
        float fontSize = font.getFontSize();
        float textWidth = font.getTextWidth(quitText);
        
        // Button NDC coordinates: x: -0.2 to 0.2, y: -0.05 to 0.05
        // Convert button center from NDC to pixels
        float buttonCenterXpx = (0.0f + 1.0f) * renderer.getWindowWidth() / 2.0f;  // Center of button
        float buttonCenterYpx = (0.0f + 1.0f) * renderer.getWindowHeight() / 2.0f; // Center of button
        
        // Calculate text position to center it on button
        float textPixelX = buttonCenterXpx - (textWidth / 2.0f);
        // Adjust Y position for the text baseline (with Y flipped, we add to move down)
        float textPixelY = buttonCenterYpx + (fontSize / 4.0f); // Adjusted for better vertical centering
        
        // Draw text with white color (fully opaque)
        renderer.drawText(quitText, textPixelX, textPixelY, new Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
          // Restore NDC projection for other UI elements if any were to follow
        shaderProgram.setUniform("projectionMatrix", ndcOrthoProjection);

        // Reset shader states
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_isText", false);
        
        // Ensure texture is unbound
        glBindTexture(GL_TEXTURE_2D, 0);

        // Unbind VAO and shader
        glBindVertexArray(0);
        shaderProgram.unbind();
        
        // Restore GL state
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
    }
    
    /**
     * Checks if the menu is currently visible.
     */
    public boolean isVisible() {
        return visible;
    }
    
    /**
     * Sets the visibility of the menu.
     */
    public void setVisible(boolean visible) {
        this.visible = visible;
    }
    
    /**
     * Toggles the visibility of the menu.
     */
    public void toggleVisibility() {
        this.visible = !this.visible;
    }
    
    /**
     * Checks if the given coordinates are within the quit button.
     * Coordinates should be in normalized device coordinates (-1 to 1).
     */
    public boolean isQuitButtonClicked(float x, float y) {
        return (visible && 
                x >= -0.2f && x <= 0.2f && 
                y >= -0.05f && y <= 0.05f);
    }
    
    /**
     * Cleanup resources.
     */
    public void cleanup() {
        glDeleteVertexArrays(menuPanelVao);
        glDeleteVertexArrays(menuButtonVao);
    }
}
