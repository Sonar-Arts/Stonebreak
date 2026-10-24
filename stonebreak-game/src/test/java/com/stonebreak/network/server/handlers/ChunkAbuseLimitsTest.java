package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.utils.ChunkPosition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Issue #336, chunk side: a player's view can only start so many chunk loads/generations, fake
 * audit hashes spend resync permits, and a player with no position does not stop eviction.
 */
class ChunkAbuseLimitsTest {

    private static ServerPlayer reported(int id, float x, float z) {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), id);
        sp.markHandshakeDone();
        sp.updateState(x, 64f, z, 0f, 0f);
        return sp;
    }

    private static ServerWorldContext ctx(World world, ServerPlayer... players) {
        ServerWorldContext ctx = mock(ServerWorldContext.class);
        when(ctx.world()).thenReturn(world);
        when(ctx.players()).thenReturn(List.of(players));
        return ctx;
    }

    private static long chunkRequests(World world) {
        return mockingDetails(world).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("getChunkAt"))
                .count();
    }

    @Test
    void viewScanStartsAtMostTheBudgetOfNewChunkLoads() {
        World world = mock(World.class); // nothing resident, nothing in flight: every cell is new
        ServerPlayer sp = reported(1, 8f, 8f);
        ServerChunkHandler handler = new ServerChunkHandler();
        ServerWorldContext ctx = ctx(world, sp);

        // Each tick the unfinished scan asks again for its whole generation square
        // ((2·10+1)² = 441 cells at the default view); 20 ticks would be 8820 requests.
        for (int i = 0; i < 20; i++) {
            handler.tick(ctx);
        }

        long requests = chunkRequests(world);
        assertTrue(requests <= ServerPlayerBudget.burst() + 300,
                "chunk requests bounded by the budget, got " + requests);
        assertTrue(requests >= 441, "the first view is not held back");
    }

    @Test
    void requestsAlreadyInFlightAreFree() {
        World world = mock(World.class);
        when(world.isChunkLoadPending(anyInt(), anyInt())).thenReturn(true);
        ServerPlayer sp = reported(1, 8f, 8f);
        ServerChunkHandler handler = new ServerChunkHandler();
        ServerWorldContext ctx = ctx(world, sp);

        for (int i = 0; i < 20; i++) {
            handler.tick(ctx);
        }

        assertTrue(sp.allowChunkRequest(System.nanoTime()), "pending loads spent nothing");
    }

    @Test
    void fakeAuditHashesSpendResyncPermits() {
        World world = mock(World.class);
        Chunk chunk = mock(Chunk.class, withSettings().stubOnly());
        when(chunk.areFeaturesPopulated()).thenReturn(true);
        when(world.getChunkIfLoaded(anyInt(), anyInt())).thenReturn(chunk);
        ServerPlayer sp = reported(1, 8f, 8f);
        ServerChunkHandler handler = new ServerChunkHandler();

        // 40 in-view chunks, all reported with a hash that cannot match, plus one far away.
        int[] entries = new int[41 * 3];
        for (int i = 0; i < 40; i++) {
            entries[i * 3] = i % 8 - 4;
            entries[i * 3 + 1] = i / 8 - 2;
            entries[i * 3 + 2] = 12345;
            sp.markChunkSent(ServerChunkHandler.packKey(entries[i * 3], entries[i * 3 + 1]), 0);
        }
        entries[120] = 900;
        entries[121] = 900;
        entries[122] = 12345;
        sp.markChunkSent(ServerChunkHandler.packKey(900, 900), 0);

        handler.handleChunkHashes(sp, entries, ctx(world, sp));

        int forgotten = 0;
        for (int i = 0; i < 40; i++) {
            if (sp.sentChunkVersion(ServerChunkHandler.packKey(entries[i * 3], entries[i * 3 + 1])) < 0) {
                forgotten++;
            }
        }
        assertEquals(32, forgotten, "one resync permit per mismatch; the rest wait");
        assertEquals(0, sp.sentChunkVersion(ServerChunkHandler.packKey(900, 900)),
                "a chunk outside the player's view is not audited");
    }

    @Test
    void playerWithoutAPositionDoesNotStopEviction() {
        World world = mock(World.class);
        when(world.getLoadedChunkPositions()).thenReturn(Set.of(new ChunkPosition(500, 500)));
        ServerPlayer positioned = reported(1, 8f, 8f);
        ServerPlayer creatingCharacter = new ServerPlayer(mock(ServerConnection.class), 2);
        ServerChunkHandler handler = new ServerChunkHandler();
        ServerWorldContext ctx = ctx(world, positioned, creatingCharacter);

        for (int i = 0; i < 100; i++) { // one eviction period
            handler.tick(ctx);
        }

        verify(world).unloadChunk(500, 500);
    }

    @Test
    void idleServerKeepsItsChunks() {
        World world = mock(World.class);
        when(world.getLoadedChunkPositions()).thenReturn(Set.of(new ChunkPosition(500, 500)));
        ServerChunkHandler handler = new ServerChunkHandler();
        ServerWorldContext ctx = ctx(world, new ServerPlayer(mock(ServerConnection.class), 2));

        for (int i = 0; i < 100; i++) {
            handler.tick(ctx);
        }

        verify(world, never()).unloadChunk(anyInt(), anyInt());
        verify(world, atMost(0)).getChunkAt(anyInt(), anyInt());
    }

    /** The per-player chunk request burst (package-private in ServerPlayer's package). */
    private static final class ServerPlayerBudget {
        static int burst() {
            int max = com.stonebreak.world.operations.WorldConfiguration.MAX_RENDER_DISTANCE;
            int side = 2 * (max + 2) + 1;
            return side * side;
        }
    }
}
