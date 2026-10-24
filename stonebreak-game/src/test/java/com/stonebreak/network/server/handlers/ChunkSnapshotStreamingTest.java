package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.codec.ChunkBlockPayload;
import com.openmason.engine.net.server.ConnectionRegistry;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.network.packet.world.ChunkDataS2C;
import com.stonebreak.network.server.ServerLevel;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.world.SnowLayerManager;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Issue #345: the server ships each chunk as a copy-on-write storage snapshot. Every viewer of
 * a chunk at one block revision shares one snapshot (so a wire encodes it once), a resend of an
 * unchanged chunk reuses it, and a changed chunk gets a fresh one while the old snapshot keeps
 * the blocks it was taken with.
 */
@Tag("regression")
class ChunkSnapshotStreamingTest {

    private final Map<Long, Chunk> chunks = new HashMap<>();
    private final Map<ServerPlayer, List<ChunkDataS2C>> received = new HashMap<>();
    private ServerWorldContext ctx;
    private ServerChunkHandler handler;
    private ServerPlayer first;
    private ServerPlayer second;

    private Chunk chunkAt(int cx, int cz) {
        return chunks.computeIfAbsent(ServerChunkHandler.packKey(cx, cz), k -> {
            Chunk c = new Chunk(cx, cz);
            c.setBlock(1, 10, 1, BlockType.STONE);
            c.setFeaturesPopulated(true);
            return c;
        });
    }

    private ServerPlayer player(int id) {
        ServerConnection connection = mock(ServerConnection.class);
        when(connection.isWritable()).thenReturn(true);
        List<ChunkDataS2C> log = new ArrayList<>();
        when(connection.send(any(), anyBoolean())).thenAnswer(inv -> {
            Packet p = inv.getArgument(0);
            if (p instanceof ChunkDataS2C cd) {
                log.add(cd);
            }
            return true;
        });
        ServerPlayer sp = new ServerPlayer(connection, id);
        received.put(sp, log);
        sp.setViewDistanceChunks(WorldConfiguration.MIN_RENDER_DISTANCE);
        sp.updateState(8f, 64f, 8f, 0f, 0f);
        ctx.addPlayer(sp);
        return sp;
    }

    @BeforeEach
    void setUp() {
        World world = mock(World.class);
        when(world.getChunkAt(anyInt(), anyInt())).thenAnswer(inv -> chunkAt(inv.getArgument(0), inv.getArgument(1)));
        when(world.getChunkIfLoaded(anyInt(), anyInt())).thenAnswer(inv -> chunkAt(inv.getArgument(0), inv.getArgument(1)));
        when(world.getSnowLayerManager()).thenReturn(new SnowLayerManager());
        ServerLevel level = mock(ServerLevel.class);
        when(level.world()).thenReturn(world);
        ctx = new ServerWorldContext(mock(ConnectionRegistry.class));
        ctx.setServerLevel(level);
        handler = new ServerChunkHandler();
        handler.onSessionStart();
        first = player(1);
        second = player(2);
    }

    /** The latest snapshot of (cx, cz) a player received, ticking until it arrives. */
    private ChunkDataS2C latest(ServerPlayer sp, int cx, int cz, int sentBefore) {
        for (int tick = 0; tick < 200; tick++) {
            List<ChunkDataS2C> log = received.get(sp);
            for (int i = log.size() - 1; i >= sentBefore; i--) {
                if (log.get(i).chunkX() == cx && log.get(i).chunkZ() == cz) {
                    return log.get(i);
                }
            }
            handler.tick(ctx);
            for (ServerPlayer p : ctx.players()) {
                handler.handleChunkAck(p, received.get(p).size(), 64f); // keep the window open
            }
        }
        throw new AssertionError("chunk (" + cx + "," + cz + ") never streamed to player " + sp.playerId());
    }

    @Test
    void viewersShareOneSnapshotThatIsNeverEncodedInProcess() {
        ChunkDataS2C a = latest(first, 0, 0, 0);
        ChunkDataS2C b = latest(second, 0, 0, 0);

        assertSame(a.blocks(), b.blocks(), "one snapshot per chunk revision, whatever the viewers");
        assertNotNull(a.blocks().snapshot(), "a snapshot the local client installs as is");
        assertFalse(a.blocks().isEncoded(), "nothing encodes until a wire asks for bytes");
    }

    @Test
    void resendOfAnUnchangedChunkReusesItsSnapshot() {
        ChunkBlockPayload before = latest(first, 1, 0, 0).blocks();
        int sent = received.get(first).size();

        handler.handleResyncRequest(first, 1, 0);
        assertSame(before, latest(first, 1, 0, sent).blocks());
    }

    @Test
    void changedChunkGetsAFreshSnapshotAndTheOldOneKeepsItsBlocks() {
        ChunkBlockPayload before = latest(first, 0, 0, 0).blocks();
        int sent = received.get(first).size();

        chunkAt(0, 0).setBlock(2, 10, 2, BlockType.DIRT);
        handler.handleResyncRequest(first, 0, 0);
        ChunkBlockPayload after = latest(first, 0, 0, sent).blocks();

        assertNotSame(before, after);
        assertEquals(BlockType.AIR, before.snapshot().get(2, 10, 2), "the old snapshot is copy-on-write");
        assertEquals(BlockType.DIRT, after.snapshot().get(2, 10, 2));
        assertSame(after, handler.cachedBlocks(0, 0));
    }
}
