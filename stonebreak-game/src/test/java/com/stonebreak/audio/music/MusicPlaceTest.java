package com.stonebreak.audio.music;

import com.stonebreak.world.World;
import com.stonebreak.world.generation.NativeTerrain;
import com.stonebreak.world.generation.TerrainClosedException;
import com.stonebreak.world.generation.biomes.BiomeType;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The world-backed {@link MusicPlace}: lazy, memoized, and safe while the terrain shuts down. */
class MusicPlaceTest {

    @Test
    void aClosingTerrainIsNowhereAndNeverThrows() {
        // Leaving a world: the generator closes before the game stops counting as in a world (crash 2026-11-22).
        World world = mock(World.class);
        when(world.terrain()).thenThrow(new TerrainClosedException("generator closed"));
        when(world.surfaceY(anyLong(), anyLong())).thenThrow(new TerrainClosedException("generator closed"));
        MusicPlace place = MusicPlace.of(world, new Vector3d(1, 70, 1));
        assertNull(place.biome());
        assertFalse(place.onSurface());

        NativeTerrain terrain = mock(NativeTerrain.class);
        when(terrain.getBiomeAt(anyInt(), anyInt())).thenThrow(new TerrainClosedException("generator closed"));
        World open = mock(World.class);
        when(open.terrain()).thenReturn(terrain);
        assertNull(MusicPlace.of(open, new Vector3d(1, 70, 1)).biome());
    }

    @Test
    void surfaceAllowsAShallowDipButNotACave() {
        World world = mock(World.class);
        when(world.surfaceY(anyLong(), anyLong())).thenReturn(64L);
        assertTrue(MusicPlace.of(world, new Vector3d(0.5, 64, 0.5)).onSurface());
        assertTrue(MusicPlace.of(world, new Vector3d(0.5, 64 - MusicPlace.SURFACE_TOLERANCE, 0.5)).onSurface());
        assertFalse(MusicPlace.of(world, new Vector3d(0.5, 50, 0.5)).onSurface());
    }

    @Test
    void answersAreReadOnce() {
        NativeTerrain terrain = mock(NativeTerrain.class);
        when(terrain.getBiomeAt(anyInt(), anyInt())).thenReturn(BiomeType.SHADE);
        World world = mock(World.class);
        when(world.terrain()).thenReturn(terrain);
        when(world.surfaceY(anyLong(), anyLong())).thenReturn(64L);
        MusicPlace place = MusicPlace.of(world, new Vector3d(-0.5, 70, 3.2));
        for (int i = 0; i < 3; i++) {
            assertEquals(BiomeType.SHADE, place.biome());
            assertTrue(place.onSurface());
        }
        verify(terrain, times(1)).getBiomeAt(-1, 3);
        verify(world, times(1)).surfaceY(-1L, 3L);
    }
}
