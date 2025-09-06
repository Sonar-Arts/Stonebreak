package com.stonebreak.rendering.models.blocks;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemType;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.shaders.ShaderProgram;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager;
import com.stonebreak.rendering.core.API.commonBlockResources.models.BlockDefinitionRegistry;
import com.stonebreak.world.World;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Specialized renderer for block-related rendering operations.
 * Handles block crack overlays, block drops, and block breaking animations.
 */
public class BlockRenderer {
    private int crackTextureId;
    private int blockOverlayVao;
    private CBRResourceManager cbrManager;
    
    
    public BlockRenderer() {
        initialize();
    }
    
    public BlockRenderer(TextureAtlas textureAtlas, BlockDefinitionRegistry blockRegistry) {
        this.cbrManager = CBRResourceManager.getInstance(textureAtlas, blockRegistry);
        initialize();
    }
    
    private void initialize() {
        createCrackTexture();
        createBlockOverlayVao();
    }
    
    
    /**
     * Renders block crack overlay on the block being broken by the player.
     */
    public void renderBlockCrackOverlay(Player player, ShaderProgram shaderProgram, Matrix4f projectionMatrix) {
        Vector3i breakingBlock = player.getBreakingBlock();
        if (breakingBlock == null) {
            return; // No block being broken
        }
        
        float progress = player.getBreakingProgress();
        if (progress <= 0.0f) {
            return; // No progress yet
        }
        
        // Calculate crack stage (0-9) with smoother transitions
        float exactStage = Math.min(9.0f, progress * 10.0f);
        int crackStage = (int) exactStage;
        
        // Add small offset to prevent flickering between stages
        if (exactStage > crackStage + 0.1f) {
            crackStage = Math.min(9, crackStage + 1);
        }
        
        // Enable blending for crack overlay
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        
        // Disable depth writing but keep depth testing to avoid z-fighting
        glDepthMask(false);
        glEnable(GL_DEPTH_TEST);
        
        // Enable polygon offset to prevent z-fighting
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(-1.0f, -1.0f);
        
        // Use shader program
        shaderProgram.bind();
        
        // Set uniforms for overlay rendering
        shaderProgram.setUniform("projectionMatrix", projectionMatrix);
        shaderProgram.setUniform("viewMatrix", player.getViewMatrix());
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_transformUVsForItem", false);
        shaderProgram.setUniform("texture_sampler", 0);
        
        // Bind crack texture
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, crackTextureId);
        
        // Set up texture coordinates for the specific crack stage
        float vOffset = crackStage / 10.0f; // V offset for the crack stage
        float vScale = 1.0f / 10.0f; // V scale for one crack stage
        
        // Apply texture coordinate transformation for crack stage
        shaderProgram.setUniform("u_transformUVsForItem", true);
        shaderProgram.setUniform("u_atlasUVOffset", new Vector2f(0.0f, vOffset));
        shaderProgram.setUniform("u_atlasUVScale", new Vector2f(1.0f, vScale));
        
        // Create model matrix to position the overlay at the breaking block
        Matrix4f modelMatrix = new Matrix4f()
            .translate(breakingBlock.x, breakingBlock.y, breakingBlock.z)
            .scale(1.002f); // Slightly larger to avoid z-fighting
        
        // Combine view and model matrices
        Matrix4f modelViewMatrix = new Matrix4f(player.getViewMatrix()).mul(modelMatrix);
        shaderProgram.setUniform("viewMatrix", modelViewMatrix);
        
        // Set color with some transparency
        shaderProgram.setUniform("u_color", new Vector4f(1.0f, 1.0f, 1.0f, 0.8f));
        
