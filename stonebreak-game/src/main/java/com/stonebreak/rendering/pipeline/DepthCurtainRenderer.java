package com.stonebreak.rendering.pipeline;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.shaders.ShaderProgram;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;

/**
 * Specialized renderer for depth curtains that occlude block drops behind UI elements.
 * This creates proper depth values for block drop occlusion without visual interference.
 * 
 * Depth curtains are invisible quads that write to the depth buffer but not the color buffer,
 * effectively "blocking" 3D objects from appearing behind UI elements like inventory panels,
 * hotbar, pause menu, etc.
 */
public class DepthCurtainRenderer {
    
    private final ShaderProgram shaderProgram;
    private final int windowWidth;
    private final int windowHeight;
    private final Matrix4f projectionMatrix;
    
    // UI screen dimensions (matching InventoryScreen calculations)
    private static final int SLOT_SIZE = 40;
    private static final int SLOT_PADDING = 5;
    private static final int TITLE_HEIGHT = 30;
    private static final int CRAFTING_GRID_SIZE = 2;
    private static final int MAIN_INVENTORY_COLS = 9; // Inventory.MAIN_INVENTORY_COLS
    private static final int MAIN_INVENTORY_ROWS = 3; // Inventory.MAIN_INVENTORY_ROWS
    private static final int HOTBAR_SIZE = 9; // Inventory.HOTBAR_SIZE
    private static final int HOTBAR_Y_OFFSET = 20;
    
    public DepthCurtainRenderer(ShaderProgram shaderProgram, int windowWidth, int windowHeight, Matrix4f projectionMatrix) {
        this.shaderProgram = shaderProgram;
        this.windowWidth = windowWidth;
        this.windowHeight = windowHeight;
        this.projectionMatrix = projectionMatrix;
    }
    
    /**
     * Renders an invisible depth curtain to occlude block drops behind inventory UI.
     * This creates proper depth values for block drop occlusion without visual interference.
     */
    public void renderInventoryDepthCurtain() {
        // Save current GL state
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);
        
