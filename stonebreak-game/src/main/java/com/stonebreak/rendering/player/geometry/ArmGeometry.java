package com.stonebreak.rendering.player.geometry;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Manages the creation and management of player arm geometry (VAOs, VBOs, etc.).
 * Handles the creation of the arm mesh with authentic proportions and UV mapping.
 * 
 * Player model specifications:
 * - Regular arms: 4x12x4 pixels (classic/wide arms)
 * - Slim arms: 3x12x4 pixels (slim arms)
 * - 1 pixel = 0.0625 world units (1/16th of a block)
 * - Uses 64x64 pixel skin texture format
 */
public class ArmGeometry {
    
    private int regularArmVao;
    private int slimArmVao;
    
    // Game scaling: 1 pixel = 0.0625 world units (1/16th of a block)
    private static final float PIXEL_SIZE = 0.0625f;
    
    // Standard first-person arm rotation angles (in radians)
    private static final float ARM_ROTATION_X = (float) Math.toRadians(-10.0f);  // Slight downward tilt
    private static final float ARM_ROTATION_Y = (float) Math.toRadians(-15.0f);  // Slight inward rotation
    private static final float ARM_ROTATION_Z = (float) Math.toRadians(5.0f);    // Slight roll
    
    // Regular (classic) arm dimensions: 4x12x4 pixels
    private static final float REGULAR_ARM_WIDTH = 4.0f * PIXEL_SIZE;
    private static final float REGULAR_ARM_HEIGHT = 12.0f * PIXEL_SIZE;
    private static final float REGULAR_ARM_DEPTH = 4.0f * PIXEL_SIZE;
    
    // Slim arm dimensions: 3x12x4 pixels
    private static final float SLIM_ARM_WIDTH = 3.0f * PIXEL_SIZE;
    private static final float SLIM_ARM_HEIGHT = 12.0f * PIXEL_SIZE;
    private static final float SLIM_ARM_DEPTH = 4.0f * PIXEL_SIZE;
    
    /**
     * Creates the VAO for rendering regular arm with authentic proportions.
     * Regular arm dimensions: 4x12x4 pixels using exact game scaling.
     */
    public int createRegularArmVao() {
        if (regularArmVao != 0) {
            return regularArmVao; // Already created
        }
        
        // Calculate half-extents for regular arms
        float armHalfWidth = REGULAR_ARM_WIDTH / 2.0f;   // 2 pixels = 0.125
        float armHalfHeight = REGULAR_ARM_HEIGHT / 2.0f; // 6 pixels = 0.375
        float armHalfDepth = REGULAR_ARM_DEPTH / 2.0f;   // 2 pixels = 0.125

        return createArmVao(armHalfWidth, armHalfHeight, armHalfDepth, true);
    }
    
    /**
     * Creates the VAO for rendering slim arm with authentic proportions.
     * Slim arm dimensions: 3x12x4 pixels using exact game scaling.
     */
    public int createSlimArmVao() {
        if (slimArmVao != 0) {
            return slimArmVao; // Already created
        }
        
        // Calculate half-extents for slim arms
        float armHalfWidth = SLIM_ARM_WIDTH / 2.0f;   // 1.5 pixels = 0.09375
        float armHalfHeight = SLIM_ARM_HEIGHT / 2.0f; // 6 pixels = 0.375
        float armHalfDepth = SLIM_ARM_DEPTH / 2.0f;   // 2 pixels = 0.125
        
        return createArmVao(armHalfWidth, armHalfHeight, armHalfDepth, false);
    }
    
    /**
     * Applies rotation transformation to a 3D vertex.
     * Performs rotation around X, Y, and Z axes in that order.
     */
    private float[] rotateVertex(float x, float y, float z) {
        // Rotation around X-axis
        float cosX = (float) Math.cos(ARM_ROTATION_X);
        float sinX = (float) Math.sin(ARM_ROTATION_X);
        float y1 = y * cosX - z * sinX;
        float z1 = y * sinX + z * cosX;
        
        // Rotation around Y-axis
        float cosY = (float) Math.cos(ARM_ROTATION_Y);
        float sinY = (float) Math.sin(ARM_ROTATION_Y);
        float x2 = x * cosY + z1 * sinY;
        float z2 = -x * sinY + z1 * cosY;
        
        // Rotation around Z-axis
        float cosZ = (float) Math.cos(ARM_ROTATION_Z);
        float sinZ = (float) Math.sin(ARM_ROTATION_Z);
        float x3 = x2 * cosZ - y1 * sinZ;
        float y3 = x2 * sinZ + y1 * cosZ;
        
        return new float[]{x3, y3, z2};
    }
    