        // Render the block overlay
        GL30.glBindVertexArray(blockOverlayVao);
        glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0);
        GL30.glBindVertexArray(0);
        
        // Restore state
        glDepthMask(true);
        glDisable(GL_BLEND);
        glDisable(GL_POLYGON_OFFSET_FILL);
        glBindTexture(GL_TEXTURE_2D, 0);
        
        // Reset shader state
        shaderProgram.setUniform("u_transformUVsForItem", false);
        
        shaderProgram.unbind();
    }
    
    /**
     * Renders a block using CBR BlockRenderResource with proper shader configuration.
     * This method sets up texture coordinates and renders the block mesh.
     */
    public void renderBlock(CBRResourceManager.BlockRenderResource resource, ShaderProgram shaderProgram, Matrix4f modelMatrix) {
        // Get texture coordinates from the resource
        float[] texCoords = resource.getTextureCoords().toArray();
        
        // Set texture coordinate transformation in shader
        shaderProgram.setUniform("u_transformUVsForItem", true);
        shaderProgram.setUniform("u_atlasUVOffset", new Vector2f(texCoords[0], texCoords[1])); // u1, v1
        shaderProgram.setUniform("u_atlasUVScale", new Vector2f(texCoords[2] - texCoords[0], texCoords[3] - texCoords[1])); // width, height
        
        // Set model matrix
        shaderProgram.setUniform("modelMatrix", modelMatrix);
        
        // Bind mesh and render
        resource.getMesh().bind();
        glDrawElements(GL_TRIANGLES, resource.getMesh().getIndexCount(), GL_UNSIGNED_INT, 0);
        
        // Reset shader state
        shaderProgram.setUniform("u_transformUVsForItem", false);
    }
    
    /**
     * Convenience method for rendering a block type directly.
     */
    public void renderBlockType(BlockType type, ShaderProgram shaderProgram, Matrix4f modelMatrix) {
        if (cbrManager == null) {
            throw new IllegalStateException("BlockRenderer not initialized with CBRResourceManager.");
        }
        CBRResourceManager.BlockRenderResource resource = cbrManager.getBlockTypeResource(type);
        renderBlock(resource, shaderProgram, modelMatrix);
    }
    
    /**
     * Gets the CBRResourceManager instance if available.
     * 
     * @return The CBRResourceManager instance, or null if not initialized
     */
    public CBRResourceManager getCBRResourceManager() {
        return cbrManager;
    }
    
    /**
     * Checks if this BlockRenderer has been initialized with CBRResourceManager.
     * 
     * @return true if CBR is available, false otherwise
     */
    public boolean hasCBRSupport() {
        return cbrManager != null;
    }
    

    
    /**
     * Creates the crack texture with multiple crack stages.
     */
    private void createCrackTexture() {
        int texWidth = 16;
        int texHeight = 16 * 10; // 10 crack stages vertically
        ByteBuffer buffer = BufferUtils.createByteBuffer(texWidth * texHeight * 4); // RGBA
        
        for (int stage = 0; stage < 10; stage++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    byte r = 0, g = 0, b = 0, a = 0;
                    
                    // Create realistic crack pattern based on stage and position
                    float intensity = (stage + 1) / 10.0f;
                    
                    // Create organic-looking cracks using multiple crack paths
                    boolean isCrack = generateCrackPattern(x, y, stage);
                    
                    if (isCrack) {
                        r = g = b = 0; // Black cracks
                        a = (byte) (intensity * 200); // More visible as stage increases
                    }
                    
                    buffer.put(r);
                    buffer.put(g);
                    buffer.put(b);
                    buffer.put(a);
                }
            }
        }
        buffer.flip();
        
        crackTextureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, crackTextureId);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, texWidth, texHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        glBindTexture(GL_TEXTURE_2D, 0);
    }
    
    /**
     * Generates realistic crack patterns for block breaking animation.
     * Creates organic-looking cracks that branch and spread as breaking progresses.
     */
    private boolean generateCrackPattern(int x, int y, int stage) {
        if (stage == 0) return false;
        
        // Define crack centers and paths for organic crack generation
        int centerX = 8, centerY = 8;
        
        // Stage 1-2: Initial small cracks from center
        if (stage >= 1) {
            // Main crack lines from center
            if (isOnCrackLine(x, y, centerX, centerY, centerX + 3, centerY - 2, 1)) return true;
            if (isOnCrackLine(x, y, centerX, centerY, centerX - 2, centerY + 3, 1)) return true;
        }
        
        // Stage 3-4: Extend cracks and add branches
        if (stage >= 3) {
            if (isOnCrackLine(x, y, centerX + 3, centerY - 2, centerX + 6, centerY - 4, 1)) return true;
            if (isOnCrackLine(x, y, centerX - 2, centerY + 3, centerX - 4, centerY + 6, 1)) return true;
            // Add branching cracks
            if (isOnCrackLine(x, y, centerX + 1, centerY - 1, centerX + 4, centerY + 2, 1)) return true;
            if (isOnCrackLine(x, y, centerX - 1, centerY + 1, centerX - 3, centerY - 2, 1)) return true;
        }
        
        // Stage 5-6: More extensive cracking
        if (stage >= 5) {
            if (isOnCrackLine(x, y, centerX + 6, centerY - 4, centerX + 8, centerY - 6, 1)) return true;
            if (isOnCrackLine(x, y, centerX - 4, centerY + 6, centerX - 6, centerY + 8, 1)) return true;
            if (isOnCrackLine(x, y, centerX + 4, centerY + 2, centerX + 7, centerY + 5, 1)) return true;
            if (isOnCrackLine(x, y, centerX - 3, centerY - 2, centerX - 6, centerY - 5, 1)) return true;
            // Add perpendicular branches
            if (isOnCrackLine(x, y, centerX + 2, centerY, centerX + 2, centerY - 4, 1)) return true;
            if (isOnCrackLine(x, y, centerX, centerY + 2, centerX - 4, centerY + 2, 1)) return true;
        }
        
        // Stage 7-8: Heavy cracking with web pattern
        if (stage >= 7) {
            if (isOnCrackLine(x, y, centerX, centerY - 3, centerX + 5, centerY - 6, 1)) return true;
            if (isOnCrackLine(x, y, centerX - 3, centerY, centerX - 6, centerY + 5, 1)) return true;
            if (isOnCrackLine(x, y, centerX + 5, centerY, centerX + 8, centerY - 3, 1)) return true;
            if (isOnCrackLine(x, y, centerX, centerY + 5, centerX - 3, centerY + 8, 1)) return true;
            // Add connecting cracks between main lines
            if (isOnCrackLine(x, y, centerX + 3, centerY - 1, centerX + 1, centerY + 3, 1)) return true;
            if (isOnCrackLine(x, y, centerX - 1, centerY - 3, centerX - 3, centerY + 1, 1)) return true;
        }
        
        // Stage 9: Maximum cracking with detailed fractures
        if (stage >= 9) {
            // Add fine detail cracks
            if (isOnCrackLine(x, y, centerX + 1, centerY + 1, centerX + 6, centerY + 3, 1)) return true;
            if (isOnCrackLine(x, y, centerX - 1, centerY - 1, centerX - 6, centerY - 3, 1)) return true;
            if (isOnCrackLine(x, y, centerX + 4, centerY - 1, centerX + 7, centerY + 2, 1)) return true;
            if (isOnCrackLine(x, y, centerX - 4, centerY + 1, centerX - 7, centerY - 2, 1)) return true;
            // Add edge cracks
            if (x == 0 || x == 15 || y == 0 || y == 15) {
                if ((x + y) % 3 == 0) return true;
            }
            // Add small fracture details with consistent pattern
            if ((x * 7 + y * 3) % 11 == 0 && distanceFromCenter(x, y, centerX, centerY) < 6) return true;
            // Add additional consistent detail cracks
            if ((x * 3 + y * 5) % 13 == 0 && distanceFromCenter(x, y, centerX, centerY) < 5) return true;
        }
        
        return false;
    }
    
    /**
     * Checks if a point is on a crack line with some thickness for natural appearance.
     */
    private boolean isOnCrackLine(int x, int y, int x1, int y1, int x2, int y2, int thickness) {
        // Calculate distance from point to line segment
        float A = x - x1;
        float B = y - y1;
        float C = x2 - x1;
        float D = y2 - y1;
        
        float dot = A * C + B * D;
        float lenSq = C * C + D * D;
        
        if (lenSq == 0) {
            // Line has no length, check distance to point
            return Math.abs(x - x1) <= thickness && Math.abs(y - y1) <= thickness;
        }
        
        float param = dot / lenSq;
        
        float xx, yy;
        if (param < 0) {
            xx = x1;
            yy = y1;
        } else if (param > 1) {
            xx = x2;
            yy = y2;
        } else {
            xx = x1 + param * C;
            yy = y1 + param * D;
        }
        
        float dx = x - xx;
        float dy = y - yy;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        
        return distance <= thickness;
    }
    
    /**
     * Calculates distance from a point to the center.
     */
    private float distanceFromCenter(int x, int y, int centerX, int centerY) {
        float dx = x - centerX;
        float dy = y - centerY;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
    
    /**
     * Creates VAO for rendering block overlay (crack) on top of blocks.
     */
    private void createBlockOverlayVao() {
        // Create a unit cube for overlaying on blocks
        float[] vertices = {
            // Front face
            0.0f, 0.0f, 1.0f,  0.0f, 0.0f,
            1.0f, 0.0f, 1.0f,  1.0f, 0.0f,
            1.0f, 1.0f, 1.0f,  1.0f, 1.0f,
            0.0f, 1.0f, 1.0f,  0.0f, 1.0f,
            // Back face
            1.0f, 0.0f, 0.0f,  0.0f, 0.0f,
            0.0f, 0.0f, 0.0f,  1.0f, 0.0f,
            0.0f, 1.0f, 0.0f,  1.0f, 1.0f,
            1.0f, 1.0f, 0.0f,  0.0f, 1.0f,
            // Top face
            0.0f, 1.0f, 0.0f,  0.0f, 0.0f,
            1.0f, 1.0f, 0.0f,  1.0f, 0.0f,
            1.0f, 1.0f, 1.0f,  1.0f, 1.0f,
            0.0f, 1.0f, 1.0f,  0.0f, 1.0f,
            // Bottom face
            0.0f, 0.0f, 1.0f,  0.0f, 0.0f,
            1.0f, 0.0f, 1.0f,  1.0f, 0.0f,
            1.0f, 0.0f, 0.0f,  1.0f, 1.0f,
            0.0f, 0.0f, 0.0f,  0.0f, 1.0f,
            // Right face
            1.0f, 0.0f, 1.0f,  0.0f, 0.0f,
            1.0f, 0.0f, 0.0f,  1.0f, 0.0f,
            1.0f, 1.0f, 0.0f,  1.0f, 1.0f,
            1.0f, 1.0f, 1.0f,  0.0f, 1.0f,
            // Left face
            0.0f, 0.0f, 0.0f,  0.0f, 0.0f,
            0.0f, 0.0f, 1.0f,  1.0f, 0.0f,
            0.0f, 1.0f, 1.0f,  1.0f, 1.0f,
            0.0f, 1.0f, 0.0f,  0.0f, 1.0f
        };
        
        int[] indices = {
            0, 1, 2, 2, 3, 0,       // Front
            4, 5, 6, 6, 7, 4,       // Back
            8, 9, 10, 10, 11, 8,    // Top
            12, 13, 14, 14, 15, 12, // Bottom
            16, 17, 18, 18, 19, 16, // Right
            20, 21, 22, 22, 23, 20  // Left
        };
        
        blockOverlayVao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(blockOverlayVao);
        
        int vbo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, vbo);
        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();
        GL20.glBufferData(GL20.GL_ARRAY_BUFFER, vertexBuffer, GL20.GL_STATIC_DRAW);
        
        // Position attribute (location 0)
        GL20.glVertexAttribPointer(0, 3, GL20.GL_FLOAT, false, 5 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);
        // Texture coordinate attribute (location 1)
        GL20.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);
        
        int ibo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo);
        IntBuffer indexBuffer = BufferUtils.createIntBuffer(indices.length);
        indexBuffer.put(indices).flip();
        GL20.glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL20.GL_STATIC_DRAW);
        
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }
    
    /**
     * Gets a block render resource using CBR system with proper face texturing.
     * This ensures proper face texturing for blocks with different textures per face.
     */
    public CBRResourceManager.BlockRenderResource getBlockRenderResource(BlockType type) {
        if (cbrManager == null) {
            throw new IllegalStateException("BlockRenderer not initialized with CBRResourceManager. Use constructor with TextureAtlas and BlockDefinitionRegistry.");
        }
        return cbrManager.getBlockTypeResource(type);
    }
    

    
    /**
     * Gets a flower cross render resource using CBR system.
     * Creates two intersecting quads that form a cross pattern.
     */
    public CBRResourceManager.BlockRenderResource getFlowerCrossResource(BlockType type) {
        if (cbrManager == null) {
            throw new IllegalStateException("BlockRenderer not initialized with CBRResourceManager. Use constructor with TextureAtlas and BlockDefinitionRegistry.");
        }
        return cbrManager.getBlockTypeResource(type);
    }
    

    
    /**
     * Cleanup method to release resources.
     */
    public void cleanup() {
        // Delete crack texture
        if (crackTextureId != 0) {
            glDeleteTextures(crackTextureId);
            crackTextureId = 0;
        }
        
        // Delete block overlay VAO
        if (blockOverlayVao != 0) {
            GL30.glDeleteVertexArrays(blockOverlayVao);
            blockOverlayVao = 0;
        }
        
        // Clean up CBRResourceManager if we own it
        if (cbrManager != null) {
            try {
                cbrManager.close();
            } catch (Exception e) {
                System.err.println("Error cleaning up CBRResourceManager: " + e.getMessage());
            }
            cbrManager = null;
        }
    }
}