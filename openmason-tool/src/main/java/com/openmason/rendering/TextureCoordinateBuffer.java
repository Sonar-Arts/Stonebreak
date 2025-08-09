package com.openmason.rendering;

import com.stonebreak.textures.CowTextureDefinition;
import com.stonebreak.textures.CowTextureLoader;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.Map;

/**
 * Texture Coordinate Buffer for managing UV mapping data.
 * Integrates with the Stonebreak texture atlas system to provide proper
 * texture coordinate generation for different model parts and texture variants.
 */
public class TextureCoordinateBuffer extends OpenGLBuffer {
    private final int attributeIndex;
    private int vertexCount;
    private String currentTextureVariant;
    
    /**
     * Creates a new texture coordinate buffer.
     * 
     * @param attributeIndex The vertex attribute index (typically 1 for texture coordinates)
     * @param debugName Debug name for tracking
     */
    public TextureCoordinateBuffer(int attributeIndex, String debugName) {
        super(GL15.GL_ARRAY_BUFFER, debugName);
        this.attributeIndex = attributeIndex;
        this.vertexCount = 0;
        this.currentTextureVariant = null;
    }
    
    /**
     * Convenience constructor using attribute index 1 (standard for texture coordinates).
     * 
     * @param debugName Debug name for tracking
     */
    public TextureCoordinateBuffer(String debugName) {
        this(1, debugName);
    }
    
    /**
     * Uploads texture coordinate data to the GPU with static draw usage.
     * 
     * @param texCoords Texture coordinates as float array (2 components per vertex: u, v)
     */
    public void uploadTextureCoords(float[] texCoords) {
        uploadTextureCoords(texCoords, GL15.GL_STATIC_DRAW);
    }
    
