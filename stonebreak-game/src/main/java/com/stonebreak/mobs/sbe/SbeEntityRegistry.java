package com.stonebreak.mobs.sbe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Registry of SBE-driven entity assets, indexed by SBE {@code objectId}.
 *
 * <p>Mirrors {@code SBOBlockRegistry}: drop a {@code .sbe} file into the
 * {@code sbe/Mobs/} resource folder and {@link #scanAndLoad()} discovers,
 * decodes (via {@link SbeEntityLoader}) and registers it under the object id
 * declared in its manifest — no per-entity loader class required.
 *
 * <p>Entities reference their asset by object id (see
 * {@code EntityType.getSbeObjectId()}); the renderer stays entity-blind.
 */
public final class SbeEntityRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SbeEntityRegistry.class);

    /** Resource folder scanned for {@code .sbe} files. */
    private static final String SBE_RESOURCE_PATH = "sbe/Mobs";

    private static final Map<String, SbeEntityAsset> BY_OBJECT_ID = new LinkedHashMap<>();

    private SbeEntityRegistry() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Discover and load every {@code .sbe} file under {@code sbe/Mobs/},
     * indexing each by its manifest object id. Failures on individual files are
     * logged and skipped. Safe to call again to rescan.
     *
     * @return the number of entities successfully registered
     */
    public static synchronized int scanAndLoad() {
        BY_OBJECT_ID.clear();
        for (String resourcePath : discover()) {
            try {
                SbeEntityAsset asset = SbeEntityLoader.load(resourcePath);
                BY_OBJECT_ID.put(asset.objectId(), asset);
            } catch (RuntimeException e) {
                logger.error("Failed to load SBE file: {}", resourcePath, e);
            }
        }
        logger.info("SBE entity registry: {} entit(ies) loaded from {}",
                BY_OBJECT_ID.size(), SBE_RESOURCE_PATH);
        return BY_OBJECT_ID.size();
    }

    /**
     * Asset for an SBE object id (e.g. {@code stonebreak:cow}), or {@code null}
     * if none is registered.
     */
    public static SbeEntityAsset get(String objectId) {
        return objectId == null ? null : BY_OBJECT_ID.get(objectId);
    }

    /** Whether an object id is registered. */
    public static boolean contains(String objectId) {
        return objectId != null && BY_OBJECT_ID.containsKey(objectId);
    }

    /** All registered entity assets. */
    public static Collection<SbeEntityAsset> getAll() {
        return Collections.unmodifiableCollection(BY_OBJECT_ID.values());
    }

    /** Number of registered entities. */
    public static int size() {
        return BY_OBJECT_ID.size();
    }

    /**
     * Discover {@code .sbe} files under {@link #SBE_RESOURCE_PATH}, returning
     * absolute classpath resource paths suitable for {@link SbeEntityLoader}.
     * Checks the development filesystem first, then the classpath (incl. JARs).
     */
    private static List<String> discover() {
        Set<String> fileNames = new LinkedHashSet<>();
        try {
            Path fsPath = Path.of(SBE_RESOURCE_PATH);
            if (Files.isDirectory(fsPath)) {
                collectSbeNames(fsPath, fileNames);
            }
            if (fileNames.isEmpty()) {
                URI uri = classpathFolderUri();
                if (uri != null) {
                    if ("jar".equals(uri.getScheme())) {
                        try (FileSystem fs = FileSystems.newFileSystem(uri, Collections.emptyMap())) {
                            collectSbeNames(fs.getPath(SBE_RESOURCE_PATH), fileNames);
                        }
                    } else {
                        collectSbeNames(Path.of(uri), fileNames);
                    }
                }
            }
        } catch (IOException | URISyntaxException e) {
            logger.error("Error discovering SBE files", e);
        }

        List<String> resourcePaths = new ArrayList<>(fileNames.size());
        for (String name : fileNames) {
            resourcePaths.add("/" + SBE_RESOURCE_PATH + "/" + name);
        }
        return resourcePaths;
    }

    private static URI classpathFolderUri() throws URISyntaxException {
        var url = SbeEntityRegistry.class.getClassLoader().getResource(SBE_RESOURCE_PATH);
        return url == null ? null : url.toURI();
    }

    private static void collectSbeNames(Path folder, Set<String> into) throws IOException {
        try (Stream<Path> stream = Files.list(folder)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".sbe"))
                    .forEach(p -> into.add(p.getFileName().toString()));
        }
    }
}
