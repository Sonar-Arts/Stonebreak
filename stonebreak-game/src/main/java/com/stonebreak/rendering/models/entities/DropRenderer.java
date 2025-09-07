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
 * Specialized renderer for block and item drops in the world.
 * Uses the CBR API and BlockRenderer for 3D block drops, and creates 2D billboard sprite representations for items.
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
        
        // Disable depth writes for transparent objects to prevent occlusion of entities behind them
        glDepthMask(false);
        
        // Render each drop (skip compressed ones)
        for (Entity drop : drops) {
            if (drop.isAlive() && isDropEntity(drop)) {
                // Only render if it's not compressed into another drop
                boolean shouldRender = true;
                if (drop instanceof com.stonebreak.mobs.entities.BlockDrop blockDrop) {
                    shouldRender = blockDrop.shouldRender();
                } else if (drop instanceof com.stonebreak.mobs.entities.ItemDrop itemDrop) {
                    shouldRender = itemDrop.shouldRender();
                }
                
                if (shouldRender) {
                    renderDrop(drop, shaderProgram, viewMatrix);
                }
            }
        }
        
        // Clean up state
        glDisable(GL_BLEND);
        glDepthMask(true); // Re-enable depth writes for subsequent rendering
        GL30.glBindVertexArray(0);
        shaderProgram.setUniform("u_transformUVsForItem", false);
        
        // Reset UI element mode back to false for world rendering
        shaderProgram.setUniform("u_isUIElement", false);
    }
    
    /**
     * Renders a single drop entity.
     */
    private void renderDrop(Entity drop, ShaderProgram shaderProgram, Matrix4f viewMatrix) {
        // Create model matrix for the drop
        Vector3f dropPos = drop.getPosition();
        float dropAge = drop.getAge();
        
        // Apply bobbing animation
        float bobOffset = (float) Math.sin(dropAge * 2.0f) * 0.1f;
        
        if (isItemDrop(drop)) {
            // For 2D sprites, create billboard transformation (always face camera)
            dropModelMatrix.identity()
                .translate(dropPos.x, dropPos.y + bobOffset, dropPos.z)
                .scale(0.3f); // Make item drops slightly larger than blocks for visibility
            
            // Create billboard rotation to face camera
            // Extract camera forward vector from view matrix
            Vector3f cameraForward = new Vector3f();
            viewMatrix.positiveZ(cameraForward).negate();
            
            // Calculate billboard rotation
            float rotationY = (float) Math.atan2(-cameraForward.x, -cameraForward.z);
            rotationMatrix.identity().rotateY(rotationY);
            dropModelMatrix.mul(rotationMatrix);
        } else {
            // For block drops, keep spinning rotation
            float rotationY = dropAge * 50.0f; // Rotate 50 degrees per second
            
            dropModelMatrix.identity()
                .translate(dropPos.x, dropPos.y + bobOffset, dropPos.z)
                .scale(0.25f); // Make drops smaller than full blocks
            
            // Apply rotation for spinning effect
            rotationMatrix.identity().rotateY((float) Math.toRadians(rotationY));
            dropModelMatrix.mul(rotationMatrix);
        }
        
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
        if (cbrManager == null) {
            System.err.println("[DropRenderer] CBR not available for block drop " + blockType);
            return;
        }
        
        CBRResourceManager.BlockRenderResource resource = cbrManager.getBlockTypeResource(blockType);
        
        // Set shader uniforms for block rendering
        shaderProgram.setUniform("u_useSolidColor", false);
        
        // Enable UI element mode for consistent lighting with hotbar icons
        shaderProgram.setUniform("u_isUIElement", true);
        
        // CBR meshes already have texture coordinates baked in, so don't transform them
        shaderProgram.setUniform("u_transformUVsForItem", false);
        
        // Set color with slight transparency for visual appeal
        shaderProgram.setUniform("u_color", new Vector4f(1.0f, 1.0f, 1.0f, 0.95f));
        
        // Render the block mesh
        resource.getMesh().bind();
        glDrawElements(GL_TRIANGLES, resource.getMesh().getIndexCount(), GL_UNSIGNED_INT, 0);
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
        
        // Enable UI element mode for consistent lighting with hotbar icons
        shaderProgram.setUniform("u_isUIElement", true);
        
        shaderProgram.setUniform("u_transformUVsForItem", true);
        
        // Get texture coordinates from item type using TextureAtlas
        // This matches how the 2D item rendering works in hotbars
        float[] texCoords = textureAtlas.getTextureCoordinatesForItem(itemType.getId());
        if (texCoords == null || texCoords.length < 4) {
            System.err.println("[DropRenderer] Failed to get texture coordinates for item: " + itemType.getName());
            return;
        }
        
        // Convert from [u1, v1, u2, v2] format to offset + scale format
        float atlasU = texCoords[0];
        float atlasV = texCoords[1];
        float atlasW = texCoords[2] - texCoords[0];
        float atlasH = texCoords[3] - texCoords[1];
        
        shaderProgram.setUniform("u_atlasUVOffset", new Vector2f(atlasU, atlasV));
        shaderProgram.setUniform("u_atlasUVScale", new Vector2f(atlasW, atlasH));
        
        // Set color with slight transparency
        shaderProgram.setUniform("u_color", new Vector4f(1.0f, 1.0f, 1.0f, 0.95f));
        
        // Render the item sprite as a 2D billboard quad
        GL30.glBindVertexArray(itemSpriteVao);
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0); // 2 triangles per quad
    }
    
    
    /**
     * Creates VAO for rendering item sprites as true 2D sprites.
     * Creates a single billboard quad that always faces the camera.
     */
    private void createItemSpriteVao() {
        // Create a single quad for 2D sprite rendering (billboard style)
        float[] vertices = {
            // Quad vertices (camera-facing billboard)
            -0.5f, -0.5f, 0.0f,  0.0f, 1.0f, // Bottom-left
             0.5f, -0.5f, 0.0f,  1.0f, 1.0f, // Bottom-right
             0.5f,  0.5f, 0.0f,  1.0f, 0.0f, // Top-right
            -0.5f,  0.5f, 0.0f,  0.0f, 0.0f  // Top-left
        };
        
        int[] indices = {
            // Single quad
            0, 1, 2, 2, 3, 0
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