package com.openmason.deprecated;

import com.openmason.rendering.OpenGLBuffer;
import com.openmason.rendering.VertexArray;
import com.stonebreak.textures.mobs.CowTextureDefinition;
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
 *
 * @deprecated This class is exclusively used for legacy cow model rendering in Open Mason's viewport.
 *             It contains hardcoded cow-specific texture mappings (HEAD, BODY, LEG, HORNS, UDDER, TAIL)
 *             and only works with {@link com.stonebreak.textures.mobs.CowTextureDefinition} and
 *             {@link com.stonebreak.textures.mobs.CowTextureLoader}. It is used by:
 *             - {@link LegacyCowModelRenderer} for rendering {@link com.openmason.deprecated.LegacyCowStonebreakModel}
 *             - {@link VertexArray#fromModelPart} for cow model part initialization
 *             - {@link com.openmason.ui.viewport.OpenMason3DViewport} for cow model visualization
 *             <p>
 *             Block rendering uses the CBR API from stonebreak-game which has its own texture coordinate
 *             management. This class should not be used for new features or non-cow models. Consider
 *             migrating cow rendering to use stonebreak's texture systems directly or creating a generic
 *             texture coordinate buffer that isn't hardcoded to cow anatomy.
 */
@Deprecated
public class LegacyCowTextureCoordinateBuffer extends OpenGLBuffer {
    private final int attributeIndex;
    private int vertexCount;
    private String currentTextureVariant;
    
    /**
     * Creates a new texture coordinate buffer.
     * 
     * @param attributeIndex The vertex attribute index (typically 1 for texture coordinates)
     * @param debugName Debug name for tracking
     */
    public LegacyCowTextureCoordinateBuffer(int attributeIndex, String debugName) {
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
    public LegacyCowTextureCoordinateBuffer(String debugName) {
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
        updateForTextureVariant(cowVariant, null, partName, textureVariant);
    }
    
    /**
     * Updates texture coordinates for a specific texture variant using the model's texture field.
     * This method generates texture coordinates based on the Stonebreak texture atlas system.
     * 
     * @param cowVariant The cow variant containing face mappings
     * @param textureField The model texture field (e.g., "cow_head", "cow_body", "cow_legs")
     * @param partName The model part name (e.g., "head", "body", "leg")
     * @param textureVariant The texture variant name (e.g., "default", "angus", "highland")
     */
    public void updateForTextureVariant(CowTextureDefinition.CowVariant cowVariant, 
                                      String textureField, String partName, String textureVariant) {
        // Check if variant has changed to avoid unnecessary updates
        if (textureVariant.equals(currentTextureVariant)) {
            System.out.println("[TextureCoordinateBuffer] Skipping " + partName + " - already variant " + textureVariant);
            return;
        }
        
        // Generate texture coordinates matching the current vertex count
        // Use texture field if provided, otherwise fall back to part name
        String mappingKey = (textureField != null) ? textureField : partName;
        System.out.println("[TextureCoordinateBuffer] Updating " + partName + " (" + mappingKey + ") to variant " + textureVariant + " (" + vertexCount + " vertices)");
        
        float[] texCoords = generateTextureCoordinates(cowVariant, mappingKey, vertexCount);
        
        if (texCoords.length > 0) {
            // Use dynamic draw for texture coordinates since they may change frequently
            uploadTextureCoords(texCoords, GL15.GL_DYNAMIC_DRAW);
            currentTextureVariant = textureVariant;
            System.out.println("[TextureCoordinateBuffer] Generated " + texCoords.length + " UV coordinates for " + partName);
        } else {
            System.err.println("[TextureCoordinateBuffer] ERROR: No texture coordinates generated for " + partName);
        }
    }
    
    /**
     * Generates texture coordinates for a model part using the texture atlas system.
     * This method maps each face of a cuboid model part to the appropriate texture atlas coordinates.
     * 
     * @param cowVariant The cow variant containing face mappings
     * @param mappingKey The texture field (e.g., "cow_head") or part name for atlas mapping
     * @param vertexCount The actual number of vertices in the model part
     * @return Float array containing texture coordinates (vertexCount * 2 values)
     */
    private float[] generateTextureCoordinates(CowTextureDefinition.CowVariant cowVariant, String mappingKey, int vertexCount) {
        if (cowVariant == null) {
            System.err.println("[TextureCoordinateBuffer] ERROR: Cow variant is null!");
            return new float[vertexCount * 2]; // Return empty coordinates
        }
        
        Map<String, CowTextureDefinition.AtlasCoordinate> faceMappings = cowVariant.getFaceMappings();
        
        // Get the correct atlas face prefix for this model part  
        String atlasFacePrefix = getAtlasFacePrefix(mappingKey);
        
        // Calculate expected UV coordinates based on vertex count
        int expectedUVCount = vertexCount * 2; // 2 UV coordinates per vertex
        float[] result = new float[expectedUVCount];
        
        // Get default texture coordinates for this part type
        String defaultFaceName = atlasFacePrefix + "_FRONT"; // Use front face as default
        float[] defaultFaceCoords = getFaceTextureCoordinates(faceMappings, defaultFaceName);
        
        // Handle different vertex counts for cuboid models
        if (vertexCount == 36) {
            // Standard cuboid UV mapping (6 faces × 6 vertices per face)
            String[] faceNames = {
                atlasFacePrefix + "_FRONT", atlasFacePrefix + "_BACK", atlasFacePrefix + "_LEFT",
                atlasFacePrefix + "_RIGHT", atlasFacePrefix + "_TOP", atlasFacePrefix + "_BOTTOM"
            };
            
            System.out.println("[TextureCoordinateBuffer] Mapping 36-vertex cuboid " + mappingKey + " (" + atlasFacePrefix + ") faces: " + java.util.Arrays.toString(faceNames));
            
            int index = 0;
            for (int faceIndex = 0; faceIndex < faceNames.length; faceIndex++) {
                String faceName = faceNames[faceIndex];
                float[] faceCoords = getFaceTextureCoordinates(faceMappings, faceName);
                
                // Each face has 6 vertices (2 triangles), we need 6 UV coordinates
                for (int i = 0; i < 6 && index + 1 < result.length; i++) {
                    int coordIndex = (i % 4) * 2; // Map to quad vertices (0,1,2,3,2,1)
                    if (i == 4) coordIndex = 4; // Third vertex of second triangle
                    if (i == 5) coordIndex = 2; // Second vertex of second triangle
                    
                    result[index++] = faceCoords[coordIndex];     // u
                    result[index++] = faceCoords[coordIndex + 1]; // v
                }
            }
        } else if (vertexCount == 24) {
            // 24-vertex cuboid UV mapping (4 vertices per face, 6 faces)
            String[] faceNames = {
                atlasFacePrefix + "_FRONT", atlasFacePrefix + "_BACK", atlasFacePrefix + "_LEFT",
                atlasFacePrefix + "_RIGHT", atlasFacePrefix + "_TOP", atlasFacePrefix + "_BOTTOM"
            };
            
            System.out.println("[TextureCoordinateBuffer] Mapping 24-vertex cuboid " + mappingKey + " (" + atlasFacePrefix + ") faces: " + java.util.Arrays.toString(faceNames));
            
            int index = 0;
            for (int faceIndex = 0; faceIndex < faceNames.length; faceIndex++) {
                String faceName = faceNames[faceIndex];
                float[] faceCoords = getFaceTextureCoordinates(faceMappings, faceName);
                
                // Each face has 4 vertices (quad), we need 4 UV coordinates
                for (int i = 0; i < 4 && index + 1 < result.length; i++) {
                    int coordIndex = i * 2; // Map to quad vertices (0,1,2,3)
                    result[index++] = faceCoords[coordIndex];     // u
                    result[index++] = faceCoords[coordIndex + 1]; // v
                }
            }
        } else {
            // For other non-standard vertex counts, repeat the default face coordinates
            System.out.println("[TextureCoordinateBuffer] Non-standard vertex count (" + vertexCount + "), using default face only");
            for (int i = 0; i < vertexCount && i * 2 + 1 < result.length; i++) {
                int coordIndex = (i % 4) * 2; // Cycle through the 4 quad vertices
                result[i * 2] = defaultFaceCoords[coordIndex];         // u
                result[i * 2 + 1] = defaultFaceCoords[coordIndex + 1]; // v
            }
        }
        
        return result;
    }
    
    /**
     * Maps model texture fields or part names to texture atlas face prefixes.
     * This method handles mapping for both texture fields (e.g., "cow_head") and part names (e.g., "head").
     * Uses Stonebreak's exact texture field mapping when available.
     * 
     * @param mappingKey The texture field (e.g., "cow_head") or part name (e.g., "head")
     * @return The corresponding atlas face prefix (e.g., "HEAD", "HORNS", "LEG")
     */
    private String getAtlasFacePrefix(String mappingKey) {
        // First try Stonebreak's exact texture field mapping
        String texturePrefix = getAtlasFacePrefixFromTextureField(mappingKey);
        if (texturePrefix != null) {
            return texturePrefix;
        }
        
        // Fallback to part name mapping for backwards compatibility
        return getAtlasFacePrefixFromPartName(mappingKey);
    }
    
    /**
     * Maps texture fields to atlas face prefixes using Stonebreak's exact logic.
     * 
     * @param textureField The texture field (e.g., "cow_head", "cow_body", "cow_legs")
     * @return The atlas face prefix or null if not a recognized texture field
     */
    private String getAtlasFacePrefixFromTextureField(String textureField) {
        return switch (textureField) {
            case "cow_head" -> "HEAD";
            case "cow_body" -> "BODY";
            case "cow_legs" -> "LEG";
            case "cow_horns" -> "HORNS";
            case "cow_udder" -> "UDDER";
            case "cow_tail" -> "TAIL";
            default -> null; // Not a texture field
        };
    }
    
    /**
     * Maps part names to atlas face prefixes (fallback logic).
     * 
     * @param partName The model part name (e.g., "head", "left_horn", "front_left")
     * @return The corresponding atlas face prefix (e.g., "HEAD", "HORNS", "LEG")
     */
    private String getAtlasFacePrefixFromPartName(String partName) {
        String lowerPartName = partName.toLowerCase();
        
        // Horn parts (left_horn, right_horn) -> HORNS
        if (lowerPartName.contains("horn")) {
            return "HORNS";
        }
        
        // Leg parts (front_left, front_right, back_left, back_right) -> LEG
        if (lowerPartName.contains("front_") || lowerPartName.contains("back_") ||
            lowerPartName.equals("front_left") || lowerPartName.equals("front_right") ||
            lowerPartName.equals("back_left") || lowerPartName.equals("back_right")) {
            return "LEG";
        }
        
        // Head part -> HEAD
        if (lowerPartName.equals("head")) {
            return "HEAD";
        }
        
        // Body part -> BODY
        if (lowerPartName.equals("body")) {
            return "BODY";
        }
        
        // Tail part -> TAIL
        if (lowerPartName.equals("tail")) {
            return "TAIL";
        }
        
        // Udder part -> UDDER
        if (lowerPartName.equals("udder")) {
            return "UDDER";
        }
        
        // Default: use uppercase version of part name
        return partName.toUpperCase();
    }
    
    /**
     * Gets texture coordinates for a specific face from the texture atlas.
     * 
     * @param faceMappings The face mapping definitions
     * @param faceName The face name (e.g., "HEAD_FRONT", "BODY_LEFT")
     * @return Float array with 8 values (4 vertices × 2 coordinates)
     */
    private float[] getFaceTextureCoordinates(Map<String, CowTextureDefinition.AtlasCoordinate> faceMappings, String faceName) {
        // Debug: Log detailed face mapping resolution
        System.out.println("[TextureCoordinateBuffer] FACE MAPPING DEBUG:");
        System.out.println("  Requesting face: " + faceName);
        System.out.println("  Face mappings available: " + (faceMappings != null ? faceMappings.size() : "null"));
        if (faceMappings != null) {
            System.out.println("  Available face keys: " + faceMappings.keySet());
        }
        
        if (faceMappings == null) {
            System.err.println("[TextureCoordinateBuffer] ERROR: Face mappings is null for " + faceName);
            return getDefaultTextureCoordinates();
        }
        
        CowTextureDefinition.AtlasCoordinate mapping = faceMappings.get(faceName);
        
        if (mapping == null) {
            System.err.println("[TextureCoordinateBuffer] ERROR: No face mapping found for " + faceName);
            System.err.println("  Available mappings: " + faceMappings.keySet());
            System.err.println("  This will cause fallback to default coordinates - ALL FACES WILL LOOK THE SAME!");
            // Fallback to default coordinates if mapping not found
            return getDefaultTextureCoordinates();
        }
        
        // Convert atlas coordinates to normalized texture coordinates
        float tileSize = 1.0f / 16.0f; // 16x16 atlas grid
        float u = mapping.getAtlasX() * tileSize;
        float v = mapping.getAtlasY() * tileSize;
        
        System.out.println("[TextureCoordinateBuffer] SUCCESS: Face " + faceName + " mapped to atlas (" + 
                         mapping.getAtlasX() + "," + mapping.getAtlasY() + ") -> UV (" + 
                         String.format("%.3f", u) + "," + String.format("%.3f", v) + ")");
        
        // Return coordinates for a quad (matching Stonebreak's coordinate system)
        // Fixed: Previous version had flipped V-coordinates causing upside-down facial features
        return new float[]{
            u, v,                      // bottom-left
            u + tileSize, v,           // bottom-right
            u + tileSize, v + tileSize, // top-right
            u, v + tileSize            // top-left
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
            0, 0,            // bottom-left
            tileSize, 0,     // bottom-right
            tileSize, tileSize, // top-right
            0, tileSize      // top-left
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
    public static LegacyCowTextureCoordinateBuffer createPlaceholder(int vertexCount, String debugName) {
        LegacyCowTextureCoordinateBuffer buffer = new LegacyCowTextureCoordinateBuffer(debugName);
        
        // Generate placeholder coordinates (all point to first atlas tile)
        float[] placeholderCoords = new float[vertexCount * 2];
        float tileSize = 1.0f / 16.0f;
        
        for (int i = 0; i < vertexCount; i++) {
            int baseIndex = i * 2;
            // Simple UV mapping to first tile
            placeholderCoords[baseIndex] = (i % 2) * tileSize;     // u
            placeholderCoords[baseIndex + 1] = ((i / 2) % 2) * tileSize; // v
        }
        
        System.out.println("[TextureCoordinateBuffer] Created placeholder buffer " + debugName + " with " + vertexCount + " vertices (currentTextureVariant=null)");
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