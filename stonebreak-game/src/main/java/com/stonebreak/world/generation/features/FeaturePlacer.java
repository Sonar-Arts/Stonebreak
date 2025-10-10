package com.stonebreak.world.generation.features;

import com.stonebreak.world.World;

/**
 * Functional interface for placing a feature in the world.
 * Used by FeatureQueue to defer placement until all required chunks exist.
 *
 * Example usage:
 * <pre>
 * FeaturePlacer treePlacer = (world) -> {
 *     TreeGenerator.generateElmTree(world, chunk, x, surfaceHeight, z, random, lock);
 * };
 * </pre>
 */
@FunctionalInterface
public interface FeaturePlacer {
    /**
     * Places the feature in the world.
     *
     * @param world The world to place the feature in
     * @throws Exception if placement fails
     */
    void place(World world) throws Exception;
}
