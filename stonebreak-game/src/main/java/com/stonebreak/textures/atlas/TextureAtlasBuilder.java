package com.stonebreak.textures.atlas;

import com.stonebreak.textures.loaders.TextureResourceLoader;
import com.stonebreak.textures.loaders.EnhancedJSONLoader;
import com.stonebreak.textures.validation.TextureFormatValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Main coordinator for atlas generation.
 * Coordinates all texture processing and generates final atlas with metadata.
 */
public class TextureAtlasBuilder {
    
    // Resource paths
    private static final String BLOCKS_TEXTURE_PATH = "/blocks/Textures/";
    private static final String ITEMS_TEXTURE_PATH = "/Items/Textures/";
    private static final Path BLOCK_IDS_PATH = Paths.get("stonebreak-game", "src", "main", "resources", "blocks", "Block_ids.JSON");
    private static final Path ATLAS_OUTPUT_DIR = Paths.get("stonebreak-game", "src", "main", "resources", "texture atlas");
    private static final String ATLAS_IMAGE_NAME = "TextureAtlas.png";
    private static final String ATLAS_METADATA_NAME = "atlas_metadata.json";
    
    // Components
    private final TextureFormatValidator validator;
    private final CubeNetTextureExtractor faceExtractor;
    private final TextureAtlasPacker packer;
    private final TextureChecksumManager checksumManager;
    private final AtlasIdManager idManager;
    private final AtlasMetadataCache metadataCache;
    
    // Parallel processing
    private final ExecutorService executorService;
    private final ObjectMapper objectMapper;
    
