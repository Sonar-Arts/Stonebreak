package com.stonebreak.rendering.models.blocks;

import com.stonebreak.items.Item;
import com.stonebreak.items.ItemType;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL30.*;

/**
 * Factory for creating Vertex Array Objects for block drops.
 */
public class VAOFactory {
    
    /**
     * Create a 2D sprite VAO for item rendering.
     */
    public static int createSprite2DVAO(ItemType itemType) {
        float[] uvCoords = TextureCoordinateCalculator.calculateSpriteUVs(itemType);
        float size = 0.4f;
        
        // Vertical quad vertices: position (3), UV (2), normal (3), isWater (1), isAlphaTested (1) = 10 floats
        float[] vertices = {
            // Vertical quad facing forward (Z+) - Enable alpha testing for transparency
            -size, -size, 0.0f,  uvCoords[0], uvCoords[3],  0.0f, 0.0f, 1.0f,  0.0f, 1.0f, // Bottom-left
             size, -size, 0.0f,  uvCoords[2], uvCoords[3],  0.0f, 0.0f, 1.0f,  0.0f, 1.0f, // Bottom-right  
             size,  size, 0.0f,  uvCoords[2], uvCoords[1],  0.0f, 0.0f, 1.0f,  0.0f, 1.0f, // Top-right
            -size,  size, 0.0f,  uvCoords[0], uvCoords[1],  0.0f, 0.0f, 1.0f,  0.0f, 1.0f  // Top-left
        };
        
        // Single quad indices (2 triangles)
        int[] indices = {0, 1, 2, 2, 3, 0};
        
        return createVAO(vertices, indices);
    }
    
    /**
     * Create a 3D cube VAO for block/item rendering.
     */
    public static int createCube3DVAO(Item item) {
        float[][] faceUVs = TextureCoordinateCalculator.calculateFaceUVs(item);
        float isAlphaTestedValue = TextureCoordinateCalculator.shouldUseAlphaTesting(item) ? 1.0f : 0.0f;
        
        float halfSize = 0.5f;
        
        // Generate vertex data with proper face-specific UV coordinates at origin
        // Each vertex: position (3), UV (2), normal (3), isWater (1), isAlphaTested (1) = 10 floats
        float[] vertices = {
            // Front face (positive Z) - Face 2
            -halfSize, -halfSize, +halfSize,  faceUVs[2][2], faceUVs[2][3],  0.0f, 0.0f, 1.0f,  0.0f, isAlphaTestedValue, // BL
            +halfSize, -halfSize, +halfSize,  faceUVs[2][4], faceUVs[2][5],  0.0f, 0.0f, 1.0f,  0.0f, isAlphaTestedValue, // BR
            +halfSize, +halfSize, +halfSize,  faceUVs[2][6], faceUVs[2][7],  0.0f, 0.0f, 1.0f,  0.0f, isAlphaTestedValue, // TR
            -halfSize, +halfSize, +halfSize,  faceUVs[2][0], faceUVs[2][1],  0.0f, 0.0f, 1.0f,  0.0f, isAlphaTestedValue, // TL
            
            // Back face (negative Z) - Face 3 - UV order: BR, BL, TL, TR (flipped)
            -halfSize, -halfSize, -halfSize,  faceUVs[3][4], faceUVs[3][5],  0.0f, 0.0f, -1.0f,  0.0f, isAlphaTestedValue, // BR
            +halfSize, -halfSize, -halfSize,  faceUVs[3][2], faceUVs[3][3],  0.0f, 0.0f, -1.0f,  0.0f, isAlphaTestedValue, // BL
            +halfSize, +halfSize, -halfSize,  faceUVs[3][0], faceUVs[3][1],  0.0f, 0.0f, -1.0f,  0.0f, isAlphaTestedValue, // TL
            -halfSize, +halfSize, -halfSize,  faceUVs[3][6], faceUVs[3][7],  0.0f, 0.0f, -1.0f,  0.0f, isAlphaTestedValue, // TR
            
            // Left face (negative X) - Face 5
            -halfSize, -halfSize, -halfSize,  faceUVs[5][2], faceUVs[5][3],  -1.0f, 0.0f, 0.0f,  0.0f, isAlphaTestedValue, // BL
            -halfSize, -halfSize, +halfSize,  faceUVs[5][4], faceUVs[5][5],  -1.0f, 0.0f, 0.0f,  0.0f, isAlphaTestedValue, // BR
            -halfSize, +halfSize, +halfSize,  faceUVs[5][6], faceUVs[5][7],  -1.0f, 0.0f, 0.0f,  0.0f, isAlphaTestedValue, // TR
            -halfSize, +halfSize, -halfSize,  faceUVs[5][0], faceUVs[5][1],  -1.0f, 0.0f, 0.0f,  0.0f, isAlphaTestedValue, // TL
            
            // Right face (positive X) - Face 4
            +halfSize, -halfSize, -halfSize,  faceUVs[4][2], faceUVs[4][3],  1.0f, 0.0f, 0.0f,  0.0f, isAlphaTestedValue, // BL
            +halfSize, -halfSize, +halfSize,  faceUVs[4][4], faceUVs[4][5],  1.0f, 0.0f, 0.0f,  0.0f, isAlphaTestedValue, // BR
            +halfSize, +halfSize, +halfSize,  faceUVs[4][6], faceUVs[4][7],  1.0f, 0.0f, 0.0f,  0.0f, isAlphaTestedValue, // TR
            +halfSize, +halfSize, -halfSize,  faceUVs[4][0], faceUVs[4][1],  1.0f, 0.0f, 0.0f,  0.0f, isAlphaTestedValue, // TL
            
            // Top face (positive Y) - Face 0
            -halfSize, +halfSize, -halfSize,  faceUVs[0][0], faceUVs[0][1],  0.0f, 1.0f, 0.0f,  0.0f, isAlphaTestedValue, // TL
            +halfSize, +halfSize, -halfSize,  faceUVs[0][6], faceUVs[0][7],  0.0f, 1.0f, 0.0f,  0.0f, isAlphaTestedValue, // TR
            +halfSize, +halfSize, +halfSize,  faceUVs[0][4], faceUVs[0][5],  0.0f, 1.0f, 0.0f,  0.0f, isAlphaTestedValue, // BR
            -halfSize, +halfSize, +halfSize,  faceUVs[0][2], faceUVs[0][3],  0.0f, 1.0f, 0.0f,  0.0f, isAlphaTestedValue, // BL
            
            // Bottom face (negative Y) - Face 1
            -halfSize, -halfSize, -halfSize,  faceUVs[1][0], faceUVs[1][1],  0.0f, -1.0f, 0.0f,  0.0f, isAlphaTestedValue, // TL
            +halfSize, -halfSize, -halfSize,  faceUVs[1][6], faceUVs[1][7],  0.0f, -1.0f, 0.0f,  0.0f, isAlphaTestedValue, // TR
            +halfSize, -halfSize, +halfSize,  faceUVs[1][4], faceUVs[1][5],  0.0f, -1.0f, 0.0f,  0.0f, isAlphaTestedValue, // BR
            -halfSize, -halfSize, +halfSize,  faceUVs[1][2], faceUVs[1][3],  0.0f, -1.0f, 0.0f,  0.0f, isAlphaTestedValue  // BL
        };
        
        // Cube indices
        int[] indices = {
            // Front face
            0, 1, 2, 2, 3, 0,
            // Back face
            4, 5, 6, 6, 7, 4,
            // Left face
            8, 9, 10, 10, 11, 8,
            // Right face
            12, 13, 14, 14, 15, 12,
            // Top face
            16, 17, 18, 18, 19, 16,
            // Bottom face
            20, 21, 22, 22, 23, 20
        };
        
        return createVAO(vertices, indices);
    }
    
