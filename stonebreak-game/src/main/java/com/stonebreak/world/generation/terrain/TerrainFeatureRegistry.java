package com.stonebreak.world.generation.terrain;

import com.stonebreak.world.generation.biomes.BiomeType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Registry and coordinator for terrain features.
 *
 * Manages a collection of terrain features (caves, overhangs, arches, etc.)
 * and evaluates them in priority order to determine if blocks should be removed.
 *
 * Design Patterns:
 * - **Registry Pattern**: Centralized registration of features
 * - **Strategy Pattern**: Each feature implements TerrainFeature interface
 * - **Chain of Responsibility**: Features evaluated in priority order
 *
 * Priority System:
 * Features are evaluated from lowest to highest priority number:
 * - Priority 100: Underground caves (deepest, most important)
 * - Priority 200: Natural arches (surface-level)
 * - Priority 300: Surface overhangs (surface, rarest)
 *
 * First match wins: If any feature returns true, block is removed.
 * This prevents conflicts and ensures consistent behavior.
 *
 * Extensibility:
 * New features can be easily added:
 * 1. Implement TerrainFeature interface
 * 2. Register via registerFeature()
 * 3. Set appropriate priority
 *
 * Example Usage:
 * <pre>
 * TerrainFeatureRegistry registry = new TerrainFeatureRegistry(seed);
 * registry.registerFeature(new CaveSystemFeature(seed));
 * registry.registerFeature(new NaturalArchFeature(seed));
 * registry.registerFeature(new SurfaceOverhangFeature(seed));
 *
 * boolean shouldRemove = registry.shouldRemoveBlock(x, y, z, surfaceHeight, biome);
 * </pre>
 */
public class TerrainFeatureRegistry {

    private final List<TerrainFeature> features;
    private final long seed;
    private boolean sorted;

    /**
     * Creates a new terrain feature registry.
     *
     * @param seed World seed for feature generation
     */
    public TerrainFeatureRegistry(long seed) {
        this.seed = seed;
        this.features = new ArrayList<>();
        this.sorted = false;
    }

    /**
     * Creates a registry with default features (caves, arches, overhangs).
     *
     * @param seed World seed
     * @return New registry with default features
     */
    public static TerrainFeatureRegistry withDefaults(long seed) {
        TerrainFeatureRegistry registry = new TerrainFeatureRegistry(seed);

        // Register default features in any order (will be sorted by priority)
        registry.registerFeature(CaveSystemFeature.withDefaults(seed));
        registry.registerFeature(NaturalArchFeature.withDefaults(seed));
        registry.registerFeature(SurfaceOverhangFeature.withDefaults(seed));

        return registry;
    }

    /**
     * Registers a new terrain feature.
     *
     * Features will be automatically sorted by priority after registration.
     *
     * @param feature Feature to register
     * @return This registry (for method chaining)
     */
    public TerrainFeatureRegistry registerFeature(TerrainFeature feature) {
        features.add(feature);
        sorted = false; // Mark as needing resort
        return this;
    }

    /**
     * Removes a feature by name.
     *
     * @param featureName Name of feature to remove
     * @return true if feature was found and removed
     */
    public boolean removeFeature(String featureName) {
        return features.removeIf(f -> f.getFeatureName().equals(featureName));
    }

    /**
     * Clears all registered features.
     */
    public void clearFeatures() {
        features.clear();
        sorted = false;
    }

    /**
     * Determines if a block should be removed by any registered feature.
     *
     * Features are evaluated in priority order (lowest first).
     * First feature that returns true causes block removal.
     *
     * Optimization: Returns immediately on first match (no unnecessary checks).
     *
     * @param worldX        World X coordinate
     * @param y             World Y coordinate
     * @param worldZ        World Z coordinate
     * @param surfaceHeight Surface height from heightmap
     * @param biome         Biome type at this location
     * @return true if any feature wants to remove this block
     */
    public boolean shouldRemoveBlock(int worldX, int y, int worldZ, int surfaceHeight, BiomeType biome) {
        // Ensure features are sorted by priority
        ensureSorted();

        // Check each feature in priority order
        for (TerrainFeature feature : features) {
            if (feature.shouldRemoveBlock(worldX, y, worldZ, surfaceHeight, biome)) {
                return true; // First match wins
            }
        }

        return false; // No feature wants to remove this block
    }

    /**
     * Gets the world seed used for feature generation.
     *
     * @return World seed
     */
    public long getSeed() {
        return seed;
    }

    /**
     * Gets the number of registered features.
     *
     * @return Feature count
     */
    public int getFeatureCount() {
        return features.size();
    }

    /**
     * Gets a list of all registered feature names.
     *
     * @return Feature names (in priority order)
     */
    public List<String> getFeatureNames() {
        ensureSorted();
        List<String> names = new ArrayList<>();
        for (TerrainFeature feature : features) {
            names.add(feature.getFeatureName());
        }
        return names;
    }

    /**
     * Checks if a feature is registered by name.
     *
     * @param featureName Name of feature to check
     * @return true if feature is registered
     */
    public boolean hasFeature(String featureName) {
        return features.stream().anyMatch(f -> f.getFeatureName().equals(featureName));
    }

    /**
     * Ensures features are sorted by priority (lowest first).
     * Only sorts if features were added/removed since last sort.
     */
    private void ensureSorted() {
        if (!sorted) {
            features.sort(Comparator.comparingInt(TerrainFeature::getPriority));
            sorted = true;
        }
    }

    /**
     * Gets debug information about registered features.
     *
     * @return Debug string with feature names and priorities
     */
    @Override
    public String toString() {
        ensureSorted();
        StringBuilder sb = new StringBuilder("TerrainFeatureRegistry[");
        for (int i = 0; i < features.size(); i++) {
            TerrainFeature feature = features.get(i);
            sb.append(feature.getFeatureName())
              .append(" (priority=")
              .append(feature.getPriority())
              .append(")");
            if (i < features.size() - 1) {
                sb.append(", ");
            }
        }
        sb.append("]");
        return sb.toString();
    }
}
