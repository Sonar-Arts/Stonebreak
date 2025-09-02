package com.stonebreak.textures;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Handles cube net format for block textures (64x48 pixels).
 * 
 * Cube Net Format Layout (64x48):
 * 
 *     TOP     (16x16) at (16, 0)
 * LEFT FRONT RIGHT BACK (4x16x16) at (0,16), (16,16), (32,16), (48,16)
 *    BOTTOM   (16x16) at (16, 32)
 * 
 * The layout is:
 *   -TOP-
 * LEFT FRONT RIGHT BACK
 *   -BOTTOM-
 * 
 * Each face is 16x16 pixels. Single texture files contain the entire cube net.
 * This extractor parses these files and extracts individual faces for atlas packing.
 */
public class CubeNetTextureExtractor {
    
    /**
     * Cube face enumeration matching the vertical layout of cube cross format.
     * Order: TOP, BOTTOM, NORTH, SOUTH, EAST, WEST (from top to bottom in 16x96 texture)
     */
    public enum CubeFace {
        TOP(0),      // Y+ face
        BOTTOM(1),   // Y- face  
        NORTH(2),    // Z- face
        SOUTH(3),    // Z+ face
        EAST(4),     // X+ face
        WEST(5);     // X- face
        
        public final int index;
        
        CubeFace(int index) {
            this.index = index;
        }
    }
    
    /**
     * Represents extracted cube face textures.
     */
    public static class ExtractedFaces {
        private final Map<CubeFace, BufferedImage> faces;
        private final String baseName;
        private final boolean isUniform;
        
        public ExtractedFaces(String baseName, boolean isUniform) {
            this.faces = new EnumMap<>(CubeFace.class);
            this.baseName = baseName;
            this.isUniform = isUniform;
        }
        
        public void setFace(CubeFace face, BufferedImage image) {
            faces.put(face, image);
        }
        
        public BufferedImage getFace(CubeFace face) {
            return faces.get(face);
        }
        
        public String getBaseName() {
            return baseName;
        }
        
        public boolean isUniform() {
            return isUniform;
        }
        
        public Set<CubeFace> getAvailableFaces() {
            return faces.keySet();
        }
        
        /**
         * Generate texture names for each face.
         * @return Map of face to texture name
         */
        public Map<CubeFace, String> getFaceNames() {
            Map<CubeFace, String> faceNames = new EnumMap<>(CubeFace.class);
            
            if (isUniform) {
                // Uniform texture uses same name for all faces
                String uniformName = baseName;
                for (CubeFace face : CubeFace.values()) {
                    faceNames.put(face, uniformName);
                }
            } else {
                // Cube cross texture has separate name for each face
                for (CubeFace face : faces.keySet()) {
                    faceNames.put(face, baseName + "_" + face.name().toLowerCase());
                }
            }
            
            return faceNames;
        }
    }
    
    private final Executor extractionExecutor;
    
    public CubeNetTextureExtractor() {
        // Use parallel processing for face extraction
        this.extractionExecutor = Executors.newFixedThreadPool(
            Math.min(6, Runtime.getRuntime().availableProcessors())
        );
    }
    
    /**
     * Extract cube faces from a texture.
     * Automatically detects if texture is cube cross (16x96) or uniform (16x16).
     * 
     * @param texture The loaded texture
     * @param baseName Base name for the texture (without extension)
     * @return ExtractedFaces containing individual face textures
     */
    public ExtractedFaces extractCubeFaces(TextureResourceLoader.LoadedTexture texture, String baseName) {
        if (texture == null || texture.image == null) {
            throw new IllegalArgumentException("Texture cannot be null");
        }
        
        // Remove file extension from base name if present
        String cleanBaseName = baseName.replaceAll("\\.(png|gif)$", "").replace("_texture", "");
        
        // Determine texture type based on dimensions
        if (texture.width == 16 && texture.height == 16) {
            return extractUniformTexture(texture, cleanBaseName);
        } else if (texture.width == 64 && texture.height == 48) {
            return extractCubeCrossTexture(texture, cleanBaseName);
        } else {
            throw new IllegalArgumentException(
                String.format("Invalid texture dimensions for %s: %dx%d. Expected 16x16 (uniform) or 64x48 (cube cross)", 
                baseName, texture.width, texture.height)
            );
        }
    }
    
