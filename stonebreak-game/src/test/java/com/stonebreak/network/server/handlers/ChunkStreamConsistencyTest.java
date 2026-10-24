package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.features.FeatureQueue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Server-side chunk bookkeeping: a chunk streams only once generation is done writing into it
 * (#365), the client's unload reports clear the sent-record (#363), and a chunk resync refused
 * over budget is kept for a retry instead of being lost (#363).
 */
@Tag("regression")
class ChunkStreamConsistencyTest {

    private final Map<Long, Chunk> chunks = new HashMap<>();

    private Chunk chunk(int cx, int cz, boolean populated) {
        Chunk c = mock(Chunk.class);
        when(c.areFeaturesPopulated()).thenReturn(populated);
        chunks.put(ServerChunkHandler.packKey(cx, cz), c);
        return c;
    }

    private World world(FeatureQueue queue) {
        World world = mock(World.class);
        when(world.getChunkIfLoaded(anyInt(), anyInt())).thenAnswer(inv ->
            chunks.get(ServerChunkHandler.packKey(inv.getArgument(0), inv.getArgument(1))));
        when(world.getFeatureQueue()).thenReturn(queue);
        return world;
    }

    private Chunk populatedNeighbourhood() {
        Chunk centre = null;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                Chunk c = chunk(dx, dz, true);
                if (dx == 0 && dz == 0) {
                    centre = c;
                }
            }
        }
        return centre;
    }

    @Test
    void streamsOnlyOnceEveryNeighbourIsPopulated() {
        Chunk centre = populatedNeighbourhood();
        World world = world(new FeatureQueue());
        assertTrue(ServerChunkHandler.settledForStreaming(world, centre, 0, 0));

        // A west neighbour still unpopulated would plant trees into (0, 0) after the send.
        chunk(-1, 0, false);
        assertFalse(ServerChunkHandler.settledForStreaming(world, centre, 0, 0));

        chunks.remove(ServerChunkHandler.packKey(-1, 0));
        assertFalse(ServerChunkHandler.settledForStreaming(world, centre, 0, 0), "missing neighbour");
    }

    @Test
    void unpopulatedChunkNeverStreams() {
        populatedNeighbourhood();
        Chunk centre = chunk(0, 0, false);
        assertFalse(ServerChunkHandler.settledForStreaming(world(new FeatureQueue()), centre, 0, 0));
    }

    @Test
    void queuedFeatureHoldsTheChunkBack() {
        Chunk centre = populatedNeighbourhood();
        FeatureQueue queue = mock(FeatureQueue.class);
        when(queue.hasPendingFor(0, 0)).thenReturn(true);
        assertFalse(ServerChunkHandler.settledForStreaming(world(queue), centre, 0, 0));
    }

    @Test
    void clientUnloadForgetsTheSentRecord() {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 1);
        long a = ServerChunkHandler.packKey(3, -4);
        long b = ServerChunkHandler.packKey(-7, 9);
        long kept = ServerChunkHandler.packKey(0, 0);
        sp.markChunkSent(a, 2);
        sp.markChunkSent(b, 0);
        sp.markChunkSent(kept, 1);

        new ServerChunkHandler().handleChunksUnloaded(sp, new int[]{3, -4, -7, 9});

        assertEquals(-1, sp.sentChunkVersion(a));
        assertEquals(-1, sp.sentChunkVersion(b));
        assertEquals(1, sp.sentChunkVersion(kept));
    }

    @Test
    void unloadInsideTheServersViewRearmsTheScan() {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 1);
        sp.updateState(8f, 64f, 8f, 0f, 0f);
        ServerChunkHandler handler = new ServerChunkHandler();
        int far = sp.viewDistanceChunks() + 3;

        clearScan(sp);
        handler.handleChunksUnloaded(sp, new int[]{far, 0});
        assertFalse(sp.viewScanPending(), "a chunk beyond the view re-streams when the player returns");

        handler.handleChunksUnloaded(sp, new int[]{1, 0});
        assertTrue(sp.viewScanPending(), "a chunk still in view must re-stream now");
    }

    private static void clearScan(ServerPlayer sp) {
        if (sp.viewScanPending()) {
            sp.clearViewScanPending();
        }
    }

    @Test
    void resyncRefusedOverBudgetIsRetained() {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 1);
        ServerChunkHandler handler = new ServerChunkHandler();
        while (sp.allowResync()) {
            // spend the window's permits
        }
        long key = ServerChunkHandler.packKey(5, 5);
        sp.markChunkSent(key, 0);

        handler.handleResyncRequest(sp, 5, 5);
        assertEquals(0, sp.sentChunkVersion(key), "over budget: not honoured yet");

        java.util.List<Long> drained = new java.util.ArrayList<>();
        sp.drainDeferredChunkResyncs(drained::add);
        assertTrue(drained.isEmpty(), "still over budget");

        // A fresh player has permits: whatever was deferred drains oldest first.
        ServerPlayer fresh = new ServerPlayer(mock(ServerConnection.class), 2);
        fresh.deferChunkResync(key);
        fresh.deferChunkResync(ServerChunkHandler.packKey(6, 6));
        fresh.drainDeferredChunkResyncs(drained::add);
        assertEquals(java.util.List.of(key, ServerChunkHandler.packKey(6, 6)), drained);
        drained.clear();
        fresh.drainDeferredChunkResyncs(drained::add);
        assertTrue(drained.isEmpty(), "each deferred resync is retried once");
    }
}
