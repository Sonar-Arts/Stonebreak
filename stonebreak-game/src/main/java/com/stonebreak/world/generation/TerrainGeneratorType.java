package com.stonebreak.world.generation;

/**
 * Enumeration of available terrain generation systems.
 * <p>
 * Each type represents a different algorithm for generating terrain height.
 * The generator type is stored per-world in metadata and cannot be changed
 * after world creation (to prevent chunk border artifacts).
 */
public enum TerrainGeneratorType {
    /**
     * Spline-based multi-parameter terrain generation system.
     * <p>
     * Uses unified multi-dimensional spline interpolation inspired by Minecraft 1.18+:
     * <ul>
     *   <li>Single unified spline: height = f(continentalness, erosion, PV, weirdness)</li>
     *   <li>No terrain hints (all variety encoded in spline points)</li>
     *   <li>More flexible and expressive terrain shapes</li>
     * </ul>
     * <p>
     * Recommended for: Worlds requiring flexible terrain shapes.
     */
    SPLINE("Spline Generator", "Multi-parameter spline-based system (Minecraft 1.18+ style)"),

    /**
     * Hybrid SDF-Spline terrain generation system.
     * <p>
     * Combines spline-based base terrain with analytical SDF features:
     * <ul>
     *   <li>Base terrain: Fast spline interpolation (preserves existing optimizations)</li>
     *   <li>3D features: Analytical SDF primitives (caves, overhangs, arches)</li>
     *   <li>50-65% faster 3D chunk generation vs. SPLINE</li>
     *   <li>85-90% faster cave generation vs. noise-based approach</li>
     *   <li>More complex features: intricate cave systems, dramatic overhangs, natural arches</li>
     * </ul>
     * <p>
     * Recommended for: New worlds requiring high performance with complex terrain features (default).
     */
    HYBRID_SDF("Hybrid SDF Generator", "Spline terrain + SDF features (high performance, complex caves)");

    private final String displayName;
    private final String description;

    TerrainGeneratorType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    /**
     * Get the human-readable display name for this generator type.
     *
     * @return Display name (e.g., "Hybrid SDF Generator")
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Get a human-readable description of this generator type.
     *
     * @return Description explaining the generation approach and use cases
     */
    public String getDescription() {
        return description;
    }
}
