package com.stonebreak.blocks.registry;

import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOParseResult;
import com.stonebreak.rendering.sbo.SBOBlockRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Authoritative auto-populated registry of game blocks, sourced from the SBO
 * files discovered by {@link SBOBlockRegistry}.
 *
 * <p>Indexed by both namespaced object ID (e.g. {@code "stonebreak:dirt"}) and
 * stable numeric ID (the value persisted in chunk save files). Both indices
 * are populated from the SBO manifest's {@link SBOFormat.GameProperties} block
 * (format 1.1+); 1.0 SBOs are skipped with a warning since they lack the
 * gameplay metadata needed to register.
 *
 * <p>This registry is the foundation for the eventual transition off the
 * hardcoded {@code BlockType} enum. During the transition both coexist:
 * {@code BlockType} remains the source of truth for static call sites, while
 * this registry is the source of truth for "what blocks exist" / "look up by
 * objectId" queries — and is what the eventual class-form {@code BlockType}
 * will read at static-init time.
 *
 * <p>Thread-safety: load/clear are not thread-safe; call once at startup.
 * Reads after load are safe for concurrent access.
 */
public final class BlockRegistry {

    private static final Logger logger = LoggerFactory.getLogger(BlockRegistry.class);
    private static final BlockRegistry INSTANCE = new BlockRegistry();

    public static BlockRegistry getInstance() {
        return INSTANCE;
    }

    private final Map<String, BlockEntry> byObjectId = new LinkedHashMap<>();
    private final Map<Integer, BlockEntry> byNumericId = new LinkedHashMap<>();
    private boolean loaded = false;

    private BlockRegistry() {}

    /**
     * Idempotent self-loader. First call triggers an SBO scan from the
     * classpath / dev filesystem; subsequent calls are no-ops. Safe to call
     * from any class initializer that needs registry contents.
     *
     * <p>This is what {@link com.stonebreak.blocks.BlockType}'s static
     * initializer calls so that the static fields can resolve their data
     * from the SBO files instead of hardcoded values.
     */
    public synchronized void ensureLoaded() {
        if (loaded) return;
        SBOBlockRegistry sbo = new SBOBlockRegistry();
        sbo.scanAndLoad();
        loadFrom(sbo);
    }

    /**
     * Populate the registry from a loaded {@link SBOBlockRegistry}. Any prior
     * contents are cleared first. SBOs without a {@code gameProperties} block
     * (legacy 1.0 files) are skipped — run {@code SBOMetadataBackfill} first.
     *
     * @return number of entries loaded
     */
    public synchronized int loadFrom(SBOBlockRegistry sboRegistry) {
        Objects.requireNonNull(sboRegistry, "sboRegistry");
        byObjectId.clear();
        byNumericId.clear();
        loaded = true;

        int loaded = 0;
        int skipped = 0;
        for (SBOParseResult result : sboRegistry.getAll()) {
            SBOFormat.GameProperties gp = result.manifest().gameProperties();
            if (gp == null) {
                logger.warn("Skipping legacy SBO without gameProperties: {} ({}). Run SBOMetadataBackfill.",
                        result.getObjectName(), result.getObjectId());
                skipped++;
                continue;
            }

            // Note: we don't promote into BlockType here. BlockType's own
            // static initializer reads from this registry to create instances
            // for both hardcoded constants (STONE, GRASS, ...) and SBO-only
            // blocks. Calling BlockType.register() from this loop would race
            // with that static-init flow.
            BlockEntry entry = new BlockEntry(
                    result.getObjectId(),
                    result.getObjectName(),
                    gp,
                    result
            );

            BlockEntry existingByObjectId = byObjectId.put(entry.objectId(), entry);
            if (existingByObjectId != null) {
                logger.warn("Duplicate SBO objectId '{}' — keeping last loaded", entry.objectId());
            }

            if (gp.numericId() >= 0) {
                BlockEntry existingByNumericId = byNumericId.put(gp.numericId(), entry);
                if (existingByNumericId != null && !existingByNumericId.objectId().equals(entry.objectId())) {
                    logger.error("Numeric ID collision: {} and {} both claim id={}. Save corruption risk.",
                            existingByNumericId.objectId(), entry.objectId(), gp.numericId());
                }
            } else {
                logger.warn("SBO {} has no numericId — chunk saves cannot reference it", entry.objectId());
            }

            loaded++;
        }

        logger.info("BlockRegistry: loaded {} entries ({} skipped)", loaded, skipped);
        return loaded;
    }

    /**
     * Look up a block by namespaced object ID (e.g. {@code "stonebreak:dirt"}).
     */
    public Optional<BlockEntry> get(String objectId) {
        return Optional.ofNullable(byObjectId.get(objectId));
    }

    /**
     * Look up a block by its stable numeric ID (the value in chunk save files).
     */
    public Optional<BlockEntry> getById(int numericId) {
        return Optional.ofNullable(byNumericId.get(numericId));
    }

    /**
     * All registered blocks in load order. Unmodifiable view.
     */
    public Collection<BlockEntry> all() {
        return Collections.unmodifiableCollection(byObjectId.values());
    }

    /**
     * Whether the registry contains an entry for this objectId.
     */
    public boolean contains(String objectId) {
        return byObjectId.containsKey(objectId);
    }

    /**
     * Number of registered blocks.
     */
    public int size() {
        return byObjectId.size();
    }

    /**
     * Convert a namespaced SBO objectId (e.g. {@code "stonebreak:red_sand"})
     * to the SCREAMING_CASE name used as the BlockType registry key
     * ({@code "RED_SAND"}). Drops the namespace prefix and uppercases the
     * remainder.
     */
    public static String sboNameToEnumName(String objectId) {
        int colon = objectId.indexOf(':');
        String local = colon >= 0 ? objectId.substring(colon + 1) : objectId;
        return local.toUpperCase();
    }

    /**
     * Immutable record of one registered block.
     *
     * @param objectId   namespaced ID, e.g. "stonebreak:dirt"
     * @param displayName human-readable name from the SBO manifest
     * @param properties full gameplay metadata block from the SBO
     * @param sboData    full SBO parse result (mesh, materials, textures)
     */
    public record BlockEntry(
            String objectId,
            String displayName,
            SBOFormat.GameProperties properties,
            SBOParseResult sboData
    ) {
        public int numericId() { return properties.numericId(); }
        public float hardness() { return properties.hardness(); }
        public boolean solid() { return properties.solid(); }
        public boolean breakable() { return properties.breakable(); }
        public int atlasX() { return properties.atlasX(); }
        public int atlasY() { return properties.atlasY(); }
        public boolean transparent() { return properties.transparent(); }
        public boolean flower() { return properties.flower(); }
        public boolean stackable() { return properties.stackable(); }
        public int maxStackSize() { return properties.maxStackSize(); }
        public boolean placeable() { return properties.placeable(); }
        public String renderLayer() { return properties.renderLayerOrDefault(); }
        public String category() { return properties.categoryOrDefault(); }
    }
}
