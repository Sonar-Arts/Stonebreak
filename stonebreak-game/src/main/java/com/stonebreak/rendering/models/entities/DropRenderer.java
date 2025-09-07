package com.stonebreak.rendering.models.entities;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.rendering.models.blocks.BlockRenderer;
import com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager;
import com.stonebreak.rendering.shaders.ShaderProgram;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.world.World;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;

/**
 * Specialized renderer for 3D block and item drops in the world.
 * Uses the CBR API and BlockRenderer for block drops, and creates 3D sprite representations for items.
 */
public class DropRenderer {
    
    private final BlockRenderer blockRenderer;
    private final TextureAtlas textureAtlas;
    private CBRResourceManager cbrManager;
    
    // VAO for item sprite rendering (3D representation)
    private int itemSpriteVao;
    private boolean initialized = false;
    
    // Reusable matrices to avoid allocations
    private final Matrix4f dropModelMatrix = new Matrix4f();
    private final Matrix4f rotationMatrix = new Matrix4f();
    
    /**
     * Creates a DropRenderer with the required dependencies.
     */
    public DropRenderer(BlockRenderer blockRenderer, TextureAtlas textureAtlas) {
        this.blockRenderer = blockRenderer;
        this.textureAtlas = textureAtlas;
        this.cbrManager = blockRenderer.getCBRResourceManager();
        initialize();
    }
    
    /**
     * Initialize the drop renderer by creating necessary resources.
     */
    private void initialize() {
        if (initialized) return;
        
        createItemSpriteVao();
        initialized = true;
    }
    
    /**
     * Renders all drops in the world. This method should be called before UI rendering
     * to ensure drops render underneath the UI.
     */
    public void renderDrops(List<Entity> drops, ShaderProgram shaderProgram, Matrix4f projectionMatrix, Matrix4f viewMatrix) {
        if (drops == null || drops.isEmpty()) {
            return;
        }
        
        // Ensure shader is bound
        shaderProgram.bind();
        
        // Set common uniforms
        shaderProgram.setUniform("projectionMatrix", projectionMatrix);
        shaderProgram.setUniform("texture_sampler", 0);
        shaderProgram.setUniform("u_isText", false);
        
        // Bind texture atlas
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureAtlas.getTextureId());
        
        // Enable depth testing and blending for proper rendering
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        
        // Render each drop
        for (Entity drop : drops) {
            if (drop.isAlive() && isDropEntity(drop)) {
                renderDrop(drop, shaderProgram, viewMatrix);
            }
        }
        
