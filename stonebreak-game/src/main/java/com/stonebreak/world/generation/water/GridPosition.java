package com.stonebreak.world.generation.water;

/**
 * Immutable grid coordinate record for water level grid.
 *
 * Used as HashMap keys for caching water levels at grid points.
 * Java records automatically provide equals(), hashCode(), and toString().
 *
 * @param gridX Grid X coordinate
 * @param gridZ Grid Z coordinate
 */
public record GridPosition(int gridX, int gridZ) {
}
