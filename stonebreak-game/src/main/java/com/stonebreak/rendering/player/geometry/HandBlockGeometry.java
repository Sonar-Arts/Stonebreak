package com.stonebreak.rendering.player.geometry;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.textures.TextureAtlas;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages the creation and caching of block-specific VAOs for rendering blocks in the player's hand.
 * Each block type gets its own VAO with properly mapped textures for each face.
 */
public class HandBlockGeometry {
    
    private final TextureAtlas textureAtlas;
    private final Map<BlockType, Integer> handBlockVaoCache = new HashMap<>();
    
    public HandBlockGeometry(TextureAtlas textureAtlas) {
        this.textureAtlas = textureAtlas;
    }
    
    /**
     * Gets or creates a block-specific VAO for hand rendering.
     */
    public int getHandBlockVao(BlockType blockType) {
        // Check if VAO is already cached
        Integer cachedVao = handBlockVaoCache.get(blockType);
        if (cachedVao != null) {
            return cachedVao;
        }
        
        // Create new VAO and cache it
        int vao = createBlockSpecificCube(blockType);
        handBlockVaoCache.put(blockType, vao);
        return vao;
    }
    
    /**
     * Creates a VAO for a cube with textures specific to the given BlockType.
     * Each face of the cube uses the appropriate texture coordinates from the atlas.
     */
    private int createBlockSpecificCube(BlockType type) {
        // Use the modern metadata-driven texture atlas system instead of legacy grid coordinates
        float[] frontUVs = textureAtlas.getBlockFaceUVs(type, BlockType.Face.SIDE_NORTH);   // Front
        float[] backUVs = textureAtlas.getBlockFaceUVs(type, BlockType.Face.SIDE_SOUTH);    // Back
        float[] topUVs = textureAtlas.getBlockFaceUVs(type, BlockType.Face.TOP);            // Top
        float[] bottomUVs = textureAtlas.getBlockFaceUVs(type, BlockType.Face.BOTTOM);      // Bottom
        float[] rightUVs = textureAtlas.getBlockFaceUVs(type, BlockType.Face.SIDE_EAST);    // Right
        float[] leftUVs = textureAtlas.getBlockFaceUVs(type, BlockType.Face.SIDE_WEST);     // Left

        // Define vertices for a cube (position, normal, texCoord)
        // Each face defined separately to allow different UVs per face
        float[] vertices = {
            // Front face (+Z)
            -0.5f, -0.5f,  0.5f,  0.0f,  0.0f,  1.0f,  frontUVs[0], frontUVs[3], // Bottom-left
             0.5f, -0.5f,  0.5f,  0.0f,  0.0f,  1.0f,  frontUVs[2], frontUVs[3], // Bottom-right
             0.5f,  0.5f,  0.5f,  0.0f,  0.0f,  1.0f,  frontUVs[2], frontUVs[1], // Top-right
            -0.5f,  0.5f,  0.5f,  0.0f,  0.0f,  1.0f,  frontUVs[0], frontUVs[1], // Top-left
            
            // Back face (-Z)
            -0.5f, -0.5f, -0.5f,  0.0f,  0.0f, -1.0f,  backUVs[0], backUVs[3], // Bottom-left
             0.5f, -0.5f, -0.5f,  0.0f,  0.0f, -1.0f,  backUVs[2], backUVs[3], // Bottom-right
             0.5f,  0.5f, -0.5f,  0.0f,  0.0f, -1.0f,  backUVs[2], backUVs[1], // Top-right
            -0.5f,  0.5f, -0.5f,  0.0f,  0.0f, -1.0f,  backUVs[0], backUVs[1], // Top-left
            
            // Top face (+Y)
            -0.5f,  0.5f, -0.5f,  0.0f,  1.0f,  0.0f,  topUVs[0], topUVs[1], // Top-left
             0.5f,  0.5f, -0.5f,  0.0f,  1.0f,  0.0f,  topUVs[2], topUVs[1], // Top-right
             0.5f,  0.5f,  0.5f,  0.0f,  1.0f,  0.0f,  topUVs[2], topUVs[3], // Bottom-right
            -0.5f,  0.5f,  0.5f,  0.0f,  1.0f,  0.0f,  topUVs[0], topUVs[3], // Bottom-left
            
            // Bottom face (-Y)
            -0.5f, -0.5f, -0.5f,  0.0f, -1.0f,  0.0f, bottomUVs[0], bottomUVs[1], // Top-left
             0.5f, -0.5f, -0.5f,  0.0f, -1.0f,  0.0f, bottomUVs[2], bottomUVs[1], // Top-right
             0.5f, -0.5f,  0.5f,  0.0f, -1.0f,  0.0f, bottomUVs[2], bottomUVs[3], // Bottom-right
            -0.5f, -0.5f,  0.5f,  0.0f, -1.0f,  0.0f, bottomUVs[0], bottomUVs[3], // Bottom-left
            
            // Right face (+X)
             0.5f, -0.5f, -0.5f,  1.0f,  0.0f,  0.0f,  rightUVs[0], rightUVs[3], // Bottom-left
             0.5f, -0.5f,  0.5f,  1.0f,  0.0f,  0.0f,  rightUVs[2], rightUVs[3], // Bottom-right
             0.5f,  0.5f,  0.5f,  1.0f,  0.0f,  0.0f,  rightUVs[2], rightUVs[1], // Top-right
             0.5f,  0.5f, -0.5f,  1.0f,  0.0f,  0.0f,  rightUVs[0], rightUVs[1], // Top-left
            
            // Left face (-X)
            -0.5f, -0.5f, -0.5f, -1.0f,  0.0f,  0.0f,  leftUVs[0], leftUVs[3], // Bottom-left
            -0.5f, -0.5f,  0.5f, -1.0f,  0.0f,  0.0f,  leftUVs[2], leftUVs[3], // Bottom-right
            -0.5f,  0.5f,  0.5f, -1.0f,  0.0f,  0.0f,  leftUVs[2], leftUVs[1], // Top-right
            -0.5f,  0.5f, -0.5f, -1.0f,  0.0f,  0.0f,  leftUVs[0], leftUVs[1]  // Top-left
        };
        
        int[] indices = {
            0,  1,  2,  0,  2,  3,  // Front
            4,  5,  6,  4,  6,  7,  // Back
            8,  9, 10,  8, 10, 11,  // Top
            12, 13, 14, 12, 14, 15, // Bottom
            16, 17, 18, 16, 18, 19, // Right
            20, 21, 22, 20, 22, 23  // Left
        };
        
        // Create VAO
        int vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);
        
