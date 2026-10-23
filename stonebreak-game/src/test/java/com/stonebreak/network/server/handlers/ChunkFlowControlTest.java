package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.network.server.ServerPlayer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Chunk flow control (after Minecraft's chunk batching) and teleport-aware unload reports.
 * A client that stops acknowledging is not buried under snapshots it would later install in
 * one frame; a lost ack never stalls streaming for good; and chunks dropped after a teleport
 * are judged from where the client now stands, not the server's lagging sample of the old area.
 */
@Tag("regression")
class ChunkFlowControlTest {

    @Test
    void acknowledgedSnapshotsLeaveTheWindowAndSetTheRate() {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 1);
        for (int i = 0; i < 10; i++) {
            sp.onChunkStreamed();
        }
        assertEquals(10, sp.chunksInFlight());

        new ServerChunkHandler().handleChunkAck(sp, 4, 12f);
        assertEquals(6, sp.chunksInFlight());
        assertEquals(12f, sp.chunkRate());

        new ServerChunkHandler().handleChunkAck(sp, 50, 1000f);
        assertEquals(0, sp.chunksInFlight(), "never negative");
        assertTrue(sp.chunkRate() <= 64f, "a remote client's asked-for rate is capped");
    }

    @Test
    void aLostAckReopensTheWindowAfterTheWatchdog() {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 1);
        sp.onChunkStreamed();
        for (int i = 1; i < 100; i++) {
            assertFalse(sp.chunkAckOverdue(100));
        }
        assertTrue(sp.chunkAckOverdue(100));
        assertEquals(0, sp.chunksInFlight());
    }

    @Test
    void unloadAfterATeleportIsJudgedFromTheClientsPosition() {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 1);
        sp.updateState(8f, 64f, 8f, 0f, 0f); // the server still has the player in the old area
        ServerChunkHandler handler = new ServerChunkHandler();
        if (sp.viewScanPending()) {
            sp.clearViewScanPending();
        }

        // The client teleported 1000 chunks away and dropped the old area around (0, 0).
        handler.handleChunksUnloaded(sp, new int[]{0, 0, 1, 0}, 1000, 1000);
        assertFalse(sp.viewScanPending(), "the old area must not re-stream to a client that left it");

        handler.handleChunksUnloaded(sp, new int[]{1001, 1000}, 1000, 1000);
        assertTrue(sp.viewScanPending(), "a chunk in view of where the client stands re-streams");
    }
}
