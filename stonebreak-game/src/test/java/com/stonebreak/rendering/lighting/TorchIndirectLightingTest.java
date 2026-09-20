package com.stonebreak.rendering.lighting;

import com.openmason.engine.voxel.cco.data.CcoChunkMetadata;
import com.openmason.engine.voxel.cco.data.CcoDirtyTracker;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TorchIndirectLightingTest {
    @Test void cachesStationaryLightButDetectsOpacityEditsAndChunkUnload() {
        World world = mock(World.class);
        Chunk chunk = mock(Chunk.class);
        CcoDirtyTracker dirty = new CcoDirtyTracker();
        when(world.getChunkIfLoaded(anyInt(), anyInt())).thenReturn(chunk);
        when(chunk.getCcoMetadata()).thenReturn(CcoChunkMetadata.forNewChunk(0, 0));
        when(chunk.getCcoDirtyTracker()).thenReturn(dirty);
        when(chunk.getBlock(anyInt(), anyInt(), anyInt())).thenReturn(BlockType.AIR);
        TorchIndirectLighting.Entry cache = new TorchIndirectLighting.Entry();
        Vector3f position = new Vector3f(-.2f, 64.6f, -16.2f);
        assertTrue(cache.update(world, position));
        clearInvocations(chunk);
        assertFalse(cache.update(world, position));
        verify(chunk, never()).getBlock(anyInt(), anyInt(), anyInt());
        dirty.markBlockChanged();
        assertFalse(cache.update(world, position), "opacity-neutral edits do not rebuild radiance");
        when(chunk.getBlock(anyInt(), eq(62), anyInt())).thenReturn(BlockType.DIRT);
        dirty.markBlockChanged();
        assertTrue(cache.update(world, position));
        assertTrue(java.util.stream.IntStream.range(0, cache.volume.data().length)
                .anyMatch(i -> cache.volume.data()[i] > 0), "new floor reflects light");
        dirty.clearAll();
        assertFalse(cache.update(world, position), "clearing save/remesh flags is not a lighting change");
        when(chunk.getBlock(anyInt(), eq(62), anyInt())).thenReturn(BlockType.AIR);
        dirty.markBlockChanged();
        assertTrue(cache.update(world, position), "another edit is visible even with identical metadata");
        for (float value : cache.volume.data()) assertEquals(0, value, "removed reflector leaves no stale light");
        assertFalse(cache.update(world, new Vector3f(position).add(.01f, 0, 0)), "sub-probe motion reuses indirect fill");
        assertTrue(cache.update(world, new Vector3f(position).add(.3f, 0, 0)), "moving held light updates");
        when(world.getChunkIfLoaded(anyInt(), anyInt())).thenReturn(null);
        assertTrue(cache.update(world, position));
        for (float value : cache.volume.data()) assertEquals(0, value);
        verify(world, never()).getChunkAt(anyInt(), anyInt());
    }
}