        // Create VBO
        int vbo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, vbo);
        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();
        GL20.glBufferData(GL20.GL_ARRAY_BUFFER, vertexBuffer, GL20.GL_STATIC_DRAW);
        
        int stride = 8 * Float.BYTES; // 3 pos, 3 normal, 2 texCoord
        // Position attribute (location 0)
        GL20.glVertexAttribPointer(0, 3, GL20.GL_FLOAT, false, stride, 0);
        GL20.glEnableVertexAttribArray(0);
        // Normal attribute (location 2) - Make sure shader uses location 2 for normals
        GL20.glVertexAttribPointer(2, 3, GL20.GL_FLOAT, false, stride, 3 * Float.BYTES);
        GL20.glEnableVertexAttribArray(2);
        // Texture coordinate attribute (location 1)
        GL20.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, stride, 6 * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);
        
        // Create IBO
        int ibo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL20.GL_ELEMENT_ARRAY_BUFFER, ibo);
        IntBuffer indexBuffer = BufferUtils.createIntBuffer(indices.length);
        indexBuffer.put(indices).flip();
        GL20.glBufferData(GL20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL20.GL_STATIC_DRAW);
        
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
        
        return vao;
    }
    
    /**
     * Cleanup all cached VAOs.
     */
    public void cleanup() {
        for (int vao : handBlockVaoCache.values()) {
            if (vao != 0) {
                GL30.glDeleteVertexArrays(vao);
            }
        }
        handBlockVaoCache.clear();
    }
}