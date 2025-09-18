package com.stonebreak.rendering.models.entities;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.rendering.models.blocks.BlockRenderer;
import com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager;
import com.stonebreak.rendering.player.items.voxelization.VoxelizedSpriteRenderer;
import com.stonebreak.rendering.player.items.voxelization.SpriteVoxelizer;
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
 * Uses the CBR API and BlockRenderer for 3D block drops, and the voxelization system for 3D item drops.
 */
public class DropRenderer {
    
    private final BlockRenderer blockRenderer;
    private final TextureAtlas textureAtlas;
    private CBRResourceManager cbrManager;
    private final VoxelizedSpriteRenderer voxelizedSpriteRenderer;

    private boolean initialized = false;
    
    // Reusable matrices to avoid allocations
    private final Matrix4f dropModelMatrix = new Matrix4f();
    private final Matrix4f rotationMatrix = new Matrix4f();
    
    /**
     * Creates a DropRenderer with the required dependencies.
     */
    public DropRenderer(BlockRenderer blockRenderer, TextureAtlas textureAtlas, ShaderProgram shaderProgram) {
        this.blockRenderer = blockRenderer;
        this.textureAtlas = textureAtlas;
        this.cbrManager = blockRenderer.getCBRResourceManager();
        this.voxelizedSpriteRenderer = new VoxelizedSpriteRenderer(shaderProgram, textureAtlas);
        initialize();
    }
    
    /**
     * Initialize the drop renderer by creating necessary resources.
     */
    private void initialize() {
        if (initialized) return;

        // Preload voxel meshes for all supported items to reduce hitches during gameplay
        voxelizedSpriteRenderer.preloadAllVoxelMeshes();
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
        
        // Enable depth testing for proper rendering
        glEnable(GL_DEPTH_TEST);

        // Note: Blending will be handled per-drop based on transparency requirements
        
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
        
        // Clean up state - restore blending for UI elements
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
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
            // For 3D voxelized items, create standard transformation with spinning rotation
            float rotationY = dropAge * 30.0f; // Slower rotation for voxelized items

            dropModelMatrix.identity()
                .translate(dropPos.x, dropPos.y + bobOffset, dropPos.z)
                .scale(0.25f); // Same size as blocks for consistency

            // Apply rotation for spinning effect
            rotationMatrix.identity().rotateY((float) Math.toRadians(rotationY));
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
        
        // Handle transparency and blending based on block type and settings
        boolean isTransparent = isTransparentBlock(blockType);

        // Handle blending - enable only for transparent blocks
        if (isTransparent) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        } else {
            glDisable(GL_BLEND);
        }

        // Handle depth writing based on block transparency
        // For transparent blocks (like glass), disable depth writes to prevent occlusion issues
        // For solid blocks, enable depth writes to ensure all faces render properly
        glDepthMask(!isTransparent);
        
        // Set shader uniforms for block rendering
        shaderProgram.setUniform("u_useSolidColor", false);
        
        // Enable UI element mode for consistent lighting with hotbar icons
        shaderProgram.setUniform("u_isUIElement", true);
        
        // CBR meshes already have texture coordinates baked in, so don't transform them
        shaderProgram.setUniform("u_transformUVsForItem", false);
        
        // Set color - full opacity for opaque blocks, slight transparency for transparent blocks
        float alpha = isTransparent ? 0.95f : 1.0f;
        shaderProgram.setUniform("u_color", new Vector4f(1.0f, 1.0f, 1.0f, alpha));
        
        // Render the block mesh
        resource.getMesh().bind();
        glDrawElements(GL_TRIANGLES, resource.getMesh().getIndexCount(), GL_UNSIGNED_INT, 0);
        
        // Restore depth writes for subsequent rendering
        glDepthMask(true);
    }
    
    /**
     * Renders an item drop as a 3D voxelized representation.
     */
    private void renderItemDrop(Entity drop, ShaderProgram shaderProgram) {
        ItemType itemType = getItemTypeFromDrop(drop);
        if (itemType == null) {
            return;
        }

        // Check if this item can be rendered using the voxelization system
        if (SpriteVoxelizer.isVoxelizable(itemType)) {
            // Use the voxelized sprite renderer for 3D representation
            voxelizedSpriteRenderer.renderVoxelizedSprite(itemType);
        } else {
            // Fallback to 2D billboard sprite for items without voxelization support
            renderFallback2DItemDrop(drop, shaderProgram, itemType);
        }
    }

    /**
     * Fallback method to render item drops as 2D billboard sprites for items without voxelization support.
     */
    private void renderFallback2DItemDrop(Entity drop, ShaderProgram shaderProgram, ItemType itemType) {
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

        // Create a simple billboard quad for fallback rendering
        renderFallbackBillboardQuad();
    }

    /**
     * Renders a simple billboard quad for fallback item rendering.
     */
    private void renderFallbackBillboardQuad() {
        // Create a simple quad for 2D sprite rendering (billboard style)
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

        // Create temporary VAO for rendering (cleaned up automatically)
        int vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);

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

        // Render the quad
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);

        // Clean up temporary resources
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
        GL30.glDeleteVertexArrays(vao);
        GL20.glDeleteBuffers(vbo);
        GL20.glDeleteBuffers(ibo);
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
     * Determines if a block type should be considered transparent for rendering purposes.
     * Transparent blocks need special depth handling to render properly.
     * Uses the same logic as BlockType.isTransparent() to respect leaf transparency setting.
     */
    private boolean isTransparentBlock(BlockType blockType) {
        // Use the BlockType's isTransparent() method which handles leaf transparency setting
        return blockType.isTransparent();
    }
    
    /**
     * Gets statistics about the voxelized item drop rendering.
     */
    public String getVoxelizationStatistics() {
        return voxelizedSpriteRenderer.getStatistics();
    }

    /**
     * Checks if an item type uses voxelized rendering for drops.
     */
    public boolean usesVoxelizedRendering(ItemType itemType) {
        return SpriteVoxelizer.isVoxelizable(itemType);
    }

    /**
     * Tests the drop rendering system including voxelization.
     */
    public void testDropRendering() {
        System.out.println("=== Drop Renderer Test ===");

        // Test voxelization system
        voxelizedSpriteRenderer.testVoxelizedRendering();

        // Report which items use which rendering method
        System.out.println("\nItem Drop Rendering Methods:");
        for (ItemType itemType : ItemType.values()) {
            if (usesVoxelizedRendering(itemType)) {
                System.out.println("  " + itemType.getName() + ": 3D Voxelized");
            } else {
                System.out.println("  " + itemType.getName() + ": 2D Billboard Fallback");
            }
        }

        System.out.println("=== Drop Renderer Test Complete ===");
    }

    /**
     * Cleanup OpenGL resources.
     */
    public void cleanup() {
        if (voxelizedSpriteRenderer != null) {
            voxelizedSpriteRenderer.cleanup();
        }
    }
}