        // Clean up state
        glDisable(GL_BLEND);
        GL30.glBindVertexArray(0);
        shaderProgram.setUniform("u_transformUVsForItem", false);
    }
    
    /**
     * Renders a single drop entity.
     */
    private void renderDrop(Entity drop, ShaderProgram shaderProgram, Matrix4f viewMatrix) {
        // Create model matrix for the drop
        Vector3f dropPos = drop.getPosition();
        float dropAge = drop.getAge();
        
        // Apply bobbing animation and rotation
        float bobOffset = (float) Math.sin(dropAge * 2.0f) * 0.1f;
        float rotationY = dropAge * 50.0f; // Rotate 50 degrees per second
        
        dropModelMatrix.identity()
            .translate(dropPos.x, dropPos.y + bobOffset, dropPos.z)
            .scale(0.25f); // Make drops smaller than full blocks
        
        // Apply rotation for spinning effect
        rotationMatrix.identity().rotateY((float) Math.toRadians(rotationY));
        dropModelMatrix.mul(rotationMatrix);
        
        // Combine with view matrix
        Matrix4f modelViewMatrix = new Matrix4f(viewMatrix).mul(dropModelMatrix);
        shaderProgram.setUniform("viewMatrix", modelViewMatrix);
        
        // Render based on drop type
        if (isBlockDrop(drop)) {
            renderBlockDrop(drop, shaderProgram);
        } else if (isItemDrop(drop)) {
            renderItemDrop(drop, shaderProgram);
        }
    }
    
    /**
     * Renders a block drop using the CBR API and BlockRenderer.
     */
    private void renderBlockDrop(Entity drop, ShaderProgram shaderProgram) {
        BlockType blockType = getBlockTypeFromDrop(drop);
        if (blockType == null || blockType == BlockType.AIR) {
            return;
        }
        
        // Use CBR API to get block render resource
        if (cbrManager != null) {
            try {
                CBRResourceManager.BlockRenderResource resource = cbrManager.getBlockTypeResource(blockType);
                
                // Set shader uniforms for block rendering
                shaderProgram.setUniform("u_useSolidColor", false);
                shaderProgram.setUniform("u_transformUVsForItem", true);
                
                // Get texture coordinates
                float[] texCoords = resource.getTextureCoords().toArray();
                shaderProgram.setUniform("u_atlasUVOffset", new Vector2f(texCoords[0], texCoords[1]));
                shaderProgram.setUniform("u_atlasUVScale", new Vector2f(texCoords[2] - texCoords[0], texCoords[3] - texCoords[1]));
                
                // Set color with slight transparency for visual appeal
                shaderProgram.setUniform("u_color", new Vector4f(1.0f, 1.0f, 1.0f, 0.95f));
                
                // Render the block mesh
                resource.getMesh().bind();
                glDrawElements(GL_TRIANGLES, resource.getMesh().getIndexCount(), GL_UNSIGNED_INT, 0);
                
            } catch (Exception e) {
                System.err.println("Error rendering block drop " + blockType + ": " + e.getMessage());
                // Fall back to simple cube if CBR fails
                renderFallbackCube(shaderProgram, blockType);
            }
        } else {
            // Fallback if CBR is not available
            renderFallbackCube(shaderProgram, blockType);
        }
    }
    
    /**
     * Renders an item drop as a 3D sprite representation.
     */
    private void renderItemDrop(Entity drop, ShaderProgram shaderProgram) {
        ItemType itemType = getItemTypeFromDrop(drop);
        if (itemType == null) {
            return;
        }
        
        // Set shader uniforms for item sprite rendering
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_transformUVsForItem", true);
        
        // Get texture coordinates from item type
        float atlasU = itemType.getAtlasX() / 16.0f;
        float atlasV = itemType.getAtlasY() / 16.0f;
        float atlasW = 1.0f / 16.0f;
        float atlasH = 1.0f / 16.0f;
        
        shaderProgram.setUniform("u_atlasUVOffset", new Vector2f(atlasU, atlasV));
        shaderProgram.setUniform("u_atlasUVScale", new Vector2f(atlasW, atlasH));
        
        // Set color with slight transparency
        shaderProgram.setUniform("u_color", new Vector4f(1.0f, 1.0f, 1.0f, 0.95f));
        
        // Render the item sprite as a 3D quad (similar to hand item rendering)
        GL30.glBindVertexArray(itemSpriteVao);
        glDrawElements(GL_TRIANGLES, 12, GL_UNSIGNED_INT, 0); // 2 triangles per quad, 2 quads (cross pattern)
    }
    
    /**
     * Renders a fallback cube when CBR is not available.
     */
    private void renderFallbackCube(ShaderProgram shaderProgram, BlockType blockType) {
        // Set basic shader uniforms
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_transformUVsForItem", true);
        
        // Use block's atlas coordinates
        float atlasU = blockType.getAtlasX() / 16.0f;
        float atlasV = blockType.getAtlasY() / 16.0f;
        float atlasW = 1.0f / 16.0f;
        float atlasH = 1.0f / 16.0f;
        
        shaderProgram.setUniform("u_atlasUVOffset", new Vector2f(atlasU, atlasV));
        shaderProgram.setUniform("u_atlasUVScale", new Vector2f(atlasW, atlasH));
        shaderProgram.setUniform("u_color", new Vector4f(1.0f, 1.0f, 1.0f, 0.95f));
        
        // Use the item sprite VAO as a simple cube substitute
        GL30.glBindVertexArray(itemSpriteVao);
        glDrawElements(GL_TRIANGLES, 12, GL_UNSIGNED_INT, 0);
    }
    
    /**
     * Creates VAO for rendering item sprites as 3D cross-pattern quads.
     * Similar to how flowers are rendered but optimized for items.
     */
    private void createItemSpriteVao() {
        // Create two intersecting quads forming a cross pattern
        // This gives a 3D appearance to flat item sprites
        float[] vertices = {
            // First quad (X-Z plane rotated 45 degrees)
            -0.5f, -0.5f,  0.0f,  0.0f, 1.0f, // Bottom-left
             0.5f, -0.5f,  0.0f,  1.0f, 1.0f, // Bottom-right
             0.5f,  0.5f,  0.0f,  1.0f, 0.0f, // Top-right
            -0.5f,  0.5f,  0.0f,  0.0f, 0.0f, // Top-left
            
            // Second quad (perpendicular to first)
             0.0f, -0.5f, -0.5f,  0.0f, 1.0f, // Bottom-left
             0.0f, -0.5f,  0.5f,  1.0f, 1.0f, // Bottom-right
             0.0f,  0.5f,  0.5f,  1.0f, 0.0f, // Top-right
             0.0f,  0.5f, -0.5f,  0.0f, 0.0f  // Top-left
        };
        
        int[] indices = {
            // First quad
            0, 1, 2, 2, 3, 0,
            // Second quad  
            4, 5, 6, 6, 7, 4
        };
        
        itemSpriteVao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(itemSpriteVao);
        
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
     * Helper methods to determine drop entity types and extract data.
     * These would need to be implemented based on the actual drop entity structure.
     */
    
    private boolean isDropEntity(Entity entity) {
        return entity instanceof com.stonebreak.mobs.entities.BlockDrop || 
               entity instanceof com.stonebreak.mobs.entities.ItemDrop;
    }
    
    private boolean isBlockDrop(Entity drop) {
        return drop instanceof com.stonebreak.mobs.entities.BlockDrop;
    }
    
    private boolean isItemDrop(Entity drop) {
        return drop instanceof com.stonebreak.mobs.entities.ItemDrop;
    }
    
    private BlockType getBlockTypeFromDrop(Entity drop) {
        if (drop instanceof com.stonebreak.mobs.entities.BlockDrop blockDrop) {
            return blockDrop.getBlockType();
        }
        return BlockType.AIR; // Default fallback
    }
    
    private ItemType getItemTypeFromDrop(Entity drop) {
        if (drop instanceof com.stonebreak.mobs.entities.ItemDrop itemDrop) {
            return itemDrop.getItemType();
        }
        return null; // No item type
    }
    
    /**
     * Cleanup OpenGL resources.
     */
    public void cleanup() {
        if (itemSpriteVao != 0) {
            GL30.glDeleteVertexArrays(itemSpriteVao);
            itemSpriteVao = 0;
        }
    }
}