        // Set up depth curtain rendering state
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_ALWAYS);     // Always pass depth test (renders over world)
        glDepthMask(true);          // Write to depth buffer (this is key!)
        glDisable(GL_BLEND);        // No blending needed for invisible quad
        glColorMask(false, false, false, false); // Don't write to color buffer (invisible)
        
        // Set up orthographic projection for screen-space rendering
        Matrix4f orthoProjection = new Matrix4f().ortho(0, windowWidth, windowHeight, 0, -1, 1);
        Matrix4f identityView = new Matrix4f().identity();
        Matrix4f modelMatrix = new Matrix4f().identity();
        
        // Bind shader and set uniforms
        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", orthoProjection);
        shaderProgram.setUniform("viewMatrix", identityView);
        shaderProgram.setUniform("modelMatrix", modelMatrix);
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_color", new org.joml.Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
        
        // Calculate inventory panel dimensions (EXACTLY match InventoryScreen calculations)
        int baseInventoryPanelWidth = MAIN_INVENTORY_COLS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int craftingGridVisualWidth = CRAFTING_GRID_SIZE * SLOT_SIZE + (CRAFTING_GRID_SIZE - 1) * SLOT_PADDING;
        int craftingElementsTotalWidth = craftingGridVisualWidth + SLOT_SIZE + SLOT_PADDING + SLOT_SIZE; // grid + space + arrow + space + output
        int craftingSectionHeight = CRAFTING_GRID_SIZE * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING + SLOT_SIZE;
        
        int totalInventoryRows = MAIN_INVENTORY_ROWS + 1; // main rows + hotbar row
        int mainAndHotbarHeight = totalInventoryRows * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        
        int inventoryPanelWidth = Math.max(baseInventoryPanelWidth, craftingElementsTotalWidth + SLOT_PADDING * 2);
        int inventoryPanelHeight = mainAndHotbarHeight + TITLE_HEIGHT + craftingSectionHeight + SLOT_PADDING * 2;
        
        int panelStartX = (windowWidth - inventoryPanelWidth) / 2;
        int panelStartY = (windowHeight - inventoryPanelHeight) / 2;
        
        // Create depth curtain quad covering the inventory area
        int bufferPadding = 10;
        float left = panelStartX - bufferPadding;
        float right = panelStartX + inventoryPanelWidth + bufferPadding;
        float top = panelStartY - bufferPadding;
        float bottom = panelStartY + inventoryPanelHeight + bufferPadding;
        
        renderDepthQuad(left, right, top, bottom, 0.0f);
        
        // Restore GL state
        restoreGLState(blendWasEnabled);
        shaderProgram.unbind();
    }
    
    /**
     * Renders an invisible depth curtain to occlude block drops behind hotbar UI.
     * This creates proper depth values for block drop occlusion without visual interference.
     * Used when only the hotbar is visible (not the full inventory screen).
     */
    public void renderHotbarDepthCurtain() {
        // Save current GL state
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);
        
        // Set up depth curtain rendering state
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_ALWAYS);     // Always pass depth test (renders over world)
        glDepthMask(true);          // Write to depth buffer (this is key!)
        glDisable(GL_BLEND);        // No blending needed for invisible quad
        glColorMask(false, false, false, false); // Don't write to color buffer (invisible)
        
        // Set up orthographic projection for screen-space rendering
        Matrix4f orthoProjection = new Matrix4f().ortho(0, windowWidth, windowHeight, 0, -1, 1);
        Matrix4f identityView = new Matrix4f().identity();
        Matrix4f modelMatrix = new Matrix4f().identity();
        
        // Bind shader and set uniforms
        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", orthoProjection);
        shaderProgram.setUniform("viewMatrix", identityView);
        shaderProgram.setUniform("modelMatrix", modelMatrix);
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_color", new org.joml.Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
        
        // Calculate hotbar dimensions (match InventoryScreen.renderHotbar calculations)
        int hotbarWidth = HOTBAR_SIZE * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int hotbarHeight = SLOT_SIZE + SLOT_PADDING * 2;
        
        int hotbarStartX = (windowWidth - hotbarWidth) / 2;
        int hotbarStartY = windowHeight - SLOT_SIZE - HOTBAR_Y_OFFSET - SLOT_PADDING; // Background starts above slots
        
        // Create depth curtain quad covering the hotbar area
        float left = hotbarStartX;
        float right = hotbarStartX + hotbarWidth;
        float top = hotbarStartY;
        float bottom = hotbarStartY + hotbarHeight;
        
        renderDepthQuad(left, right, top, bottom, 0.0f);
        
        // Restore GL state
        restoreGLState(blendWasEnabled);
        shaderProgram.unbind();
    }
    
    /**
     * Renders an invisible depth curtain to occlude block drops behind pause menu UI.
     * This creates proper depth values for block drop occlusion without visual interference.
     */
    public void renderPauseMenuDepthCurtain() {
        // Save current GL state
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);
        
        // Set up depth curtain rendering state - CORRECTED for post-NanoVG rendering
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);       // Use normal depth testing (not ALWAYS)
        glDepthMask(true);          // Write to depth buffer (this is key!)
        glDisable(GL_BLEND);        // No blending needed for invisible quad
        glColorMask(false, false, false, false); // Don't write to color buffer (invisible)
        
        // CRITICAL FIX: Use SAME 3D projection/view as block drops for coordinate alignment
        Player player = Game.getPlayer();
        Matrix4f worldProjection = this.projectionMatrix; // Same as block drops
        Matrix4f worldView = (player != null) ? player.getViewMatrix() : new Matrix4f();
        
        // Bind shader and set uniforms - NOW IN WORLD COORDINATES
        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", worldProjection);
        shaderProgram.setUniform("viewMatrix", worldView);
        shaderProgram.setUniform("modelMatrix", new Matrix4f().identity());
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_color", new org.joml.Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
        
        // Create MASSIVE 3D wall in front of camera to block all block drops
        Player currentPlayer = Game.getPlayer();
        if (currentPlayer == null) return;
        
        org.joml.Vector3f forward = new org.joml.Vector3f();
        currentPlayer.getViewMatrix().positiveZ(forward).negate(); // Get forward direction
        
        // DISTANCE-ADAPTIVE SOLUTION: Extract near plane for precision-independent positioning
        float nearPlane = extractNearPlaneFromProjection(this.projectionMatrix);
        
        // Position depth curtain at near plane + tiny epsilon for maximum precision
        float epsilon = nearPlane * 0.001f; // 0.1% of near plane
        float[] wallDistances = {
            nearPlane + epsilon,           // Primary layer at near plane
            nearPlane + epsilon * 2,      // Secondary layers for robustness  
            nearPlane + epsilon * 3,
            nearPlane + epsilon * 4
        };
        
        // Calculate pause menu panel bounds in screen space
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;
        int panelWidth = 520;  // From pause menu documentation
        int panelHeight = 450; // From pause menu documentation
        
        // Convert panel bounds to normalized device coordinates [-1, +1]
        float panelLeft = ((centerX - panelWidth/2.0f) / windowWidth) * 2.0f - 1.0f;
        float panelRight = ((centerX + panelWidth/2.0f) / windowWidth) * 2.0f - 1.0f;
        float panelTop = (1.0f - (centerY - panelHeight/2.0f) / windowHeight) * 2.0f - 1.0f;
        float panelBottom = (1.0f - (centerY + panelHeight/2.0f) / windowHeight) * 2.0f - 1.0f;
        
        // Define PANEL-ONLY bounds in normalized device coordinates
        float[] screenCorners = {
            panelLeft,  panelTop,     // Top-left NDC (panel only)
            panelRight, panelTop,     // Top-right NDC (panel only)
            panelRight, panelBottom,  // Bottom-right NDC (panel only)
            panelLeft,  panelBottom   // Bottom-left NDC (panel only)
        };
        
        // Render multiple depth layers for robust coverage
        for (float wallDistance : wallDistances) {
            // Unproject screen corners to world space at this distance
            org.joml.Vector3f[] worldCorners = new org.joml.Vector3f[4];
            
            for (int i = 0; i < 4; i++) {
                float ndcX = screenCorners[i * 2];
                float ndcY = screenCorners[i * 2 + 1];
                
                // Unproject NDC coordinates to world space
                worldCorners[i] = unprojectToWorldSpace(ndcX, ndcY, wallDistance, 
                    this.projectionMatrix, currentPlayer.getViewMatrix());
            }
            
            // Create vertices using unprojected world coordinates
            float[] vertices = {
                // Top-left
                worldCorners[0].x, worldCorners[0].y, worldCorners[0].z,
                // Top-right  
                worldCorners[1].x, worldCorners[1].y, worldCorners[1].z,
                // Bottom-right
                worldCorners[2].x, worldCorners[2].y, worldCorners[2].z,
                // Bottom-left
                worldCorners[3].x, worldCorners[3].y, worldCorners[3].z
            };
            
            renderWorldSpaceDepthQuad(vertices);
        }
        
        // Restore GL state for normal 3D rendering  
        glColorMask(true, true, true, true);  // Re-enable color writing
        glDepthFunc(GL_LESS);                 // Restore normal 3D depth function
        glDepthMask(true);                    // Restore normal depth writing
        
        if (blendWasEnabled) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
        
        // Unbind shader
        shaderProgram.unbind();
    }
    
    /**
     * Renders an invisible depth curtain to occlude block drops behind recipe book UI.
     * This creates proper depth values for block drop occlusion without visual interference.
     */
    public void renderRecipeBookDepthCurtain() {
        // Save current GL state
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);
        
        // Aggressive OpenGL state setup for bulletproof depth writing
        glDisable(GL_BLEND);        // Absolutely no blending
        glEnable(GL_DEPTH_TEST);    // Force enable depth testing
        glDepthFunc(GL_ALWAYS);     // ALWAYS pass depth test (renders over everything)
        glDepthMask(true);          // FORCE write to depth buffer
        glColorMask(false, false, false, false); // Invisible (no color writing)
        glDisable(GL_CULL_FACE);    // Disable culling to ensure all faces render
        
        // Set up orthographic projection for screen-space rendering
        Matrix4f orthoProjection = new Matrix4f().ortho(0, windowWidth, windowHeight, 0, -1, 1);
        Matrix4f identityView = new Matrix4f().identity();
        Matrix4f modelMatrix = new Matrix4f().identity();
        
        // Bind shader and set uniforms
        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", orthoProjection);
        shaderProgram.setUniform("viewMatrix", identityView);
        shaderProgram.setUniform("modelMatrix", modelMatrix);
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_color", new org.joml.Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
        
        // Aggressive full-coverage depth curtain to eliminate all block drop leakage
        int ultraBuffer = 50; // Massive buffer to handle any edge cases
        float left = -ultraBuffer;
        float right = windowWidth + ultraBuffer;
        float top = -ultraBuffer;
        float bottom = windowHeight + ultraBuffer;
        
        // Use multiple depth values to create thick depth barrier
        float[] depthLayers = {-0.999f, -0.99f, -0.9f, 0.0f, 0.1f}; // Multiple near-plane depths
        
        // Render multiple depth layers for bulletproof coverage
        for (float depthValue : depthLayers) {
            renderDepthQuad(left, right, top, bottom, depthValue);
        }
        
        // Restore GL state for normal 3D rendering
        glColorMask(true, true, true, true);  // Re-enable color writing
        glDepthFunc(GL_LESS);                 // Restore normal 3D depth function
        glDepthMask(true);                    // Restore normal depth writing
        glEnable(GL_CULL_FACE);               // Re-enable face culling
        
        if (blendWasEnabled) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
        
        shaderProgram.unbind();
    }
    
    /**
     * Renders an invisible depth curtain to occlude block drops behind workbench UI.
     * This creates proper depth values for block drop occlusion without visual interference.
     */
    public void renderWorkbenchDepthCurtain() {
        // Save current GL state
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);
        
        // Set up depth curtain rendering state
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_ALWAYS);     // Always pass depth test (renders over world)
        glDepthMask(true);          // Write to depth buffer (this is key!)
        glDisable(GL_BLEND);        // No blending needed for invisible quad
        glColorMask(false, false, false, false); // Don't write to color buffer (invisible)
        
        // Set up orthographic projection for screen-space rendering
        Matrix4f orthoProjection = new Matrix4f().ortho(0, windowWidth, windowHeight, 0, -1, 1);
        Matrix4f identityView = new Matrix4f().identity();
        Matrix4f modelMatrix = new Matrix4f().identity();
        
        // Bind shader and set uniforms
        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", orthoProjection);
        shaderProgram.setUniform("viewMatrix", identityView);
        shaderProgram.setUniform("modelMatrix", modelMatrix);
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_color", new org.joml.Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
        
        // Workbench dimensions are calculated dynamically based on content
        // Use a conservative full-screen approach with some padding since workbench size varies
        int bufferPadding = 50; // Larger buffer for dynamic sizing
        float left = bufferPadding;
        float right = windowWidth - bufferPadding;
        float top = bufferPadding;
        float bottom = windowHeight - bufferPadding;
        
        renderDepthQuad(left, right, top, bottom, 0.0f);
        
        // Restore GL state
        restoreGLState(blendWasEnabled);
        shaderProgram.unbind();
    }
    
    /**
     * Helper method to render a depth quad in screen space coordinates.
     */
    private void renderDepthQuad(float left, float right, float top, float bottom, float depth) {
        float[] vertices = {
            left,  top,    depth,  // Top-left
            right, top,    depth,  // Top-right
            right, bottom, depth,  // Bottom-right
            left,  bottom, depth   // Bottom-left
        };
        
        int[] indices = {
            0, 1, 2,  // First triangle
            2, 3, 0   // Second triangle
        };
        
        // Create temporary VAO for the depth curtain
        int vao = GL30.glGenVertexArrays();
        int vbo = GL20.glGenBuffers();
        int ebo = GL20.glGenBuffers();
        
        GL30.glBindVertexArray(vao);
        
        // Upload vertex data
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, vbo);
        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();
        GL20.glBufferData(GL20.GL_ARRAY_BUFFER, vertexBuffer, GL20.GL_STATIC_DRAW);
        
        // Upload index data
        GL20.glBindBuffer(GL20.GL_ELEMENT_ARRAY_BUFFER, ebo);
        IntBuffer indexBuffer = BufferUtils.createIntBuffer(indices.length);
        indexBuffer.put(indices).flip();
        GL20.glBufferData(GL20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL20.GL_STATIC_DRAW);
        
        // Set up vertex attributes (position only)
        GL20.glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);
        
        // Render the invisible depth curtain
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
        
        // Clean up temporary resources
        GL30.glBindVertexArray(0);
        GL20.glDeleteBuffers(vbo);
        GL20.glDeleteBuffers(ebo);
        GL30.glDeleteVertexArrays(vao);
    }
    
    /**
     * Helper method to render a depth quad in world space coordinates.
     */
    private void renderWorldSpaceDepthQuad(float[] vertices) {
        int[] indices = {
            0, 1, 2,  // First triangle
            2, 3, 0   // Second triangle
        };
        
        // Create temporary VAO for this depth layer
        int vao = GL30.glGenVertexArrays();
        int vbo = GL20.glGenBuffers();
        int ebo = GL20.glGenBuffers();
        
        GL30.glBindVertexArray(vao);
        
        // Upload vertex data
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, vbo);
        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();
        GL20.glBufferData(GL20.GL_ARRAY_BUFFER, vertexBuffer, GL20.GL_STATIC_DRAW);
        
        // Upload index data
        GL20.glBindBuffer(GL20.GL_ELEMENT_ARRAY_BUFFER, ebo);
        IntBuffer indexBuffer = BufferUtils.createIntBuffer(indices.length);
        indexBuffer.put(indices).flip();
        GL20.glBufferData(GL20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL20.GL_STATIC_DRAW);
        
        // Set up vertex attributes (position only)
        GL20.glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);
        
        // Render this depth layer
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
        
        // Clean up temporary resources for this layer
        GL30.glBindVertexArray(0);
        GL20.glDeleteBuffers(vbo);
        GL20.glDeleteBuffers(ebo);
        GL30.glDeleteVertexArrays(vao);
    }
    
    /**
     * Restores OpenGL state after depth curtain rendering.
     */
    private void restoreGLState(boolean blendWasEnabled) {
        glColorMask(true, true, true, true);  // Re-enable color writing
        glDepthFunc(GL_ALWAYS);               // Keep UI depth function
        glDepthMask(false);                   // Restore UI depth mask (don't write)
        
        if (blendWasEnabled) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
    }
    
    /**
     * Extracts the near plane distance from a perspective projection matrix.
     * This is crucial for distance-independent depth curtain positioning.
     */
    private float extractNearPlaneFromProjection(Matrix4f projectionMatrix) {
        try {
            // Get matrix elements - JOML uses column-major order
            float m22 = projectionMatrix.m22(); // (2,2) element
            float m32 = projectionMatrix.m32(); // (3,2) element
            
            // For perspective projection: near = -m32 / (m22 + 1)
            // Handle edge cases and ensure positive result
            if (Math.abs(m22 + 1) > 0.0001f) {
                float near = -m32 / (m22 + 1);
                return Math.max(near, 0.01f); // Ensure minimum reasonable near plane
            }
        } catch (Exception e) {
            System.err.println("Failed to extract near plane from projection matrix: " + e.getMessage());
        }
        
        // Fallback to reasonable default
        return 0.1f;
    }
    
    /**
     * Unprojects normalized device coordinates to world space at a given distance.
     */
    private org.joml.Vector3f unprojectToWorldSpace(float ndcX, float ndcY, float distance, 
                                                   Matrix4f projectionMatrix, Matrix4f viewMatrix) {
        // Create inverse matrices
        Matrix4f invProjection = new Matrix4f(projectionMatrix).invert();
        Matrix4f invView = new Matrix4f(viewMatrix).invert();
        
        // Convert NDC to clip space (add z and w)
        org.joml.Vector4f clipSpace = new org.joml.Vector4f(ndcX, ndcY, -1.0f, 1.0f);
        
        // Transform to view space
        org.joml.Vector4f viewSpace = invProjection.transform(clipSpace);
        viewSpace.div(viewSpace.w); // Perspective divide
        
        // Set the desired distance in view space
        viewSpace.z = -distance; // Negative because view space Z points backward
        
        // Transform to world space
        org.joml.Vector4f worldSpace = invView.transform(viewSpace);
        
        return new org.joml.Vector3f(worldSpace.x, worldSpace.y, worldSpace.z);
    }
}