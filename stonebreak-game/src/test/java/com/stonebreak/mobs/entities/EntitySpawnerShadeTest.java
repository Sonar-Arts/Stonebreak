package com.stonebreak.mobs.entities;

import com.stonebreak.world.World;
import com.stonebreak.world.generation.NativeTerrain;
import com.stonebreak.world.generation.biomes.BiomeType;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Animals graze everywhere but seldom wander into the Shade, which belongs to the Vestiges. */
class EntitySpawnerShadeTest {

    /** A world whose biome is the Shade west of x=0 and plains east of it. */
    private static World halfShade() {
        World world = mock(World.class);
        NativeTerrain terrain = mock(NativeTerrain.class);
        when(terrain.getBiomeAt(anyInt(), anyInt())).thenAnswer(inv ->
                (int) inv.getArgument(0) < 0 ? BiomeType.SHADE : BiomeType.PLAINS);
        when(world.terrain()).thenReturn(terrain);
        return world;
    }

    @Test
    void packsSpawnAsBeforeOutsideTheShade() {
        assertEquals(1f, EntitySpawner.packChance(halfShade(), new Vector3d(40, 64, 0)));
    }

    @Test
    void packsAreRareInTheShade() {
        assertTrue(EntitySpawner.packChance(halfShade(), new Vector3d(-40, 64, 0)) <= 0.1f);
    }

    @Test
    void eachChunkIsAskedAboutOnceAcrossSweeps() {
        World world = halfShade();
        when(world.isColumnReady(anyLong(), anyLong())).thenReturn(true);
        EntitySpawner spawner = new EntitySpawner(world, mock(EntityManager.class));
        spawner.setSpawnAnchorSource(() -> List.of(new EntitySpawner.SpawnAnchor(new Vector3d(0, 64, 0), 2)));

        String first = spawner.getSpawnStats();
        String second = spawner.getSpawnStats();

        assertEquals(first, second);
        // A 2-chunk (32-block) view around the origin overlaps 3x3 cube columns (32x32), each asked once.
        verify(world.terrain(), times(3 * 3)).getBiomeAt(anyInt(), anyInt());
    }
}
