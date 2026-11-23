package com.stonebreak.audio.music;

import com.stonebreak.world.World;
import com.stonebreak.world.generation.TerrainClosedException;
import com.stonebreak.world.generation.TerrainGenerator;
import com.stonebreak.world.generation.biomes.BiomeType;
import org.joml.Vector3dc;

/**
 * Where the player is, as far as {@link MusicCue} conditions care. Each answer is asked at most once
 * per check and only when a cue needs it.
 */
public interface MusicPlace {

    /** Blocks below the column's sky surface that still count as "on the surface" (dips, water). */
    double SURFACE_TOLERANCE = 2.0;

    /** The biome under the player, or null when unknown. */
    BiomeType biome();

    /**
     * Feet no more than {@link #SURFACE_TOLERANCE} blocks below the column's sky surface
     * ({@link World#surfaceY}; leaves pass the sky, so standing under trees counts, a cave does not).
     */
    boolean onSurface();

    /**
     * The player's place in {@code world}, read lazily. A world whose terrain is shutting down is
     * nowhere (null biome, not on the surface): leaving a world closes the generator a frame or more
     * before the game stops counting as in a world, and this runs on the main thread, where an
     * escaping {@link TerrainClosedException} ends the game.
     */
    static MusicPlace of(World world, Vector3dc feet) {
        return new WorldPlace(world, feet);
    }

    /** Memoizing, teardown-safe view of one player position. */
    final class WorldPlace implements MusicPlace {
        private final World world;
        private final Vector3dc feet;
        private boolean biomeRead;
        private BiomeType biome;
        private Boolean onSurface;

        private WorldPlace(World world, Vector3dc feet) {
            this.world = world;
            this.feet = feet;
        }

        @Override
        public BiomeType biome() {
            if (!biomeRead) {
                biomeRead = true;
                biome = readBiome();
            }
            return biome;
        }

        @Override
        public boolean onSurface() {
            if (onSurface == null) onSurface = readSurface();
            return onSurface;
        }

        private BiomeType readBiome() {
            if (world == null || feet == null) return null;
            try {
                TerrainGenerator terrain = world.terrain();
                if (terrain == null) return null;
                return terrain.getBiomeAt((int) Math.floor(feet.x()), (int) Math.floor(feet.z()));
            } catch (TerrainClosedException e) {
                return null;
            }
        }

        private boolean readSurface() {
            if (world == null || feet == null) return false;
            try {
                long x = (long) Math.floor(feet.x());
                long z = (long) Math.floor(feet.z());
                return feet.y() >= world.surfaceY(x, z) - SURFACE_TOLERANCE;
            } catch (TerrainClosedException e) {
                return false;
            }
        }
    }
}
