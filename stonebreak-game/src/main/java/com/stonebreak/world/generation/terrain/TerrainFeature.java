package com.stonebreak.world.generation.terrain;

import com.stonebreak.world.generation.biomes.BiomeType;

/**
 * Interface for 3D terrain features that remove blocks to create landmarks.
 *
 * Each implementation represents a specific type of terrain feature (caves, overhangs, arches, etc.)
 * and determines whether a block at a given position should be removed (made air).
 *
 * Design Principles:
 * - Single Responsibility: Each feature type is independent
 * - Open/Closed: Easy to add new feature types without modifying existing code
 * - Interface Segregation: Simple, focused interface
 *
 * Feature Examples:
 * - Surface overhangs (cliffs with dramatic ledges)
 * - Natural arches (rock formations with tunnels)
 * - Cave systems (underground caverns and tunnels)
 * - Floating islands (rare high-altitude landmasses)
 * - Ravines (vertical cuts through terrain)
 *
 * Implementation Requirements:
 * - Must be thread-safe (multiple chunks generate in parallel)
 * - Should be stateless (or use thread-safe state)
 * - Return true to remove block (create air), false to keep it solid
 */
public interface TerrainFeature {

    /**
     * Determines if a block should be removed to create this terrain feature.
     *
     * @param worldX        World X coordinate
     * @param y             World Y coordinate
     * @param worldZ        World Z coordinate
     * @param surfaceHeight Surface height from heightmap at this (x, z) position
     * @param biome         Biome type at this location
     * @return true if block should be removed (made air), false to keep solid
     */
    boolean shouldRemoveBlock(int worldX, int y, int worldZ, int surfaceHeight, BiomeType biome);

    /**
     * Gets the priority order for this feature.
     * Features with lower priority values are evaluated first.
     *
     * Recommended priorities:
     * - 100: Underground caves (deepest, evaluated first)
     * - 200: Natural arches (surface-level)
     * - 300: Surface overhangs (surface, rarest)
     *
     * @return Priority value (lower = evaluated first)
     */
    int getPriority();

    /**
     * Gets the name of this feature for debugging and logging.
     *
     * @return Feature name (e.g., "SurfaceOverhang", "CaveSystem")
     */
    String getFeatureName();
}