    /**
     * Create a VAO from vertex and index data.
     */
    private static int createVAO(float[] vertices, int[] indices) {
        int vao = glGenVertexArrays();
        int vbo = glGenBuffers();
        int ebo = glGenBuffers();
        
        glBindVertexArray(vao);
        
        // Upload vertex data
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();
        glBufferData(GL_ARRAY_BUFFER, vertexBuffer, GL_STATIC_DRAW);
        
        // Upload index data
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        IntBuffer indexBuffer = BufferUtils.createIntBuffer(indices.length);
        indexBuffer.put(indices).flip();
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL_STATIC_DRAW);
        
        // Set up vertex attributes
        setupVertexAttributes();
        
        glBindVertexArray(0);
        
        return vao;
    }
    
    /**
     * Setup vertex attributes for block drop rendering.
     */
    private static void setupVertexAttributes() {
        // Position (3 floats)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 10 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);
        
        // UV coordinates (2 floats)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 10 * Float.BYTES, 3 * Float.BYTES);
        glEnableVertexAttribArray(1);
        
        // Normal (3 floats)
        glVertexAttribPointer(2, 3, GL_FLOAT, false, 10 * Float.BYTES, 5 * Float.BYTES);
        glEnableVertexAttribArray(2);
        
        // isWater (1 float)
        glVertexAttribPointer(3, 1, GL_FLOAT, false, 10 * Float.BYTES, 8 * Float.BYTES);
        glEnableVertexAttribArray(3);
        
        // isAlphaTested (1 float)
        glVertexAttribPointer(4, 1, GL_FLOAT, false, 10 * Float.BYTES, 9 * Float.BYTES);
        glEnableVertexAttribArray(4);
    }
}