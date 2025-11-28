package com.openmason.ui.themes.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.openmason.ui.themes.core.ThemeDefinition;
import com.openmason.ui.themes.registry.ThemeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * JSON persistence and file I/O component for the Open Mason theming system.
 */
public class ThemeSerializer {
    
    private static final Logger logger = LoggerFactory.getLogger(ThemeSerializer.class);
    
    // Thread safety for file operations
    private final ReadWriteLock fileOperationLock = new ReentrantReadWriteLock();
    
    // Jackson mapper for JSON operations
    private final ObjectMapper objectMapper;
    
    // File system paths (for user themes in app data directory)
    private final Path userThemesPath;
    private final Path tempDirectory;
    
    // File extensions and naming
    private static final String THEME_EXTENSION = ".theme.json";
    private static final String BACKUP_SUFFIX = ".backup";
    private static final String TEMP_SUFFIX = ".tmp";
    
    /**
     * Initialize ThemeSerializer with proper Jackson configuration
     */
    public ThemeSerializer() {
        // Configure Jackson for clean JSON output
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.objectMapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        
        // Initialize file system paths
        this.userThemesPath = initializeUserThemesPath();
        this.tempDirectory = initializeTempDirectory();
        
        logger.info("ThemeSerializer initialized - User themes: {}, Temp: {}", 
                   userThemesPath, tempDirectory);
    }
    
    /**
     * Save a theme to JSON file with atomic write operation
     */
    public void saveTheme(ThemeDefinition theme, String filename, ThemeRegistry.ThemeCategory category) 
            throws IOException, IllegalArgumentException {
        
        if (theme == null) {
            throw new IllegalArgumentException("Theme definition cannot be null");
        }
        
        if (filename == null || filename.trim().isEmpty()) {
            throw new IllegalArgumentException("Filename cannot be null or empty");
        }
        
        if (category == null) {
            throw new IllegalArgumentException("Theme category cannot be null");
        }
        
        // Validate theme before saving
        try {
            theme.validate();
        } catch (Exception e) {
            String error = String.format("Theme validation failed for '%s': %s", filename, e.getMessage());
            logger.error(error);
            throw new IllegalArgumentException(error, e);
        }
        
        // Only allow saving to USER category (built-in and community are read-only)
        if (category != ThemeRegistry.ThemeCategory.USER) {
            throw new IllegalArgumentException("Can only save themes to USER category");
        }
        
        String sanitizedFilename = sanitizeFilename(filename) + THEME_EXTENSION;
        Path targetFile = userThemesPath.resolve(sanitizedFilename);
        
        fileOperationLock.writeLock().lock();
        try {
            // Atomic write operation: write to temp file, then rename
            Path tempFile = tempDirectory.resolve(sanitizedFilename + TEMP_SUFFIX);
            
            // Create backup if file already exists
            Path backupFile = null;
            if (Files.exists(targetFile)) {
                backupFile = createBackup(targetFile);
            }
            
            try {
                // Write to temporary file
                objectMapper.writeValue(tempFile.toFile(), theme);
                
                // Ensure parent directories exist
                Files.createDirectories(targetFile.getParent());
                
                // Atomic move from temp to final location
                Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                
                // Clean up backup if successful
                if (backupFile != null) {
                    try {
                        Files.deleteIfExists(backupFile);
                    } catch (IOException e) {
                        logger.warn("Failed to delete backup file: {}", backupFile, e);
                    }
                }
                
                logger.info("Successfully saved theme '{}' to {}", theme.getName(), targetFile);
                
            } catch (Exception e) {
                // Restore from backup if save failed
                if (backupFile != null && Files.exists(backupFile)) {
                    try {
                        Files.move(backupFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                        logger.info("Restored theme from backup after save failure");
                    } catch (IOException restoreError) {
                        logger.error("Failed to restore backup after save failure", restoreError);
                    }
                }
                
                // Clean up temp file
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException cleanupError) {
                    logger.warn("Failed to clean up temp file: {}", tempFile, cleanupError);
                }
                
                throw new IOException("Failed to save theme: " + e.getMessage(), e);
            }
            
        } finally {
            fileOperationLock.writeLock().unlock();
        }
    }
    