    /**
     * Uploads texture coordinate data to the GPU with specified usage pattern.
     * 
     * @param texCoords Texture coordinates as float array (2 components per vertex: u, v)
     * @param usage OpenGL usage hint (GL_STATIC_DRAW, GL_DYNAMIC_DRAW, etc.)
     */
    public void uploadTextureCoords(float[] texCoords, int usage) {
        if (texCoords.length % 2 != 0) {
            throw new IllegalArgumentException(
                "Texture coordinate data length (" + texCoords.length + ") is not divisible by 2 (u, v components)");
        }
        
        FloatBuffer buffer = MemoryUtil.memAllocFloat(texCoords.length);
        try {
            buffer.put(texCoords).flip();
            uploadData(buffer, usage);
            this.vertexCount = texCoords.length / 2;
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }
    
    /**
     * Updates texture coordinates for a specific texture variant.
     * This method generates texture coordinates based on the Stonebreak texture atlas system.
     * 
     * @param cowVariant The cow variant containing face mappings
     * @param partName The model part name (e.g., "head", "body", "leg")
     * @param textureVariant The texture variant name (e.g., "default", "angus", "highland")
     */
    public void updateForTextureVariant(CowTextureDefinition.CowVariant cowVariant, 
                                      String partName, String textureVariant) {
        // Check if variant has changed to avoid unnecessary updates
        if (textureVariant.equals(currentTextureVariant)) {
            return;
        }
        
        float[] texCoords = generateTextureCoordinates(cowVariant, partName);
        
        if (texCoords.length > 0) {
            // Use dynamic draw for texture coordinates since they may change frequently
            uploadTextureCoords(texCoords, GL15.GL_DYNAMIC_DRAW);
            currentTextureVariant = textureVariant;
        }
    }
    
    /**
     * Generates texture coordinates for a model part using the texture atlas system.
     * This method maps each face of a cuboid model part to the appropriate texture atlas coordinates.
     * 
     * @param cowVariant The cow variant containing face mappings
     * @param partName The model part name
     * @return Float array containing texture coordinates (48 values for 6 faces × 4 vertices × 2 coords)
     */
    private float[] generateTextureCoordinates(CowTextureDefinition.CowVariant cowVariant, String partName) {
        Map<String, CowTextureDefinition.AtlasCoordinate> faceMappings = cowVariant.getFaceMappings();
        String partType = partName.toUpperCase();
        
        // Define face names for a cuboid (6 faces)
        String[] faceNames = {
            partType + "_FRONT",
            partType + "_BACK", 
            partType + "_LEFT",
            partType + "_RIGHT",
            partType + "_TOP",
            partType + "_BOTTOM"
        };
        
        // Build texture coordinate array (48 values: 6 faces × 4 vertices × 2 coordinates)
        float[] result = new float[48];
        int index = 0;
        
        // Process each face
        for (String faceName : faceNames) {
            float[] faceCoords = getFaceTextureCoordinates(faceMappings, faceName);
            
            // Add UV coordinates for this face (4 vertices × 2 coordinates = 8 values)
            System.arraycopy(faceCoords, 0, result, index, 8);
            index += 8;
        }
        
        return result;
    }
    
    /**
     * Gets texture coordinates for a specific face from the texture atlas.
     * 
     * @param faceMappings The face mapping definitions
     * @param faceName The face name (e.g., "HEAD_FRONT", "BODY_LEFT")
     * @return Float array with 8 values (4 vertices × 2 coordinates)
     */
    private float[] getFaceTextureCoordinates(Map<String, CowTextureDefinition.AtlasCoordinate> faceMappings, String faceName) {
        CowTextureDefinition.AtlasCoordinate mapping = faceMappings.get(faceName);
        
        if (mapping == null) {
            // Fallback to default coordinates if mapping not found
            return getDefaultTextureCoordinates();
        }
        
        // Convert atlas coordinates to normalized texture coordinates
        float tileSize = 1.0f / 16.0f; // 16x16 atlas grid
        float u = mapping.getAtlasX() * tileSize;
        float v = mapping.getAtlasY() * tileSize;
        
        // Return coordinates for a quad (counter-clockwise winding)
        return new float[]{
            u, v + tileSize,           // bottom-left
            u + tileSize, v + tileSize, // bottom-right
            u + tileSize, v,           // top-right
            u, v                       // top-left
        };
    }
    
    /**
     * Returns default texture coordinates when face mapping is not available.
     * 
     * @return Default texture coordinates for a single tile
     */
    private float[] getDefaultTextureCoordinates() {
        float tileSize = 1.0f / 16.0f;
        return new float[]{
            0, tileSize,     // bottom-left
            tileSize, tileSize, // bottom-right
            tileSize, 0,     // top-right
            0, 0             // top-left
        };
    }
    
    /**
     * Updates a portion of the texture coordinate data.
     * 
     * @param startVertex The starting vertex index
     * @param texCoords New texture coordinate data
     */
    public void updateTextureCoords(int startVertex, float[] texCoords) {
        if (texCoords.length % 2 != 0) {
            throw new IllegalArgumentException(
                "Texture coordinate data length (" + texCoords.length + ") is not divisible by 2");
        }
        
        long offset = (long) startVertex * 2 * Float.BYTES; // 2 components per vertex
        FloatBuffer buffer = MemoryUtil.memAllocFloat(texCoords.length);
        try {
            buffer.put(texCoords).flip();
            updateData(offset, buffer);
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }
    
    /**
     * Enables this texture coordinate buffer as a vertex attribute array.
     */
    public void enableVertexAttribute() {
        validateBuffer();
        bind();
        GL20.glVertexAttribPointer(attributeIndex, 2, GL11.GL_FLOAT, false, 2 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(attributeIndex);
        lastAccessTime = System.currentTimeMillis();
    }
    
    /**
     * Disables this vertex attribute array.
     */
    public void disableVertexAttribute() {
        GL20.glDisableVertexAttribArray(attributeIndex);
    }
    
    /**
     * Creates a texture coordinate buffer with placeholder coordinates.
     * Useful for initializing buffers before texture variant is determined.
     * 
     * @param vertexCount Number of vertices that will need texture coordinates
     * @param debugName Debug name for the buffer
     * @return Configured texture coordinate buffer with placeholder data
     */
    public static TextureCoordinateBuffer createPlaceholder(int vertexCount, String debugName) {
        TextureCoordinateBuffer buffer = new TextureCoordinateBuffer(debugName);
        
        // Generate placeholder coordinates (all point to first atlas tile)
        float[] placeholderCoords = new float[vertexCount * 2];
        float tileSize = 1.0f / 16.0f;
        
        for (int i = 0; i < vertexCount; i++) {
            int baseIndex = i * 2;
            // Simple UV mapping to first tile
            placeholderCoords[baseIndex] = (i % 2) * tileSize;     // u
            placeholderCoords[baseIndex + 1] = ((i / 2) % 2) * tileSize; // v
        }
        
        buffer.uploadTextureCoords(placeholderCoords, GL15.GL_DYNAMIC_DRAW);
        return buffer;
    }
    
    // Getters
    public int getAttributeIndex() { return attributeIndex; }
    public int getVertexCount() { return vertexCount; }
    public String getCurrentTextureVariant() { return currentTextureVariant; }
    
    @Override
    public String toString() {
        return String.format("TextureCoordinateBuffer{name='%s', id=%d, vertices=%d, variant='%s', attr=%d}",
            debugName, bufferId, vertexCount, currentTextureVariant, attributeIndex);
    }
}