    /**
     * Creates arm geometry with proper UV mapping coordinates.
     * UV coordinates are based on the standard 64x64 skin texture format.
     * 
     * Skin UV layout for right arm (mirrored for left arm):
     * - Right arm is located at (40,16) to (56,32) in the 64x64 texture
     * - Front: (44,20) to (48,32) - 4px wide, 12px tall
     * - Back: (52,20) to (56,32) - 4px wide, 12px tall  
     * - Right side: (40,20) to (44,32) - 4px wide, 12px tall
     * - Left side: (48,20) to (52,32) - 4px wide, 12px tall
     * - Top: (44,16) to (48,20) - 4px wide, 4px tall
     * - Bottom: (48,16) to (52,20) - 4px wide, 4px tall
     */
    private int createArmVao(float armHalfWidth, float armHalfHeight, float armHalfDepth, boolean isRegular) {
        // Standard UV coordinates for 64x64 skin texture (right arm)
        // Converting pixel coordinates to normalized UV (0.0 to 1.0)
        float textureWidth = 64.0f;
        float textureHeight = 64.0f;
        
        // Right arm UV coordinates in 64x64 texture (normalized)
        float frontU1 = 44.0f / textureWidth;   // 0.6875
        float frontU2 = 48.0f / textureWidth;   // 0.75
        float frontV1 = 20.0f / textureHeight;  // 0.3125
        float frontV2 = 32.0f / textureHeight;  // 0.5
        
        float backU1 = 52.0f / textureWidth;    // 0.8125
        float backU2 = 56.0f / textureWidth;    // 0.875
        float backV1 = 20.0f / textureHeight;   // 0.3125
        float backV2 = 32.0f / textureHeight;   // 0.5
        
        float rightU1 = 40.0f / textureWidth;   // 0.625
        float rightU2 = 44.0f / textureWidth;   // 0.6875
        float rightV1 = 20.0f / textureHeight;  // 0.3125
        float rightV2 = 32.0f / textureHeight;  // 0.5
        
        float leftU1 = 48.0f / textureWidth;    // 0.75
        float leftU2 = 52.0f / textureWidth;    // 0.8125
        float leftV1 = 20.0f / textureHeight;   // 0.3125
        float leftV2 = 32.0f / textureHeight;   // 0.5
        
        float topU1 = 44.0f / textureWidth;     // 0.6875
        float topU2 = 48.0f / textureWidth;     // 0.75
        float topV1 = 16.0f / textureHeight;    // 0.25
        float topV2 = 20.0f / textureHeight;    // 0.3125
        
        float bottomU1 = 48.0f / textureWidth;  // 0.75
        float bottomU2 = 52.0f / textureWidth;  // 0.8125
        float bottomV1 = 16.0f / textureHeight; // 0.25
        float bottomV2 = 20.0f / textureHeight; // 0.3125
        
        // For slim arms, adjust the UV coordinates slightly
        if (!isRegular) {
            // Slim model has 3-pixel wide arms, so adjust UV mapping
            frontU2 = 47.0f / textureWidth;     // One pixel narrower
            rightU2 = 43.0f / textureWidth;     // One pixel narrower  
            leftU1 = 47.0f / textureWidth;      // Adjust for narrower arm
            leftU2 = 51.0f / textureWidth;      // One pixel narrower
            topU2 = 47.0f / textureWidth;       // One pixel narrower
            bottomU1 = 47.0f / textureWidth;    // Adjust for narrower arm
            bottomU2 = 51.0f / textureWidth;    // One pixel narrower
        }

        // Define base vertex positions before rotation
        float[][] baseVertices = {
            // Front face vertices
            {-armHalfWidth, -armHalfHeight,  armHalfDepth}, // 0: Front-Bottom-Left
            { armHalfWidth, -armHalfHeight,  armHalfDepth}, // 1: Front-Bottom-Right
            { armHalfWidth,  armHalfHeight,  armHalfDepth}, // 2: Front-Top-Right
            {-armHalfWidth,  armHalfHeight,  armHalfDepth}, // 3: Front-Top-Left
            
            // Back face vertices
            {-armHalfWidth, -armHalfHeight, -armHalfDepth}, // 4: Back-Bottom-Left
            { armHalfWidth, -armHalfHeight, -armHalfDepth}, // 5: Back-Bottom-Right
            { armHalfWidth,  armHalfHeight, -armHalfDepth}, // 6: Back-Top-Right
            {-armHalfWidth,  armHalfHeight, -armHalfDepth}, // 7: Back-Top-Left
            
            // Top face vertices
            {-armHalfWidth,  armHalfHeight, -armHalfDepth}, // 8
            { armHalfWidth,  armHalfHeight, -armHalfDepth}, // 9
            { armHalfWidth,  armHalfHeight,  armHalfDepth}, // 10
            {-armHalfWidth,  armHalfHeight,  armHalfDepth}, // 11
            
            // Bottom face vertices
            {-armHalfWidth, -armHalfHeight,  armHalfDepth}, // 12
            { armHalfWidth, -armHalfHeight,  armHalfDepth}, // 13
            { armHalfWidth, -armHalfHeight, -armHalfDepth}, // 14
            {-armHalfWidth, -armHalfHeight, -armHalfDepth}, // 15
            
            // Right face vertices
            { armHalfWidth, -armHalfHeight, -armHalfDepth}, // 16
            { armHalfWidth, -armHalfHeight,  armHalfDepth}, // 17
            { armHalfWidth,  armHalfHeight,  armHalfDepth}, // 18
            { armHalfWidth,  armHalfHeight, -armHalfDepth}, // 19
            
            // Left face vertices
            {-armHalfWidth, -armHalfHeight,  armHalfDepth}, // 20
            {-armHalfWidth, -armHalfHeight, -armHalfDepth}, // 21
            {-armHalfWidth,  armHalfHeight, -armHalfDepth}, // 22
            {-armHalfWidth,  armHalfHeight,  armHalfDepth}  // 23
        };
        
        // UV coordinates array corresponding to each vertex
        float[][] uvCoords = {
            {frontU1, frontV2}, {frontU2, frontV2}, {frontU2, frontV1}, {frontU1, frontV1}, // Front face
            {backU2, backV2}, {backU1, backV2}, {backU1, backV1}, {backU2, backV1},         // Back face
            {topU1, topV1}, {topU2, topV1}, {topU2, topV2}, {topU1, topV2},                 // Top face
            {bottomU1, bottomV2}, {bottomU2, bottomV2}, {bottomU2, bottomV1}, {bottomU1, bottomV1}, // Bottom face
            {rightU1, rightV2}, {rightU2, rightV2}, {rightU2, rightV1}, {rightU1, rightV1}, // Right face
            {leftU1, leftV2}, {leftU2, leftV2}, {leftU2, leftV1}, {leftU1, leftV1}         // Left face
        };
        
        // Apply rotation to all vertices and build final vertex array
        float[] vertices = new float[baseVertices.length * 5]; // 3 position + 2 UV per vertex
        for (int i = 0; i < baseVertices.length; i++) {
            float[] rotated = rotateVertex(baseVertices[i][0], baseVertices[i][1], baseVertices[i][2]);
            int vertexIndex = i * 5;
            vertices[vertexIndex] = rotated[0];     // X
            vertices[vertexIndex + 1] = rotated[1]; // Y
            vertices[vertexIndex + 2] = rotated[2]; // Z
            vertices[vertexIndex + 3] = uvCoords[i][0]; // U
            vertices[vertexIndex + 4] = uvCoords[i][1]; // V
        }
        
        // Triangle indices for proper face winding order
        int[] indices = {
            // Front face
            0, 1, 2, 2, 3, 0,
            // Back face  
            4, 5, 6, 6, 7, 4,
            // Top face
            11, 10, 9, 9, 8, 11,
            // Bottom face
            12, 13, 14, 14, 15, 12,
            // Right face
            17, 16, 19, 19, 18, 17,
            // Left face
            20, 23, 22, 22, 21, 20
        };

        // Create and configure VAO
        int vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);