    /**
     * Load a theme from JSON file
     * 
     * @param filename Filename (without extension) 
     * @param category Theme category determines source directory
     * @return Loaded theme definition
     * @throws IOException if file operation fails
     * @throws IllegalArgumentException if theme validation fails
     */
    public ThemeDefinition loadTheme(String filename, ThemeRegistry.ThemeCategory category) 
            throws IOException, IllegalArgumentException {
        
        if (filename == null || filename.trim().isEmpty()) {
            throw new IllegalArgumentException("Filename cannot be null or empty");
        }
        
        if (category == null) {
            throw new IllegalArgumentException("Theme category cannot be null");
        }
        
        String sanitizedFilename = sanitizeFilename(filename) + THEME_EXTENSION;
        Path sourceFile = getThemeFilePath(sanitizedFilename, category);
        
        fileOperationLock.readLock().lock();
        try {
            if (!Files.exists(sourceFile)) {
                throw new IOException("Theme file not found: " + sourceFile);
            }
            
            // Load and validate theme
            ThemeDefinition theme = objectMapper.readValue(sourceFile.toFile(), ThemeDefinition.class);
            
            if (theme == null) {
                throw new IOException("Failed to deserialize theme from: " + sourceFile);
            }
            
            // Validate loaded theme
            try {
                theme.validate();
            } catch (Exception e) {
                String error = String.format("Loaded theme validation failed for '%s': %s", filename, e.getMessage());
                logger.error(error);
                throw new IllegalArgumentException(error, e);
            }
            
            logger.debug("Successfully loaded theme '{}' from {}", theme.getName(), sourceFile);
            return theme;
            
        } catch (JsonProcessingException e) {
            String error = String.format("JSON parsing failed for theme file '%s': %s", filename, e.getMessage());
            logger.error(error);
            throw new IOException(error, e);
            
        } finally {
            fileOperationLock.readLock().unlock();
        }
    }
    
    /**
     * Load all themes from a specific category
     * 
     * @param category Theme category to load from
     * @return Map of filename (without extension) to theme definition
     */
    public Map<String, ThemeDefinition> loadAllThemes(ThemeRegistry.ThemeCategory category) {
        if (category == null) {
            logger.warn("Cannot load themes from null category");
            return Collections.emptyMap();
        }
        
        Map<String, ThemeDefinition> themes = new HashMap<>();
        Path categoryPath = getCategoryPath(category);
        
        if (categoryPath == null || !Files.exists(categoryPath)) {
            logger.debug("Category path does not exist: {}", categoryPath);
            return themes;
        }
        
        fileOperationLock.readLock().lock();
        try (Stream<Path> files = Files.list(categoryPath)) {
            
            List<Path> themeFiles = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(THEME_EXTENSION))
                    .collect(Collectors.toList());
            
            for (Path themeFile : themeFiles) {
                try {
                    String filename = getFilenameWithoutExtension(themeFile);
                    ThemeDefinition theme = loadTheme(filename, category);
                    themes.put(filename, theme);
                    
                } catch (Exception e) {
                    logger.warn("Failed to load theme from {}: {}", themeFile, e.getMessage());
                    // Continue loading other themes
                }
            }
            
            logger.info("Loaded {} themes from category {}", themes.size(), category);
            
        } catch (IOException e) {
            logger.error("Failed to list themes in category {}: {}", category, e.getMessage());
        } finally {
            fileOperationLock.readLock().unlock();
        }
        
