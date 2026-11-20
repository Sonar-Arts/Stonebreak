package com.stonebreak.world;

import com.stonebreak.world.generation.NativeTerrain;
import com.stonebreak.world.generation.TerrainGeneratorType;
import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;
import com.stonebreak.world.operations.WorldConfiguration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A singleplayer client view borrows the integrated server world's terrain (2026-11-20) instead of building a second
 * generator with its own tile caches - only from a world of the same terrain identity, and never closes it.
 */
class WorldTerrainShareTest {

    private static World view(long seed) {
        return World.createClientView(new WorldConfiguration(), seed, TerrainGeneratorType.STANDARD,
            TerrainScalePreset.ONE_TO_FOUR);
    }

    @Test
    void aViewBorrowsTheSameWorldsTerrainAndLeavesItOpen() {
        assumeTrue(NativeTerrain.isAvailable(), "needs the worldgen native library");
        World owner = view(42L);
        World viewer = view(42L);
        try {
            viewer.setTerrainSource(() -> owner);
            NativeTerrain shared = viewer.terrain();
            assertSame(owner.terrain(), shared, "one terrain for both");
            assertTrue(viewer.isTerrainBorrowed());
            int height = owner.terrain().getFinalTerrainHeightAt(100, 100);

            viewer.cleanup();
            // The borrower's cleanup must not close the owner's terrain.
            assertTrue(owner.terrain().getFinalTerrainHeightAt(100, 100) == height, "owner's terrain still answers");
        } finally {
            viewer.cleanup();
            owner.cleanup();
        }
    }

    @Test
    void aDifferentWorldIsNeverBorrowedFrom() {
        assumeTrue(NativeTerrain.isAvailable(), "needs the worldgen native library");
        World owner = view(42L);
        World other = view(43L);   // another seed: another terrain
        try {
            other.setTerrainSource(() -> owner);
            assertNotSame(owner.terrain(), other.terrain());
            assertFalse(other.isTerrainBorrowed());
        } finally {
            other.cleanup();
            owner.cleanup();
        }
    }

    @Test
    void aClosedSourceIsNotBorrowedFrom() {
        assumeTrue(NativeTerrain.isAvailable(), "needs the worldgen native library");
        World owner = view(42L);
        World viewer = view(42L);
        owner.cleanup();
        try {
            viewer.setTerrainSource(() -> owner);
            assertFalse(viewer.terrain() == null);
            assertFalse(viewer.isTerrainBorrowed(), "a cleaned-up source: the view builds its own");
        } finally {
            viewer.cleanup();
        }
    }
}
