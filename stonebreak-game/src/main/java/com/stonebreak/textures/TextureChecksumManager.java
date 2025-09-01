package com.stonebreak.textures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/**
 * Manages checksums for texture files to detect changes and determine when atlas rebuilding is needed.
 * Stores checksums in JSON format and compares against current file states.
 */
public class TextureChecksumManager {
    
    private static final String CHECKSUM_FILE = "texture_checksums.json";
    private static final String TEXTURE_ATLAS_DIR = "Texture Atlas";
    
    // JSON handling
    private ObjectNode checksumData;
    private final ObjectMapper objectMapper;
    
    /**
     * Represents checksum data for a single texture file.
     */
    public static class TextureChecksum {
        public final String fileName;
        public final String filePath;
        public final String md5Hash;
        public final long fileSize;
        public final long lastModified;
        public final String checksumDate;
        
        public TextureChecksum(String fileName, String filePath, String md5Hash, 
                              long fileSize, long lastModified, String checksumDate) {
            this.fileName = fileName;
            this.filePath = filePath;
            this.md5Hash = md5Hash;
            this.fileSize = fileSize;
            this.lastModified = lastModified;
            this.checksumDate = checksumDate;
        }
        
        public ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode obj = mapper.createObjectNode();
            obj.put("fileName", fileName);
            obj.put("filePath", filePath);
            obj.put("md5Hash", md5Hash);
            obj.put("fileSize", fileSize);
            obj.put("lastModified", lastModified);
            obj.put("checksumDate", checksumDate);
            return obj;
        }
        
