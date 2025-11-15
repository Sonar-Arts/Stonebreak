package com.openmason.model.io.omo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages discovery and access to .OMO model files.
 *
 * <p>Following SOLID principles:</p>
 * <ul>
 *   <li>Single Responsibility: Only handles .OMO file discovery and listing</li>
 *   <li>Open/Closed: New file sources can be added without modifying existing code</li>
 * </ul>
 *
 * <p>Following KISS: Simple file scanning with standard Java NIO</p>
 * <p>Following YAGNI: No caching, no complex features - just basic file discovery</p>
 *
 * @author Open Mason Team
 */
public class OMOFileManager {

    private static final Logger logger = LoggerFactory.getLogger(OMOFileManager.class);
    private static final String OMO_EXTENSION = ".omo";

    private Path searchDirectory;
    private List<OMOFileEntry> cachedEntries; // Cache to avoid repeated scans

    /**
     * Creates a new OMO file manager with default search directory.
     * Default directory is: user.dir/Dev Working/DevTextures/Blocks/OMT/OMOs
     */
    public OMOFileManager() {
        // Default to a common development directory
        String defaultPath = System.getProperty("user.dir") + "/Dev Working/DevTextures/Blocks/OMT/OMOs";
        this.searchDirectory = Paths.get(defaultPath);

        // Create directory if it doesn't exist
        try {
            if (!Files.exists(searchDirectory)) {
                Files.createDirectories(searchDirectory);
                logger.info("Created .OMO directory: {}", searchDirectory);
            }
        } catch (IOException e) {
            logger.warn("Failed to create .OMO directory: {}", searchDirectory, e);
        }
    }

    /**
     * Creates a new OMO file manager with a custom search directory.
     *
     * @param searchDirectory The directory to search for .OMO files
     */
    public OMOFileManager(Path searchDirectory) {
        if (searchDirectory == null) {
            throw new IllegalArgumentException("Search directory cannot be null");
        }
        this.searchDirectory = searchDirectory;
    }

    /**
     * Gets the current search directory.
     *
     * @return The directory where .OMO files are searched
     */
    public Path getSearchDirectory() {
        return searchDirectory;
    }

    /**
     * Sets the search directory for .OMO files.
     * Clears the cache since the directory has changed.
     *
     * @param searchDirectory The new search directory
     */
    public void setSearchDirectory(Path searchDirectory) {
        if (searchDirectory == null) {
            throw new IllegalArgumentException("Search directory cannot be null");
        }
        this.searchDirectory = searchDirectory;
        this.cachedEntries = null; // Clear cache when directory changes
        logger.debug("Search directory changed to: {}", searchDirectory);
    }

    /**
     * Scans the search directory for .OMO files.
     * Returns a list of .OMO file entries with name and path information.
     * Results are cached to avoid repeated file I/O on every frame.
     *
     * @return List of OMO file entries, or empty list if directory doesn't exist or error occurs
     */
    public List<OMOFileEntry> scanForOMOFiles() {
        // Return cached results if available
        if (cachedEntries != null) {
            return cachedEntries;
        }

        // Perform initial scan
        cachedEntries = performScan();
        return cachedEntries;
    }

    /**
     * Refreshes the cache by re-scanning the directory.
     * Call this when you know files have been added/removed.
     *
     * @return List of OMO file entries after refresh
     */
    public List<OMOFileEntry> refresh() {
        cachedEntries = null; // Clear cache
        return scanForOMOFiles();
    }

    /**
     * Internal method that performs the actual directory scan.
     *
     * @return List of OMO file entries
     */
    private List<OMOFileEntry> performScan() {
        if (!Files.exists(searchDirectory)) {
            logger.warn("Search directory does not exist: {}", searchDirectory);
            return Collections.emptyList();
        }

        if (!Files.isDirectory(searchDirectory)) {
            logger.error("Search path is not a directory: {}", searchDirectory);
            return Collections.emptyList();
        }

        try (Stream<Path> paths = Files.walk(searchDirectory, 1)) { // Only search immediate directory
            List<OMOFileEntry> entries = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().toLowerCase().endsWith(OMO_EXTENSION))
                    .map(this::createEntry)
                    .collect(Collectors.toList());

            logger.debug("Found {} .OMO file(s) in: {}", entries.size(), searchDirectory);
            return entries;

        } catch (IOException e) {
            logger.error("Failed to scan for .OMO files in: {}", searchDirectory, e);
            return Collections.emptyList();
        }
    }

    /**
     * Gets a list of .OMO file names (without extension).
     *
     * @return List of model names
     */
    public List<String> getModelNames() {
        return scanForOMOFiles().stream()
                .map(OMOFileEntry::getName)
                .collect(Collectors.toList());
    }

    /**
     * Finds an .OMO file by name (without extension).
     *
     * @param modelName The model name to search for
     * @return The OMOFileEntry if found, or null
     */
    public OMOFileEntry findByName(String modelName) {
        if (modelName == null || modelName.trim().isEmpty()) {
            return null;
        }

        return scanForOMOFiles().stream()
                .filter(entry -> entry.getName().equalsIgnoreCase(modelName))
                .findFirst()
                .orElse(null);
    }

    /**
     * Creates an OMOFileEntry from a file path.
     *
     * @param filePath The path to the .OMO file
     * @return The OMOFileEntry
     */
    private OMOFileEntry createEntry(Path filePath) {
        String fileName = filePath.getFileName().toString();
        String nameWithoutExtension = fileName.substring(0, fileName.length() - OMO_EXTENSION.length());

        return new OMOFileEntry(nameWithoutExtension, filePath);
    }

    /**
     * Represents an .OMO file entry with name and path information.
     */
    public static class OMOFileEntry {
        private final String name;
        private final Path filePath;

        public OMOFileEntry(String name, Path filePath) {
            this.name = name;
            this.filePath = filePath;
        }

        /**
         * Gets the model name (filename without .omo extension).
         *
         * @return The model name
         */
        public String getName() {
            return name;
        }

        /**
         * Gets the full file path.
         *
         * @return The file path
         */
        public Path getFilePath() {
            return filePath;
        }

        /**
         * Gets the file path as a string.
         *
         * @return The file path string
         */
        public String getFilePathString() {
            return filePath.toString();
        }

        @Override
        public String toString() {
            return "OMOFileEntry{name='" + name + "', path='" + filePath + "'}";
        }
    }
}
