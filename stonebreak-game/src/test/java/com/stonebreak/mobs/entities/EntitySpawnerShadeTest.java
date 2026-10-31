package com.stonebreak.mobs.entities;

import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.TerrainGenerator;
import com.stonebreak.world.generation.biomes.BiomeType;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Animals graze everywhere but seldom wander into the Shade, which belongs to the Wisps. */
class EntitySpawnerShadeTest {

    /** A world whose biome is the Shade west of x=0 and plains east of it. */
    private static World halfShade() {
        World world = mock(World.class);
        TerrainGenerator terrain = mock(TerrainGenerator.class);
        when(terrain.getBiomeAt(anyInt(), anyInt())).thenAnswer(inv ->
                (int) inv.getArgument(0) < 0 ? BiomeType.SHADE : BiomeType.PLAINS);
        when(world.terrain()).thenReturn(terrain);
        return world;
    }

    @Test
    void packsSpawnAsBeforeOutsideTheShade() {
        assertEquals(1f, EntitySpawner.packChance(halfShade(), new Vector3f(40, 64, 0)));
    }

    @Test
    void packsAreRareInTheShade() {
        assertTrue(EntitySpawner.packChance(halfShade(), new Vector3f(-40, 64, 0)) <= 0.1f);
    }

    @Test
    void eachChunkIsAskedAboutOnceAcrossSweeps() {
        World world = halfShade();
        Chunk chunk = mock(Chunk.class);
        when(chunk.areFeaturesPopulated()).thenReturn(true);
        when(world.getChunkIfLoaded(anyInt(), anyInt())).thenReturn(chunk);
        EntitySpawner spawner = new EntitySpawner(world, mock(EntityManager.class));
        spawner.setSpawnAnchorSource(() -> List.of(new EntitySpawner.SpawnAnchor(new Vector3f(0, 64, 0), 2)));

        String first = spawner.getSpawnStats();
        String second = spawner.getSpawnStats();

        assertEquals(first, second);
        verify(world.terrain(), times(5 * 5)).getBiomeAt(anyInt(), anyInt());
    }
}