        // Create and populate VBO
        int vbo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, vbo);
        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();
        GL20.glBufferData(GL20.GL_ARRAY_BUFFER, vertexBuffer, GL20.GL_STATIC_DRAW);

        // Configure vertex attributes
        // Position attribute (location 0): 3 floats
        GL20.glVertexAttribPointer(0, 3, GL20.GL_FLOAT, false, 5 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);
        // Texture coordinate attribute (location 1): 2 floats
        GL20.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);

        // Create and populate IBO
        int ibo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL20.GL_ELEMENT_ARRAY_BUFFER, ibo);
        IntBuffer indexBuffer = BufferUtils.createIntBuffer(indices.length);
        indexBuffer.put(indices).flip();
        GL20.glBufferData(GL20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL20.GL_STATIC_DRAW);

        // Unbind buffers
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
        
        // Store the VAO ID and return it
        if (isRegular) {
            regularArmVao = vao;
            return regularArmVao;
        } else {
            slimArmVao = vao;
            return slimArmVao;
        }
    }
    
    /**
     * Creates a simple 2D quad geometry for item rendering.
     */
    public static void createTemporaryQuad(float[] vertices, int[] indices) {
        // Create temporary buffers and VAO
        int vao = GL30.glGenVertexArrays();
        int vbo = GL20.glGenBuffers(); 
        int ebo = GL20.glGenBuffers();
        
        try {
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
            
            // Set up vertex attributes - position at 0, UV at 1 (matching shader)
            GL20.glVertexAttribPointer(0, 3, GL20.GL_FLOAT, false, 5 * Float.BYTES, 0);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
            GL20.glEnableVertexAttribArray(1);
            
            // Draw the quad
            GL20.glDrawElements(GL20.GL_TRIANGLES, indices.length, GL20.GL_UNSIGNED_INT, 0);
            
        } finally {
            // Cleanup
            GL30.glBindVertexArray(0);
            GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
            GL20.glBindBuffer(GL20.GL_ELEMENT_ARRAY_BUFFER, 0);
            GL20.glDeleteBuffers(vbo);
            GL20.glDeleteBuffers(ebo);
            GL30.glDeleteVertexArrays(vao);
        }
    }
    
    /**
     * Legacy method for backward compatibility - returns regular arm VAO.
     * @deprecated Use createRegularArmVao() or createSlimArmVao() for explicit arm type selection.
     */
    @Deprecated
    public int createPlayerArmVao() {
        return createRegularArmVao();
    }
    
    /**
     * Gets the regular arm VAO ID.
     * @return VAO ID for regular 4-pixel wide arms, or 0 if not created yet
     */
    public int getRegularArmVao() {
        return regularArmVao;
    }
    
    /**
     * Gets the slim arm VAO ID.
     * @return VAO ID for slim 3-pixel wide arms, or 0 if not created yet
     */
    public int getSlimArmVao() {
        return slimArmVao;
    }
    
    /**
     * Legacy method for backward compatibility - returns regular arm VAO.
     * @deprecated Use getRegularArmVao() or getSlimArmVao() for explicit arm type selection.
     */
    @Deprecated
    public int getArmVao() {
        return getRegularArmVao();
    }
    
    /**
     * Gets the appropriate arm VAO based on player model type.
     * @param isSlimModel true for slim model (3-pixel arms), false for regular/classic model (4-pixel arms)
     * @return VAO ID for the appropriate arm model
     */
    public int getArmVao(boolean isSlimModel) {
        if (isSlimModel) {
            if (slimArmVao == 0) {
                createSlimArmVao();
            }
            return slimArmVao;
        } else {
            if (regularArmVao == 0) {
                createRegularArmVao();
            }
            return regularArmVao;
        }
    }
    
    /**
     * Cleanup all geometry resources.
     */
    public void cleanup() {
        if (regularArmVao != 0) {
            GL30.glDeleteVertexArrays(regularArmVao);
            regularArmVao = 0;
        }
        if (slimArmVao != 0) {
            GL30.glDeleteVertexArrays(slimArmVao);
            slimArmVao = 0;
        }
    }
}