    /**
     * Extract faces from uniform texture (16x16) - same texture for all faces.
     */
    private ExtractedFaces extractUniformTexture(TextureResourceLoader.LoadedTexture texture, String baseName) {
        ExtractedFaces extractedFaces = new ExtractedFaces(baseName, true);
        
        // Use the same 16x16 texture for all faces
        for (CubeFace face : CubeFace.values()) {
            extractedFaces.setFace(face, copyImage(texture.image));
        }
        
        return extractedFaces;
    }
    
    // 16x96 format support removed - only 64x48 cube cross format is supported
    
    /**
     * Extract faces from 64x48 cube cross texture - 4x3 grid format.
     * Each face is 16x16 pixels arranged in a grid layout.
     */
    private ExtractedFaces extractCubeCrossTexture(TextureResourceLoader.LoadedTexture texture, String baseName) {
        ExtractedFaces extractedFaces = new ExtractedFaces(baseName, false);
        
        // 64x48 layout: 6-face cross format
        // Layout:    -TOP-
        //         WEST FRONT EAST BACK
        //           -BOTTOM-
        //
        // Pixel coordinates (each face is 16x16):
        //      -[16,0]-
        //   [0,16][16,16][32,16][48,16]
        //      -[16,32]-
        
        Map<CubeFace, int[]> faceCoords = new HashMap<>();
        faceCoords.put(CubeFace.TOP, new int[]{16, 0});     // Top center
        faceCoords.put(CubeFace.BOTTOM, new int[]{16, 32}); // Bottom center
        faceCoords.put(CubeFace.NORTH, new int[]{16, 16});  // Front (center)
        faceCoords.put(CubeFace.SOUTH, new int[]{48, 16});  // Back (right)
        faceCoords.put(CubeFace.WEST, new int[]{0, 16});    // Left
        faceCoords.put(CubeFace.EAST, new int[]{32, 16});   // Right
        
        // Extract faces synchronously to avoid nested parallelism deadlock
        // (parallelism is already handled at the batch level in batchExtractFaces)
        for (Map.Entry<CubeFace, int[]> entry : faceCoords.entrySet()) {
            CubeFace face = entry.getKey();
            int[] coords = entry.getValue();
            
            try {
                BufferedImage faceImage = extractFaceImage(texture.image, coords[0], coords[1], 16, 16);
                extractedFaces.setFace(face, faceImage);
            } catch (Exception e) {
                System.err.println("Failed to extract face " + face + " from 64x48 texture " + baseName + ": " + e.getMessage());
            }
        }
        
        return extractedFaces;
    }
    
    /**
     * Extract a face image from the cube cross texture.
     */
    private BufferedImage extractFaceImage(BufferedImage source, int x, int y, int width, int height) {
        // Validate bounds
        if (x + width > source.getWidth() || y + height > source.getHeight()) {
            throw new IllegalArgumentException(
                String.format("Face extraction bounds (%d,%d,%dx%d) exceed source texture size (%dx%d)",
                x, y, width, height, source.getWidth(), source.getHeight())
            );
        }
        
        // Extract the face region
        BufferedImage faceImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var graphics = faceImage.createGraphics();
        
        try {
            graphics.drawImage(source, 0, 0, width, height, x, y, x + width, y + height, null);
        } finally {
            graphics.dispose();
        }
        
        return faceImage;
    }
    