    public TextureAtlasBuilder() {
        this.validator = new TextureFormatValidator();
        this.faceExtractor = new CubeNetTextureExtractor();
        this.packer = new TextureAtlasPacker();
        this.checksumManager = new TextureChecksumManager();
        this.idManager = new AtlasIdManager();
        this.metadataCache = new AtlasMetadataCache();
        
        this.executorService = Executors.newFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors())
        );
        this.objectMapper = new ObjectMapper();
        
        // Ensure output directory exists
        ensureOutputDirectory();
    }
    
    /**
     * Generate texture atlas from all available block and item textures.
     * @return true if atlas was generated successfully, false otherwise
     */
    public boolean generateAtlas() {
        System.out.println("Starting texture atlas generation...");
        
        try {
            // Step 1: Scan and load all textures
            System.out.println("Loading textures...");
            Map<String, TextureResourceLoader.LoadedTexture> allTextures = loadAllTextures();
            
            if (allTextures.isEmpty()) {
                System.err.println("No textures found to generate atlas");
                return false;
            }
            
            System.out.println("Loaded " + allTextures.size() + " textures");
            
            // Step 2: Validate all textures
            System.out.println("Validating textures...");
            Map<String, TextureResourceLoader.LoadedTexture> validTextures = validateTextures(allTextures);
            
            if (validTextures.isEmpty()) {
                System.err.println("No valid textures found after validation");
                return false;
            }
            
            if (validTextures.size() != allTextures.size()) {
                System.out.println("Validation filtered out " + (allTextures.size() - validTextures.size()) + " invalid textures");
            }
            
            // Step 3: Extract cube faces for block textures
            System.out.println("Extracting cube faces...");
            List<TextureAtlasPacker.TextureEntry> atlasEntries = extractAndPrepareFaces(validTextures);
            
            System.out.println("Generated " + atlasEntries.size() + " atlas entries");
            
            // Debug: Print what entries were generated
            for (TextureAtlasPacker.TextureEntry entry : atlasEntries) {
                System.out.println("  Atlas entry: " + entry.name + " (" + entry.type + ")");
            }
            
            // Step 4: Pack textures into atlas
            System.out.println("Packing textures into atlas...");
            TextureAtlasPacker.PackResult packResult = packer.packTextures(atlasEntries);
            
            System.out.printf("Atlas packed: %dx%d, %.1f%% utilization%n", 
                packResult.atlasWidth, packResult.atlasHeight, packResult.utilizationPercent);
            
            // Step 5: Generate and save atlas image
            System.out.println("Saving atlas image...");
            saveAtlasImage(packResult.atlasImage);
            
            // Step 6: Generate and save metadata
            System.out.println("Generating metadata...");
            generateAndSaveMetadata(packResult);
            
            // Step 7: Update checksums
            System.out.println("Updating checksums...");
            updateChecksums(validTextures);
            
            // Step 8: Update metadata cache
            metadataCache.invalidateCache(); // Force cache refresh
            
            System.out.println("Atlas generation completed successfully!");
            return true;
            
        } catch (Exception e) {
            System.err.println("Failed to generate atlas: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Check if atlas needs to be regenerated based on texture checksums.
     * @return true if atlas should be regenerated
     */
    public boolean shouldRegenerateAtlas() {
        try {
            // Check if atlas files exist
            Path atlasImagePath = ATLAS_OUTPUT_DIR.resolve(ATLAS_IMAGE_NAME);
            Path atlasMetadataPath = ATLAS_OUTPUT_DIR.resolve(ATLAS_METADATA_NAME);
            
            if (!Files.exists(atlasImagePath) || !Files.exists(atlasMetadataPath)) {
                System.out.println("Atlas files missing, regeneration required");
                return true;
            }
            
            // Check texture checksums
            Map<String, TextureResourceLoader.LoadedTexture> currentTextures = loadAllTextures();
            boolean checksumChanged = checksumManager.hasTexturesChanged(currentTextures);
            
            if (checksumChanged) {
                System.out.println("Texture checksums changed, regeneration required");
                return true;
            }
            
            System.out.println("Atlas is up to date");
            return false;
            
        } catch (Exception e) {
            System.err.println("Error checking atlas status: " + e.getMessage());
            // If we can't determine status, err on the side of regeneration
            return true;
        }
    }
    
    /**
     * Load all block and item textures.
     */
    private Map<String, TextureResourceLoader.LoadedTexture> loadAllTextures() {
        Map<String, TextureResourceLoader.LoadedTexture> textures = new HashMap<>();
        
        // Load block textures
        CompletableFuture<Map<String, TextureResourceLoader.LoadedTexture>> blocksFuture = 
            CompletableFuture.supplyAsync(this::loadBlockTextures, executorService);
        
        // Load item textures
        CompletableFuture<Map<String, TextureResourceLoader.LoadedTexture>> itemsFuture = 
            CompletableFuture.supplyAsync(this::loadItemTextures, executorService);
        
        // Wait for both to complete and combine results
        try {
            Map<String, TextureResourceLoader.LoadedTexture> blockTextures = blocksFuture.get();
            Map<String, TextureResourceLoader.LoadedTexture> itemTextures = itemsFuture.get();
            
            textures.putAll(blockTextures);
            textures.putAll(itemTextures);
            
        } catch (Exception e) {
            System.err.println("Error loading textures: " + e.getMessage());
        }
        
        return textures;
    }
    
    /**
     * Load block textures from resources based on Block_ids.JSON.
     */
    private Map<String, TextureResourceLoader.LoadedTexture> loadBlockTextures() {
        Map<String, TextureResourceLoader.LoadedTexture> textures = new HashMap<>();
        
        try {
            // Load block definitions from Block_ids.JSON
            Path blockIdsFile = BLOCK_IDS_PATH;
            if (!Files.exists(blockIdsFile)) {
                System.err.println("Block_ids.JSON not found at: " + blockIdsFile.toAbsolutePath());
                return loadBlockTexturesFallback(); // Use fallback method
            }
            
            Map<String, EnhancedJSONLoader.BlockDefinition> blockDefinitions = 
                EnhancedJSONLoader.loadBlockDefinitions(blockIdsFile.toFile());
            
            Set<String> textureNames = new HashSet<>();
            
            // Extract texture names from block definitions
            for (EnhancedJSONLoader.BlockDefinition blockDef : blockDefinitions.values()) {
                if (blockDef.isUniform() && blockDef.getUniformTexture() != null) {
                    textureNames.add(blockDef.getUniformTexture() + ".png");
                } else if (blockDef.isCubeNet() && blockDef.getCubeNetTexture() != null) {
                    textureNames.add(blockDef.getCubeNetTexture() + ".png");
                }
                // Handle cube_cross textures if needed in the future
            }
            
            System.out.println("Loading " + textureNames.size() + " block textures from Block_ids.JSON");
            
            // Load each texture
            for (String textureName : textureNames) {
                try {
                    TextureResourceLoader.LoadedTexture texture = TextureResourceLoader.loadBlockTexture(textureName);
                    if (texture != null) {
                        textures.put(textureName, texture);
                    } else {
                        System.err.println("Warning: Texture not found: " + textureName);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to load block texture " + textureName + ": " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            System.err.println("Error loading block textures from Block_ids.JSON: " + e.getMessage());
            return loadBlockTexturesFallback(); // Use fallback method
        }
        
        return textures;
    }
    
    /**
     * Fallback method for loading block textures if Block_ids.JSON loading fails.
     */
    private Map<String, TextureResourceLoader.LoadedTexture> loadBlockTexturesFallback() {
        Map<String, TextureResourceLoader.LoadedTexture> textures = new HashMap<>();
        
        // Fallback to hardcoded list
        String[] knownBlockTextures = {
            "grass_block_texture.png", "dirt_block_texture.png", "stone_texture.png", 
            "bedrock_texture.png", "wood_texture.png", "leaves_texture.png",
            "sand_texture.png", "water_temp_texture.png", "coal_ore_texture.png",
            "iron_ore_texture.png", "red_sand_texture.png", "magma_texture.png",
            "crystal_texture.png", "sandstone_texture.png", "red_sandstone_texture.png",
            "rose_texture.png", "dandelion_texture.png", "snowy_dirt_texture.png",
            "pine_wood_texture.png", "ice_texture.png", "pine_leaves_texture.png",
            "snow_texture.png", "workbench_custom_texture.png", "wood_planks_custom_texture.png",
            "pine_wood_planks_custom_texture.png", "elm_wood_log_texture.png", 
            "elm_wood_planks_custom_texture.png", "elm_leaves_texture.png", "cobblestone_texture.png", "Errockson.gif"
        };
        
        for (String fileName : knownBlockTextures) {
            try {
                TextureResourceLoader.LoadedTexture texture = TextureResourceLoader.loadBlockTexture(fileName);
                if (texture != null) {
                    textures.put(fileName, texture);
                }
            } catch (Exception e) {
                System.err.println("Failed to load block texture " + fileName + ": " + e.getMessage());
            }
        }
        
        return textures;
    }
    
    /**
     * Load item textures from resources.
     */
    private Map<String, TextureResourceLoader.LoadedTexture> loadItemTextures() {
        Map<String, TextureResourceLoader.LoadedTexture> textures = new HashMap<>();
        
        try {
            // Load known item textures based on Item_ids.JSON
            String[] knownItemTextures = {
                "stick_texture.png",
                "wooden_pickaxe_texture.png",
                "wooden_axe_texture.png",
                "wooden_bucket_base.png",
                "wooden_bucket_water.png"
            };
            
            for (String fileName : knownItemTextures) {
                try {
                    TextureResourceLoader.LoadedTexture texture = TextureResourceLoader.loadItemTexture(fileName);
                    if (texture != null) {
                        textures.put(fileName, texture);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to load item texture " + fileName + ": " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            System.err.println("Error loading item textures: " + e.getMessage());
        }
        
        return textures;
    }
    
    /**
     * Validate all loaded textures.
     */
    private Map<String, TextureResourceLoader.LoadedTexture> validateTextures(
            Map<String, TextureResourceLoader.LoadedTexture> textures) {
        
        Map<String, TextureResourceLoader.LoadedTexture> validTextures = new HashMap<>();
        int validCount = 0;
        int invalidCount = 0;
        
        for (Map.Entry<String, TextureResourceLoader.LoadedTexture> entry : textures.entrySet()) {
            try {
                boolean isValid = validator.validateTexture(entry.getValue());
                if (isValid) {
                    validTextures.put(entry.getKey(), entry.getValue());
                    validCount++;
                } else {
                    System.out.println("VALIDATION FAILED: " + entry.getKey() + " (" + entry.getValue().type + ", " + 
                                     entry.getValue().width + "x" + entry.getValue().height + ")");
                    invalidCount++;
                }
            } catch (Exception e) {
                System.err.println("Validation exception for texture " + entry.getKey() + ": " + e.getMessage());
                invalidCount++;
            }
        }
        
        System.out.println("Validation results: " + validCount + " valid, " + invalidCount + " invalid");
        return validTextures;
    }
    
    /**
     * Extract cube faces and prepare texture entries for atlas packing.
     */
    private List<TextureAtlasPacker.TextureEntry> extractAndPrepareFaces(
            Map<String, TextureResourceLoader.LoadedTexture> textures) {
        
        List<TextureAtlasPacker.TextureEntry> entries = new ArrayList<>();
        
        // Separate block and item textures
        Map<String, TextureResourceLoader.LoadedTexture> blockTextures = textures.entrySet().stream()
            .filter(entry -> entry.getValue().type == TextureResourceLoader.TextureType.BLOCK_UNIFORM || 
                           entry.getValue().type == TextureResourceLoader.TextureType.BLOCK_CUBE_CROSS ||
                           entry.getValue().type == TextureResourceLoader.TextureType.ERROR)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            
        Map<String, TextureResourceLoader.LoadedTexture> itemTextures = textures.entrySet().stream()
            .filter(entry -> entry.getValue().type == TextureResourceLoader.TextureType.ITEM)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        
        // Process block textures through cube face extraction
        if (!blockTextures.isEmpty()) {
            Map<String, CubeNetTextureExtractor.ExtractedFaces> extractedFaces = 
                faceExtractor.batchExtractFaces(blockTextures);
                
            for (CubeNetTextureExtractor.ExtractedFaces faces : extractedFaces.values()) {
                entries.addAll(faceExtractor.generateTextureEntries(faces));
            }
        }
        
        // Process item textures directly
        for (Map.Entry<String, TextureResourceLoader.LoadedTexture> entry : itemTextures.entrySet()) {
            String name = entry.getKey().replace("_texture.png", "").replace(".png", "");
            entries.add(new TextureAtlasPacker.TextureEntry(
                name, 
                entry.getValue().image, 
                TextureResourceLoader.TextureType.ITEM
            ));
        }
        
        return entries;
    }
    
    /**
     * Save atlas image to file.
     */
    private void saveAtlasImage(BufferedImage atlasImage) throws IOException {
        Path atlasPath = ATLAS_OUTPUT_DIR.resolve(ATLAS_IMAGE_NAME);
        ImageIO.write(atlasImage, "PNG", atlasPath.toFile());
        System.out.println("Atlas image saved: " + atlasPath);
    }
    
    /**
     * Generate and save atlas metadata JSON.
     */
    private void generateAndSaveMetadata(TextureAtlasPacker.PackResult packResult) throws IOException {
        ObjectNode metadata = objectMapper.createObjectNode();
        
        // Atlas info
        metadata.put("atlasVersion", "1.0");
        metadata.put("schemaVersion", "1.0.0");
        metadata.put("generatedAt", Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT));
        metadata.put("textureSize", 16);
        
        // Atlas size info
        ObjectNode atlasSize = objectMapper.createObjectNode();
        atlasSize.put("width", packResult.atlasWidth);
        atlasSize.put("height", packResult.atlasHeight);
        atlasSize.put("calculatedOptimal", true);
        atlasSize.put("utilizationPercent", Math.round(packResult.utilizationPercent * 10.0) / 10.0);
        metadata.set("atlasSize", atlasSize);
        
        // Texture coordinates
        Map<String, Map<String, Object>> coordinates = packer.getTextureCoordinates(packResult.packedTextures);
        metadata.set("textures", objectMapper.valueToTree(coordinates));
        
        // Save metadata file
        Path metadataPath = ATLAS_OUTPUT_DIR.resolve(ATLAS_METADATA_NAME);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(metadataPath.toFile(), metadata);
        
        System.out.println("Atlas metadata saved: " + metadataPath);
    }
    
    /**
     * Update texture checksums after successful atlas generation.
     */
    private void updateChecksums(Map<String, TextureResourceLoader.LoadedTexture> textures) {
        try {
            checksumManager.updateChecksums(textures);
        } catch (Exception e) {
            System.err.println("Failed to update checksums: " + e.getMessage());
        }
    }
    
    /**
     * Ensure the output directory exists.
     */
    private void ensureOutputDirectory() {
        try {
            Path outputDir = ATLAS_OUTPUT_DIR;
            if (!Files.exists(outputDir)) {
                Files.createDirectories(outputDir);
                System.out.println("Created output directory: " + outputDir);
            }
        } catch (IOException e) {
            System.err.println("Failed to create output directory: " + e.getMessage());
        }
    }
    
    /**
     * Get metadata for a specific texture.
     * @param textureName Name of the texture
     * @return Texture metadata including coordinates, or null if not found
     */
    public Map<String, Object> getTextureMetadata(String textureName) {
        return metadataCache.getTextureMetadata(textureName);
    }
    
    /**
     * Get texture coordinates for rendering.
     * @param textureName Name of the texture
     * @return Array of [x, y, width, height] coordinates, or null if not found
     */
    public float[] getTextureCoordinates(String textureName) {
        Map<String, Object> metadata = getTextureMetadata(textureName);
        if (metadata == null) return null;
        
        try {
            int x = ((Number) metadata.get("x")).intValue();
            int y = ((Number) metadata.get("y")).intValue();
            int width = ((Number) metadata.get("width")).intValue();
            int height = ((Number) metadata.get("height")).intValue();
            
            return new float[]{x, y, width, height};
        } catch (Exception e) {
            System.err.println("Error parsing texture coordinates for " + textureName + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Shutdown the atlas builder and cleanup resources.
     */
    public void shutdown() {
        executorService.shutdown();
        faceExtractor.shutdown();
        System.out.println("TextureAtlasBuilder shutdown complete");
    }
}
