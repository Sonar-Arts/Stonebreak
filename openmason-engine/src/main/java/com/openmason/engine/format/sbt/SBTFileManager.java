package com.openmason.engine.format.sbt;

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
 * Manages discovery and access to .SBT texture files.
 *
 * <p>Mirrors {@link com.openmason.engine.format.omo.OMOFileManager} for SBT
 * archives. Caches scan results so views can call {@link #scanForSBTFiles()}
 * every frame without repeated file I/O.
 */
public class SBTFileManager {

    private static final Logger logger = LoggerFactory.getLogger(SBTFileManager.class);

    private Path searchDirectory;
    private List<SBTFileEntry> cachedEntries;

    /** Creates a manager bound to the given directory. */
    public SBTFileManager(Path searchDirectory) {
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
                logger.info("Created .SBT directory: {}", directory);
            }
        } catch (IOException e) {
            logger.warn("Failed to create .SBT directory: {}", directory, e);
        }
    }

    public Path getSearchDirectory() {
        return searchDirectory;
    }

    /** Forces a rescan on the next call to {@link #scanForSBTFiles()}. */
    public void invalidateCache() {
        this.cachedEntries = null;
    }

    /**
     * Returns the cached list of .SBT entries, scanning on first call or
     * after {@link #invalidateCache()}.
     */
    public List<SBTFileEntry> scanForSBTFiles() {
        if (cachedEntries != null) {
            return cachedEntries;
        }
        cachedEntries = performScan();
        return cachedEntries;
    }

    private List<SBTFileEntry> performScan() {
        if (searchDirectory == null || !Files.exists(searchDirectory) || !Files.isDirectory(searchDirectory)) {
            return Collections.emptyList();
        }

        try (Stream<Path> paths = Files.walk(searchDirectory, 1)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> SBTFormat.hasSBTExtension(path.toString()))
                    .map(this::createEntry)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            logger.error("Failed to scan for .SBT files in: {}", searchDirectory, e);
            return Collections.emptyList();
        }
    }

    private SBTFileEntry createEntry(Path filePath) {
        String fileName = filePath.getFileName().toString();
        String name = fileName.substring(0, fileName.length() - SBTFormat.FILE_EXTENSION.length());
        return new SBTFileEntry(name, filePath);
    }

    /**
     * A discovered .SBT file on disk.
     */
    public record SBTFileEntry(String name, Path filePath) {
        public String getFilePathString() {
            return filePath.toString();
        }
    }
}