    /**
     * Create a copy of a BufferedImage.
     */
    private BufferedImage copyImage(BufferedImage original) {
        BufferedImage copy = new BufferedImage(original.getWidth(), original.getHeight(), original.getType());
        var graphics = copy.createGraphics();
        
        try {
            graphics.drawImage(original, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        
        return copy;
    }
    
    /**
     * Generate texture atlas entries for extracted faces.
     * @param extractedFaces The extracted cube faces
     * @return List of texture entries for atlas packing
     */
    public List<TextureAtlasPacker.TextureEntry> generateTextureEntries(ExtractedFaces extractedFaces) {
        List<TextureAtlasPacker.TextureEntry> entries = new ArrayList<>();
        Map<CubeFace, String> faceNames = extractedFaces.getFaceNames();
        
        if (extractedFaces.isUniform()) {
            // For uniform textures, create one entry that represents all faces
            entries.add(new TextureAtlasPacker.TextureEntry(
                extractedFaces.getBaseName(),
                extractedFaces.getFace(CubeFace.TOP), // Use any face since they're all the same
                TextureResourceLoader.TextureType.BLOCK_UNIFORM
            ));
        } else {
            // For cube cross textures, create separate entries for each face
            for (CubeFace face : extractedFaces.getAvailableFaces()) {
                String faceName = faceNames.get(face);
                BufferedImage faceImage = extractedFaces.getFace(face);
                
                entries.add(new TextureAtlasPacker.TextureEntry(
                    faceName,
                    faceImage,
                    TextureResourceLoader.TextureType.BLOCK_CUBE_CROSS
                ));
            }
        }
        
        return entries;
    }
    
    /**
     * Batch extract faces from multiple textures in parallel.
     * @param textures Map of texture name to loaded texture
     * @return Map of texture name to extracted faces
     */
    public Map<String, ExtractedFaces> batchExtractFaces(Map<String, TextureResourceLoader.LoadedTexture> textures) {
        System.out.println("Starting batch face extraction for " + textures.size() + " textures...");
        Map<String, ExtractedFaces> results = new ConcurrentHashMap<>();
        
        List<CompletableFuture<Void>> tasks = textures.entrySet().stream()
            .map(entry -> CompletableFuture.runAsync(() -> {
                String textureName = entry.getKey();
                TextureResourceLoader.LoadedTexture texture = entry.getValue();
                
                try {
                    System.out.println("  Extracting faces from: " + textureName + " (" + texture.type + ")");
                    ExtractedFaces faces = extractCubeFaces(texture, textureName);
                    results.put(textureName, faces);
                    System.out.println("  Completed extraction for: " + textureName);
                } catch (Exception e) {
                    System.err.println("Failed to extract faces for texture " + textureName + ": " + e.getMessage());
                    e.printStackTrace();
                    // Continue with other textures - don't fail the entire batch
                }
            }, extractionExecutor))
            .toList();
        
        // Wait for all extractions to complete
        System.out.println("Waiting for all face extraction tasks to complete...");
        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        
        return results;
    }
    
    /**
     * Get cube face for block side during rendering.
     * Maps world directions to cube faces.
     */
    public static CubeFace getFaceForSide(String side) {
        switch (side.toLowerCase()) {
            case "top": return CubeFace.TOP;
            case "bottom": return CubeFace.BOTTOM;
            case "north": return CubeFace.NORTH;
            case "south": return CubeFace.SOUTH;
            case "east": return CubeFace.EAST;
            case "west": return CubeFace.WEST;
            default: 
                System.err.println("Unknown block side: " + side + ", defaulting to TOP");
                return CubeFace.TOP;
        }
    }
    
    /**
     * Validate cube cross texture layout.
     * @param texture The texture to validate
     * @return true if valid cube cross layout
     */
    public static boolean isValidCubeCrossTexture(BufferedImage texture) {
        if (texture.getWidth() != 64 || texture.getHeight() != 48) {
            return false;
        }
        
        // Additional validation could check for consistent face sizes
        // and proper texture alignment, but basic dimension check is sufficient for now
        return true;
    }
    
    /**
     * Shutdown the extraction executor.
     * Should be called when the extractor is no longer needed.
     */
    public void shutdown() {
        if (extractionExecutor instanceof java.util.concurrent.ExecutorService) {
            ((java.util.concurrent.ExecutorService) extractionExecutor).shutdown();
        }
    }
}