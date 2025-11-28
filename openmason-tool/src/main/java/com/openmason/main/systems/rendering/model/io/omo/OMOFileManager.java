package com.openmason.main.systems.rendering.model.io.omo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages discovery and access to .OMO model files.
 */
public class OMOFileManager {

    private static final Logger logger = LoggerFactory.getLogger(OMOFileManager.class);
    private static final String OMO_EXTENSION = ".omo";

    private final Path searchDirectory;
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
        public record OMOFileEntry(String name, Path filePath) {

        /**
             * Gets the model name (filename without .omo extension).
             *
             * @return The model name
             */
            @Override
            public String name() {
                return name;
            }

            /**
             * Gets the full file path.
             *
             * @return The file path
             */
            @Override
            public Path filePath() {
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