        return themes;
    }
    
    /**
     * Delete a theme file
     * 
     * @param filename Filename (without extension)
     * @param category Theme category
     * @return true if file was deleted, false if not found
     * @throws IOException if deletion fails
     */
    public boolean deleteTheme(String filename, ThemeRegistry.ThemeCategory category) throws IOException {
        if (filename == null || filename.trim().isEmpty()) {
            throw new IllegalArgumentException("Filename cannot be null or empty");
        }
        
        if (category == null) {
            throw new IllegalArgumentException("Theme category cannot be null");
        }
        
        // Only allow deletion from USER category
        if (category != ThemeRegistry.ThemeCategory.USER) {
            throw new IllegalArgumentException("Can only delete themes from USER category");
        }
        
        String sanitizedFilename = sanitizeFilename(filename) + THEME_EXTENSION;
        Path targetFile = userThemesPath.resolve(sanitizedFilename);
        
        fileOperationLock.writeLock().lock();
        try {
            if (!Files.exists(targetFile)) {
                logger.debug("Theme file not found for deletion: {}", targetFile);
                return false;
            }
            
            // Create backup before deletion
            Path backupFile = createBackup(targetFile);
            
            try {
                Files.delete(targetFile);
                logger.info("Successfully deleted theme file: {}", targetFile);
                
                // Clean up backup after successful deletion
                try {
                    Files.deleteIfExists(backupFile);
                } catch (IOException e) {
                    logger.warn("Failed to clean up backup file: {}", backupFile, e);
                }
                
                return true;
                
            } catch (Exception e) {
                // Restore from backup if deletion failed
                try {
                    Files.move(backupFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                    logger.info("Restored theme from backup after deletion failure");
                } catch (IOException restoreError) {
                    logger.error("Failed to restore backup after deletion failure", restoreError);
                }
                
                throw new IOException("Failed to delete theme: " + e.getMessage(), e);
            }
            
        } finally {
            fileOperationLock.writeLock().unlock();
        }
    }
    
    /**
     * Export a theme to an external file location
     * 
     * @param theme Theme definition to export
     * @param destination Target file for export
     * @throws IOException if export operation fails
     */
    public void exportTheme(ThemeDefinition theme, File destination) throws IOException {
        if (theme == null) {
            throw new IllegalArgumentException("Theme definition cannot be null");
        }
        
        if (destination == null) {
            throw new IllegalArgumentException("Destination file cannot be null");
        }
        
        // Validate theme before export
        try {
            theme.validate();
        } catch (Exception e) {
            String error = String.format("Theme validation failed for export: %s", e.getMessage());
            logger.error(error);
            throw new IllegalArgumentException(error, e);
        }
        
        fileOperationLock.writeLock().lock();
        try {
            // Ensure parent directory exists
            File parentDir = destination.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                if (!parentDir.mkdirs()) {
                    throw new IOException("Failed to create destination directory: " + parentDir);
                }
            }
            
            // Export with proper JSON formatting
            objectMapper.writeValue(destination, theme);
            
            logger.info("Successfully exported theme '{}' to {}", theme.getName(), destination.getAbsolutePath());
            
        } catch (JsonProcessingException e) {
            String error = String.format("JSON serialization failed for theme export: %s", e.getMessage());
            logger.error(error);
            throw new IOException(error, e);
            
        } finally {
            fileOperationLock.writeLock().unlock();
        }
    }
    
    /**
     * Import a theme from an external file location
     * 
     * @param source Source file to import from
     * @return Imported theme definition
     * @throws IOException if import operation fails
     */
    public ThemeDefinition importTheme(File source) throws IOException {
        if (source == null) {
            throw new IllegalArgumentException("Source file cannot be null");
        }
        
        if (!source.exists()) {
            throw new IOException("Source file does not exist: " + source.getAbsolutePath());
        }
        
        if (!source.isFile()) {
            throw new IOException("Source is not a file: " + source.getAbsolutePath());
        }
        
        fileOperationLock.readLock().lock();
        try {
            // Load and validate theme
            ThemeDefinition theme = objectMapper.readValue(source, ThemeDefinition.class);
            
            if (theme == null) {
                throw new IOException("Failed to deserialize theme from: " + source.getAbsolutePath());
            }
            
            // Validate imported theme
            try {
                theme.validate();
            } catch (Exception e) {
                String error = String.format("Imported theme validation failed: %s", e.getMessage());
                logger.error(error);
                throw new IllegalArgumentException(error, e);
            }
            
            // Set as imported type if not already set
            if (theme.getType() == null) {
                theme.setType(ThemeDefinition.ThemeType.IMPORTED);
            }
            
            logger.info("Successfully imported theme '{}' from {}", theme.getName(), source.getAbsolutePath());
            return theme;
            
        } catch (JsonProcessingException e) {
            String error = String.format("JSON parsing failed for theme import: %s", e.getMessage());
            logger.error(error);
            throw new IOException(error, e);
            
        } finally {
            fileOperationLock.readLock().unlock();
        }
    }
    
    // Helper methods
    
    /**
     * Initialize user themes directory in application data
     */
    private Path initializeUserThemesPath() {
        try {
            String userHome = System.getProperty("user.home");
            Path appDataPath = Paths.get(userHome, ".openmason", "themes", "user");
            Files.createDirectories(appDataPath);
            return appDataPath;
        } catch (Exception e) {
            logger.error("Failed to initialize user themes directory", e);
            // Fallback to temp directory
            return Paths.get(System.getProperty("java.io.tmpdir"), "openmason", "themes");
        }
    }
    
    /**
     * Initialize temporary directory for atomic operations
     */
    private Path initializeTempDirectory() {
        try {
            Path tempPath = Paths.get(System.getProperty("java.io.tmpdir"), "openmason", "temp");
            Files.createDirectories(tempPath);
            return tempPath;
        } catch (Exception e) {
            logger.error("Failed to initialize temp directory", e);
            return Paths.get(System.getProperty("java.io.tmpdir"));
        }
    }
    
    /**
     * Get file path for a theme based on category
     */
    private Path getThemeFilePath(String filename, ThemeRegistry.ThemeCategory category) {
        switch (category) {
            case BUILT_IN:
                // Built-in themes are in resources (read-only)
                throw new UnsupportedOperationException("Built-in themes are loaded from resources");
            case COMMUNITY:
                // Community themes would be downloaded to app data (not implemented yet)
                throw new UnsupportedOperationException("Community themes not yet implemented");
            case USER:
                return userThemesPath.resolve(filename);
            default:
                throw new IllegalArgumentException("Unsupported theme category: " + category);
        }
    }
    
    /**
     * Get directory path for a theme category
     */
    private Path getCategoryPath(ThemeRegistry.ThemeCategory category) {
        switch (category) {
            case USER:
                return userThemesPath;
            case BUILT_IN:
            case COMMUNITY:
                // These would be in resources or downloaded content
                return null;
            default:
                return null;
        }
    }
    
    /**
     * Create backup file for atomic operations
     */
    private Path createBackup(Path originalFile) throws IOException {
        Path backupFile = originalFile.getParent().resolve(
            originalFile.getFileName().toString() + BACKUP_SUFFIX
        );
        
        Files.copy(originalFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
        return backupFile;
    }
    
    /**
     * Sanitize filename to prevent directory traversal and invalid characters
     */
    private String sanitizeFilename(String filename) {
        if (filename == null) {
            return "unnamed_theme";
        }
        
        // Remove path separators and dangerous characters
        String sanitized = filename.replaceAll("[/\\\\:*?\"<>|]", "_")
                                 .replaceAll("\\.", "_")
                                 .trim();
        
        // Ensure non-empty result
        if (sanitized.isEmpty()) {
            sanitized = "unnamed_theme";
        }
        
        // Limit length
        if (sanitized.length() > 50) {
            sanitized = sanitized.substring(0, 50);
        }
        
        return sanitized;
    }
    
    /**
     * Extract filename without extension from path
     */
    private String getFilenameWithoutExtension(Path path) {
        String filename = path.getFileName().toString();
        if (filename.endsWith(THEME_EXTENSION)) {
            return filename.substring(0, filename.length() - THEME_EXTENSION.length());
        }
        return filename;
    }
}