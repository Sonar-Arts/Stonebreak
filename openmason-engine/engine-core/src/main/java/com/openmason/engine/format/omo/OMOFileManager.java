package com.openmason.engine.format.omo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages discovery and access to .OMO model files.
 *
 * <p>Caches scan results so views can call {@link #scanForOMOFiles()} every
 * frame without repeated file I/O. The directory is supplied by the caller
 * (typically loaded from user preferences) and can be changed at runtime via
 * {@link #setSearchDirectory(Path)}.
 */
public class OMOFileManager {

    private static final Logger logger = LoggerFactory.getLogger(OMOFileManager.class);

    private Path searchDirectory;
    private List<OMOFileEntry> cachedEntries;

    /** Creates a manager bound to the given directory. */
    public OMOFileManager(Path searchDirectory) {
        setSearchDirectory(searchDirectory);
    }

    /**
     * Updates the search directory and clears the scan cache. Creates the
     * directory on disk if it doesn't yet exist.
     */
    public void setSearchDirectory(Path directory) {
        this.searchDirectory = directory;
        this.cachedEntries = null;
        if (directory == null) {
            return;
        }
        try {
            if (!Files.exists(directory)) {
                Files.createDirectories(directory);
                logger.info("Created .OMO directory: {}", directory);
            }
        } catch (IOException e) {
            logger.warn("Failed to create .OMO directory: {}", directory, e);
        }
    }

    public Path getSearchDirectory() {
        return searchDirectory;
    }

    /** Forces a rescan on the next call to {@link #scanForOMOFiles()}. */
    public void invalidateCache() {
        this.cachedEntries = null;
    }

    /**
     * Returns the cached list of .OMO entries, scanning on first call or
     * after {@link #invalidateCache()}.
     */
    public List<OMOFileEntry> scanForOMOFiles() {
        if (cachedEntries != null) {
            return cachedEntries;
        }
        cachedEntries = performScan();
        return cachedEntries;
    }

    private List<OMOFileEntry> performScan() {
        if (searchDirectory == null || !Files.exists(searchDirectory) || !Files.isDirectory(searchDirectory)) {
            return Collections.emptyList();
        }

        try (Stream<Path> paths = Files.walk(searchDirectory, 1)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> OMOFormat.hasOMOExtension(path.toString()))
                    .map(this::createEntry)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            logger.error("Failed to scan for .OMO files in: {}", searchDirectory, e);
            return Collections.emptyList();
        }
    }

    private OMOFileEntry createEntry(Path filePath) {
        String fileName = filePath.getFileName().toString();
        String name = fileName.substring(0, fileName.length() - OMOFormat.FILE_EXTENSION.length());
        return new OMOFileEntry(name, filePath);
    }

    /**
     * A discovered .OMO file on disk.
     */
    public record OMOFileEntry(String name, Path filePath) {
        public String getFilePathString() {
            return filePath.toString();
        }
    }
}