        public static TextureChecksum fromJson(JsonNode obj) {
            return new TextureChecksum(
                obj.get("fileName").asText(),
                obj.get("filePath").asText(),
                obj.get("md5Hash").asText(),
                obj.get("fileSize").asLong(),
                obj.get("lastModified").asLong(),
                obj.get("checksumDate").asText()
            );
        }
    }
    
    /**
     * Result of checksum validation operations.
     */
    public static class ValidationResult {
        public final boolean needsRebuild;
        public final List<String> changedFiles;
        public final List<String> newFiles;
        public final List<String> deletedFiles;
        public final String summary;
        
        public ValidationResult(boolean needsRebuild, List<String> changedFiles, 
                               List<String> newFiles, List<String> deletedFiles) {
            this.needsRebuild = needsRebuild;
            this.changedFiles = changedFiles;
            this.newFiles = newFiles;
            this.deletedFiles = deletedFiles;
            
            // Create summary
            StringBuilder sb = new StringBuilder();
            sb.append("Checksum validation: ");
            if (!needsRebuild) {
                sb.append("No changes detected");
            } else {
                sb.append("Changes detected - ");
                if (!changedFiles.isEmpty()) sb.append(changedFiles.size()).append(" changed, ");
                if (!newFiles.isEmpty()) sb.append(newFiles.size()).append(" new, ");
                if (!deletedFiles.isEmpty()) sb.append(deletedFiles.size()).append(" deleted");
            }
            this.summary = sb.toString();
        }
    }
    
    /**
     * Creates a new TextureChecksumManager.
     */
    public TextureChecksumManager() {
        this.objectMapper = new ObjectMapper();
        loadChecksums();
    }
    
    /**
     * Loads existing checksums from storage, or creates new storage if none exists.
     */
    private void loadChecksums() {
        try {
            File checksumFile = getChecksumFile();
            if (checksumFile.exists()) {
                JsonNode rootNode = objectMapper.readTree(checksumFile);
                checksumData = (ObjectNode) rootNode;
                System.out.println("TextureChecksumManager: Loaded checksums from " + checksumFile.getAbsolutePath());
            } else {
                checksumData = objectMapper.createObjectNode();
                checksumData.put("version", "1.0");
                checksumData.put("created", Instant.now().toString());
                checksumData.set("textures", objectMapper.createObjectNode());
                System.out.println("TextureChecksumManager: Created new checksum storage");
            }
        } catch (Exception e) {
            System.err.println("TextureChecksumManager: Failed to load checksums: " + e.getMessage());
            // Create empty structure as fallback
            checksumData = objectMapper.createObjectNode();
            checksumData.put("version", "1.0");
            checksumData.put("created", Instant.now().toString());
            checksumData.set("textures", objectMapper.createObjectNode());
        }
    }
    
    /**
     * Gets the checksum file location, creating directory if needed.
     */
    private File getChecksumFile() throws IOException {
        // Get the resources directory path
        File resourcesDir = new File(System.getProperty("user.dir"), 
                                   "stonebreak-game/src/main/resources");
        File atlasDir = new File(resourcesDir, TEXTURE_ATLAS_DIR);
        
        // Create directory if it doesn't exist
        if (!atlasDir.exists()) {
            atlasDir.mkdirs();
            System.out.println("TextureChecksumManager: Created directory: " + atlasDir.getAbsolutePath());
        }
        
        return new File(atlasDir, CHECKSUM_FILE);
    }
    
    /**
     * Calculates MD5 checksum for a file.
     * @param file The file to checksum
     * @return MD5 hash as hex string, or null if calculation fails
     */
    private String calculateMD5(File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] fileBytes = Files.readAllBytes(file.toPath());
            byte[] hashBytes = md.digest(fileBytes);
            
            // Convert to hex string
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
            
        } catch (NoSuchAlgorithmException | IOException e) {
            System.err.println("TextureChecksumManager: Failed to calculate MD5 for " + file.getName() + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Updates checksums for all texture files.
     * @param blockTextures List of block texture filenames
     * @param itemTextures List of item texture filenames
     */
    public void updateAllChecksums(String[] blockTextures, String[] itemTextures) {
        ObjectNode texturesObj = objectMapper.createObjectNode();
        int successCount = 0;
        int failureCount = 0;
        
        // Process block textures
        File blockTextureDir = new File(System.getProperty("user.dir"), 
                                      "stonebreak-game/src/main/resources/Blocks/Textures");
        for (String fileName : blockTextures) {
            File textureFile = new File(blockTextureDir, fileName);
            TextureChecksum checksum = calculateChecksumForFile(textureFile, "blocks/" + fileName);
            if (checksum != null) {
                texturesObj.set(fileName, checksum.toJson(objectMapper));
                successCount++;
            } else {
                failureCount++;
            }
        }
        
        // Process item textures
        File itemTextureDir = new File(System.getProperty("user.dir"), 
                                     "stonebreak-game/src/main/resources/Items/Textures");
        for (String fileName : itemTextures) {
            File textureFile = new File(itemTextureDir, fileName);
            TextureChecksum checksum = calculateChecksumForFile(textureFile, "items/" + fileName);
            if (checksum != null) {
                texturesObj.set(fileName, checksum.toJson(objectMapper));
                successCount++;
            } else {
                failureCount++;
            }
        }
        
        // Update checksum data
        checksumData.set("textures", texturesObj);
        checksumData.put("lastUpdated", Instant.now().toString());
        
        // Save to file
        saveChecksums();
        
        System.out.println("TextureChecksumManager: Updated checksums for " + successCount + 
                          " textures, " + failureCount + " failures");
    }
    
    /**
     * Calculates checksum for a single file.
     */
    private TextureChecksum calculateChecksumForFile(File file, String relativePath) {
        if (!file.exists()) {
            System.err.println("TextureChecksumManager: File not found: " + file.getAbsolutePath());
            return null;
        }
        
        String md5Hash = calculateMD5(file);
        if (md5Hash == null) {
            return null;
        }
        
        return new TextureChecksum(
            file.getName(),
            relativePath,
            md5Hash,
            file.length(),
            file.lastModified(),
            Instant.now().toString()
        );
    }
    
    /**
     * Validates current texture files against stored checksums.
     * @param blockTextures List of current block texture filenames
     * @param itemTextures List of current item texture filenames
     * @return ValidationResult indicating what changes were detected
     */
    public ValidationResult validateChecksums(String[] blockTextures, String[] itemTextures) {
        List<String> changedFiles = new ArrayList<>();
        List<String> newFiles = new ArrayList<>();
        List<String> deletedFiles = new ArrayList<>();
        
        JsonNode storedTextures = checksumData.has("textures") ? 
            checksumData.get("textures") : objectMapper.createObjectNode();
        Set<String> checkedFiles = new HashSet<>();
        
        // Check block textures
        File blockTextureDir = new File(System.getProperty("user.dir"), 
                                      "stonebreak-game/src/main/resources/Blocks/Textures");
        for (String fileName : blockTextures) {
            checkedFiles.add(fileName);
            File textureFile = new File(blockTextureDir, fileName);
            
            if (storedTextures.has(fileName)) {
                // File exists in checksum data, check if changed
                TextureChecksum stored = TextureChecksum.fromJson(storedTextures.get(fileName));
                TextureChecksum current = calculateChecksumForFile(textureFile, "blocks/" + fileName);
                
                if (current == null) {
                    changedFiles.add(fileName + " (checksum calculation failed)");
                } else if (!stored.md5Hash.equals(current.md5Hash) || 
                          stored.fileSize != current.fileSize ||
                          stored.lastModified != current.lastModified) {
                    changedFiles.add(fileName);
                }
            } else {
                // New file
                newFiles.add(fileName);
            }
        }
        
        // Check item textures
        File itemTextureDir = new File(System.getProperty("user.dir"), 
                                     "stonebreak-game/src/main/resources/Items/Textures");
        for (String fileName : itemTextures) {
            checkedFiles.add(fileName);
            File textureFile = new File(itemTextureDir, fileName);
            
            if (storedTextures.has(fileName)) {
                // File exists in checksum data, check if changed
                TextureChecksum stored = TextureChecksum.fromJson(storedTextures.get(fileName));
                TextureChecksum current = calculateChecksumForFile(textureFile, "items/" + fileName);
                
                if (current == null) {
                    changedFiles.add(fileName + " (checksum calculation failed)");
                } else if (!stored.md5Hash.equals(current.md5Hash) ||
                          stored.fileSize != current.fileSize ||
                          stored.lastModified != current.lastModified) {
                    changedFiles.add(fileName);
                }
            } else {
                // New file
                newFiles.add(fileName);
            }
        }
        
        // Check for deleted files
        storedTextures.fieldNames().forEachRemaining(storedFileName -> {
            if (!checkedFiles.contains(storedFileName)) {
                deletedFiles.add(storedFileName);
            }
        });
        
        boolean needsRebuild = !changedFiles.isEmpty() || !newFiles.isEmpty() || !deletedFiles.isEmpty();
        
        ValidationResult result = new ValidationResult(needsRebuild, changedFiles, newFiles, deletedFiles);
        System.out.println("TextureChecksumManager: " + result.summary);
        
        return result;
    }
    
    /**
     * Saves current checksum data to file.
     */
    public void saveChecksums() {
        try {
            File checksumFile = getChecksumFile();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(checksumFile, checksumData);
            System.out.println("TextureChecksumManager: Saved checksums to " + checksumFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("TextureChecksumManager: Failed to save checksums: " + e.getMessage());
        }
    }
    
    /**
     * Gets checksum information for a specific texture.
     * @param fileName The texture filename
     * @return TextureChecksum object, or null if not found
     */
    public TextureChecksum getChecksum(String fileName) {
        if (!checksumData.has("textures")) {
            return null;
        }
        
        JsonNode textures = checksumData.get("textures");
        if (!textures.has(fileName)) {
            return null;
        }
        
        return TextureChecksum.fromJson(textures.get(fileName));
    }
    
    /**
     * Checks if the checksum storage is empty (first run).
     * @return true if no checksums are stored, false otherwise
     */
    public boolean isEmpty() {
        return !checksumData.has("textures") || 
               checksumData.get("textures").size() == 0;
    }
    
    /**
     * Gets the number of stored checksums.
     * @return Count of textures with checksums
     */
    public int getChecksumCount() {
        if (!checksumData.has("textures")) {
            return 0;
        }
        return checksumData.get("textures").size();
    }
    
    /**
     * Prints a summary of stored checksums for debugging.
     */
    public void printSummary() {
        System.out.println("=== Texture Checksum Manager Summary ===");
        System.out.println("Stored checksums: " + getChecksumCount());
        
        if (checksumData.has("created")) {
            System.out.println("Created: " + checksumData.get("created").asText());
        }
        if (checksumData.has("lastUpdated")) {
            System.out.println("Last updated: " + checksumData.get("lastUpdated").asText());
        }
        
        if (checksumData.has("textures")) {
            JsonNode textures = checksumData.get("textures");
            textures.fieldNames().forEachRemaining(fileName -> {
                TextureChecksum checksum = TextureChecksum.fromJson(textures.get(fileName));
                System.out.println("  " + fileName + " -> " + checksum.md5Hash.substring(0, 8) + "...");
            });
        }
        
        System.out.println("=======================================");
    }
}