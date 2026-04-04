package com.stonebreak.rendering.sbo;

import com.openmason.engine.format.sbo.SBOParseResult;
import com.openmason.engine.format.sbo.SBOParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Registry for SBO-based block definitions.
 * Scans a resource folder for .sbo files, parses them, and stores results
 * indexed by object ID.
 *
 * <p>SBO files are treated like lego bricks — drop them into the
 * {@code sbo/blocks/} resource folder and they're automatically loaded.
 */
public class SBOBlockRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SBOBlockRegistry.class);
    private static final String SBO_RESOURCE_PATH = "sbo/blocks";

    private final Map<String, SBOParseResult> registryById = new LinkedHashMap<>();
    private final SBOParser parser = new SBOParser();

    /**
     * Scan the SBO blocks resource folder and parse all .sbo files found.
     * Errors on individual files are logged and skipped.
     *
     * @return number of successfully loaded SBO blocks
     */
    public int scanAndLoad() {
        registryById.clear();
        int loaded = 0;

        // Try classpath scanning first
        List<Path> sboPaths = discoverSBOFiles();
        for (Path sboPath : sboPaths) {
            try {
                SBOParseResult result = parser.parse(sboPath);
                String objectId = result.getObjectId();
                registryById.put(objectId, result);
                loaded++;
                logger.info("Loaded SBO block: {} ({})", result.getObjectName(), objectId);
            } catch (IOException e) {
                logger.error("Failed to parse SBO file: {}", sboPath, e);
            }
        }

        logger.info("SBO block registry: loaded {} blocks from {}", loaded, SBO_RESOURCE_PATH);
        return loaded;
    }

    /**
     * Get a parsed SBO block by its object ID (e.g., "stonebreak:dirt").
     *
     * @param objectId the SBO object ID
     * @return the parse result, or null if not found
     */
    public SBOParseResult getByObjectId(String objectId) {
        return registryById.get(objectId);
    }

    /**
     * Get all loaded SBO block definitions.
     *
     * @return unmodifiable collection of all parse results
     */
    public Collection<SBOParseResult> getAll() {
        return Collections.unmodifiableCollection(registryById.values());
    }

    /**
     * Check if an object ID is registered.
     */
    public boolean contains(String objectId) {
        return registryById.containsKey(objectId);
    }

    /**
     * Get the count of registered SBO blocks.
     */
    public int size() {
        return registryById.size();
    }

    private List<Path> discoverSBOFiles() {
        List<Path> paths = new ArrayList<>();
        try {
            // Try filesystem path first (for development)
            Path fsPath = Path.of(SBO_RESOURCE_PATH);
            if (Files.isDirectory(fsPath)) {
                try (Stream<Path> stream = Files.list(fsPath)) {
                    stream.filter(p -> p.toString().toLowerCase().endsWith(".sbo"))
                            .forEach(paths::add);
                }
                if (!paths.isEmpty()) {
                    logger.debug("Discovered {} SBO files from filesystem: {}", paths.size(), fsPath);
                    return paths;
                }
            }

            // Try classpath resource
            var classLoader = getClass().getClassLoader();
            var resourceUrl = classLoader.getResource(SBO_RESOURCE_PATH);
            if (resourceUrl == null) {
                logger.debug("No SBO resource folder found at: {}", SBO_RESOURCE_PATH);
                return paths;
            }

            URI uri = resourceUrl.toURI();
            Path resourcePath;

            if ("jar".equals(uri.getScheme())) {
                // Running from JAR — use FileSystem to access entries
                FileSystem fs = FileSystems.newFileSystem(uri, Collections.emptyMap());
                resourcePath = fs.getPath(SBO_RESOURCE_PATH);
            } else {
                resourcePath = Path.of(uri);
            }

            try (Stream<Path> stream = Files.list(resourcePath)) {
                stream.filter(p -> p.toString().toLowerCase().endsWith(".sbo"))
                        .forEach(paths::add);
            }

            logger.debug("Discovered {} SBO files from classpath", paths.size());
        } catch (IOException | URISyntaxException e) {
            logger.error("Error discovering SBO files", e);
        }

        return paths;
    }
}
