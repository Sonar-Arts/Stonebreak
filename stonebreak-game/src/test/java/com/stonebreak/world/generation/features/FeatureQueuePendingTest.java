package com.stonebreak.world.generation.features;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.utils.ChunkPosition;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Issue #365: the server holds a chunk back from streaming while a queued cross-chunk feature
 * still has to be placed into it, so the queue must report pending chunks and must not keep a
 * feature whose placement failed (it would hold its chunks back forever).
 */
@Tag("regression")
class FeatureQueuePendingTest {

    private static QueuedFeature feature(FeaturePlacer placer) {
        return new QueuedFeature("tree", new BlockPos(15, 70, 0),
            Set.of(new ChunkPosition(0, 0), new ChunkPosition(1, 0)), placer);
    }

    @Test
    void pendingUntilPlacedIntoEveryRequiredChunk() {
        World world = mock(World.class);
        FeatureQueue queue = new FeatureQueue();
        queue.queueFeature(feature(w -> { }));

        assertTrue(queue.hasPendingFor(0, 0));
        assertTrue(queue.hasPendingFor(1, 0));
        assertFalse(queue.hasPendingFor(2, 0));

        // (1, 0) loads but (0, 0) is not resident: nothing placed, (0, 0) still waits.
        when(world.hasChunkAt(anyInt(), anyInt())).thenAnswer(inv -> (int) inv.getArgument(0) == 1);
        queue.processChunk(world, new ChunkPosition(1, 0));
        assertTrue(queue.hasPendingFor(0, 0));

        when(world.hasChunkAt(anyInt(), anyInt())).thenReturn(true);
        queue.processChunk(world, new ChunkPosition(0, 0));
        assertFalse(queue.hasPendingFor(0, 0));
        assertFalse(queue.hasPendingFor(1, 0));
    }

    @Test
    void failedPlacementIsDroppedFromEveryChunk() {
        World world = mock(World.class);
        when(world.hasChunkAt(anyInt(), anyInt())).thenReturn(true);
        FeatureQueue queue = new FeatureQueue();
        queue.queueFeature(feature(w -> { throw new IllegalStateException("boom"); }));

        queue.processChunk(world, new ChunkPosition(1, 0));

        assertFalse(queue.hasPendingFor(0, 0), "a failed feature must not hold (0, 0) back forever");
        assertFalse(queue.hasPendingFor(1, 0));
    }
}
