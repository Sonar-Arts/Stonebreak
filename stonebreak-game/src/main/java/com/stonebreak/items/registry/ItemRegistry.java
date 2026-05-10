package com.stonebreak.items.registry;

import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOParseResult;
import com.openmason.engine.format.sbo.SBOParser;
import com.stonebreak.items.ItemCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Authoritative auto-populated registry of game items, sourced from SBO files
 * under {@code sbo/items/} (texture-only SBOs, format 1.2+).
 *
 * <p>Mirrors {@link com.stonebreak.blocks.registry.BlockRegistry} for the item
 * domain. SBO files dropped into {@code sbo/items/} appear here on next
 * registry load with no enum edits required.
 *
 * <p>Indexed by both namespaced object ID (e.g. {@code "stonebreak:sword"})
 * and stable numeric ID. The numeric ID is sourced from the SBO's
 * {@link SBOFormat.GameProperties} and lives in the 1000+ range to avoid
 * collision with block IDs.
 */
public final class ItemRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ItemRegistry.class);
    private static final ItemRegistry INSTANCE = new ItemRegistry();
    private static final String SBO_RESOURCE_PATH = "sbo/items";

    public static ItemRegistry getInstance() {
        return INSTANCE;
    }

    private final SBOParser parser = new SBOParser();
    private final Map<String, ItemEntry> byObjectId = new LinkedHashMap<>();
    private final Map<Integer, ItemEntry> byNumericId = new LinkedHashMap<>();
    private boolean loaded = false;

    private ItemRegistry() {}

    /**
     * Scan {@code sbo/items/} on the classpath / dev filesystem and register
     * every texture-only SBO of {@code objectType=item} found. Idempotent —
     * subsequent calls clear and re-load.
     *
     * @return number of items registered
     */
    public synchronized int scanAndLoad() {
        byObjectId.clear();
        byNumericId.clear();

        int registered = 0;
        for (Path sboPath : discoverSBOFiles()) {
            try {
                SBOParseResult result = parser.parse(sboPath);
                if (!"item".equalsIgnoreCase(result.getObjectType())) {
                    logger.warn("Skipping non-item SBO in items folder: {} (type={})",
                            result.getObjectId(), result.getObjectType());
                    continue;
                }
                if (!result.isTextureOnly()) {
                    logger.warn("Skipping item SBO without texture payload: {}", result.getObjectId());
                    continue;
                }

                SBOFormat.GameProperties gp = result.manifest().gameProperties();
                if (gp == null) {
                    logger.warn("Skipping item SBO missing gameProperties: {}", result.getObjectId());
                    continue;
                }

                // Note: we don't promote into ItemType here. ItemType's own
                // static initializer reads from this registry to create
                // instances for both hardcoded constants and SBO-only items.
                ItemEntry entry = new ItemEntry(result.getObjectId(), result.getObjectName(), gp, result);
                ItemEntry prev = byObjectId.put(entry.objectId(), entry);
                if (prev != null) {
                    logger.warn("Duplicate item objectId '{}' — keeping last loaded", entry.objectId());
                }
                if (gp.numericId() >= 0) {
                    ItemEntry prevById = byNumericId.put(gp.numericId(), entry);
                    if (prevById != null && !prevById.objectId().equals(entry.objectId())) {
                        logger.error("Item numeric ID collision: {} and {} both claim id={}",
                                prevById.objectId(), entry.objectId(), gp.numericId());
                    }
                }
                registered++;
            } catch (IOException e) {
                logger.error("Failed to parse item SBO: {}", sboPath, e);
            }
        }

        loaded = true;
        logger.info("ItemRegistry: loaded {} items from {}", registered, SBO_RESOURCE_PATH);
        return registered;
    }

    /** Lazy-load if {@link #scanAndLoad()} hasn't been called yet. */
    private synchronized void ensureLoaded() {
        if (!loaded) {
            scanAndLoad();
        }
    }

    public Optional<ItemEntry> get(String objectId) {
        ensureLoaded();
        return Optional.ofNullable(byObjectId.get(objectId));
    }

    public Optional<ItemEntry> getById(int numericId) {
        ensureLoaded();
        return Optional.ofNullable(byNumericId.get(numericId));
    }

    public Collection<ItemEntry> all() {
        ensureLoaded();
        return Collections.unmodifiableCollection(byObjectId.values());
    }

    public boolean contains(String objectId) {
        ensureLoaded();
        return byObjectId.containsKey(objectId);
    }

    public int size() {
        ensureLoaded();
        return byObjectId.size();
    }

    /**
     * Drop the namespace prefix and uppercase the local part:
     * {@code "stonebreak:sword"} → {@code "SWORD"}. Used by {@code ItemType}
     * to derive default names for SBO-only items.
     */
    public static String sboNameToEnumName(String objectId) {
        int colon = objectId.indexOf(':');
        String local = colon >= 0 ? objectId.substring(colon + 1) : objectId;
        return local.toUpperCase();
    }

    /**
     * Parse the SBO {@code gameProperties.category} string into the game's
     * {@link ItemCategory}. Falls back to {@code TOOLS} for unknown values.
     */
    public static ItemCategory parseCategoryOrDefault(String raw) {
        if (raw == null || raw.isBlank()) return ItemCategory.TOOLS;
        try {
            return ItemCategory.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ItemCategory.TOOLS;
        }
    }

    private java.util.List<Path> discoverSBOFiles() {
        java.util.List<Path> paths = new java.util.ArrayList<>();
        try {
            // Filesystem first (dev launches from project root)
            Path fsPath = Path.of(SBO_RESOURCE_PATH);
            if (Files.isDirectory(fsPath)) {
                try (Stream<Path> stream = Files.list(fsPath)) {
                    stream.filter(p -> p.toString().toLowerCase().endsWith(".sbo")).forEach(paths::add);
                }
                if (!paths.isEmpty()) return paths;
            }

            var classLoader = getClass().getClassLoader();
            var resourceUrl = classLoader.getResource(SBO_RESOURCE_PATH);
            if (resourceUrl == null) {
                logger.debug("No item SBO folder found at: {}", SBO_RESOURCE_PATH);
                return paths;
            }
            var uri = resourceUrl.toURI();
            Path resourcePath;
            if ("jar".equals(uri.getScheme())) {
                FileSystem fs = FileSystems.newFileSystem(uri, Collections.emptyMap());
                resourcePath = fs.getPath(SBO_RESOURCE_PATH);
            } else {
                resourcePath = Path.of(uri);
            }
            try (Stream<Path> stream = Files.list(resourcePath)) {
                stream.filter(p -> p.toString().toLowerCase().endsWith(".sbo")).forEach(paths::add);
            }
        } catch (IOException | URISyntaxException e) {
            logger.error("Error discovering item SBO files", e);
        }
        return paths;
    }

    /**
     * Immutable record of one registered item.
     */
    public record ItemEntry(
            String objectId,
            String displayName,
            SBOFormat.GameProperties properties,
            SBOParseResult sboData
    ) {
        public int numericId() { return properties.numericId(); }
        public int atlasX() { return properties.atlasX(); }
        public int atlasY() { return properties.atlasY(); }
        public int maxStackSize() { return properties.maxStackSize(); }
        public String category() { return properties.categoryOrDefault(); }

        /** Raw OMT bytes for the item's default texture (decode with OMTReader). */
        public byte[] omtBytes() { return sboData.embeddedOmtBytes(); }

        /** True when this item declares one or more named states (1.3+). */
        public boolean hasStates() { return sboData.hasStates(); }

        /** Default state name (1.3+); null when {@link #hasStates()} is false. */
        public String defaultState() { return sboData.defaultStateName(); }

        /** All declared state names in author-defined order; empty when no states. */
        public java.util.Set<String> stateNames() {
            return java.util.Collections.unmodifiableSet(sboData.stateOmtBytes().keySet());
        }

        /**
         * OMT bytes for a specific state, or the default OMT when {@code state}
         * is null/blank/unknown. Falls back to the legacy texture for items
         * without states.
         */
        public byte[] omtBytesFor(String state) {
            if (!hasStates() || state == null || state.isBlank()) return omtBytes();
            byte[] specific = sboData.stateOmtBytes().get(state);
            return specific != null ? specific : omtBytes();
        }
    }
}
