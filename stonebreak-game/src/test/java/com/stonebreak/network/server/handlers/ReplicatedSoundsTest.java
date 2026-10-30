package com.stonebreak.network.server.handlers;

import com.openmason.engine.format.sound.SoundData;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ConnectionRegistry;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ToolMiningRules;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.network.packet.player.PlayerStateFlags;
import com.stonebreak.network.packet.world.BlockBreakStartC2S;
import com.stonebreak.network.packet.world.BlockChangeC2S;
import com.stonebreak.network.packet.world.SoundEventS2C;
import com.stonebreak.network.server.ServerLevel;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.util.DropUtil;
import com.stonebreak.world.ServerMutationSinks;
import com.stonebreak.world.SnowLayerManager;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #344: a player's block sounds reach the other players near them. The server sends the
 * sound its own client played when it predicted the edit — break, place, and the mining hits
 * between a break's start and its finish — to everyone in hearing range but that player.
 */
class ReplicatedSoundsTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long MS = 1_000_000L;

    private final Map<String, BlockType> blocks = new HashMap<>();
    private ServerWorldContext ctx;
    private ServerBlockHandler handler;
    private ServerPlayer miner;
    private ServerPlayer neighbour;
    private ServerPlayer faraway;
    private MockedStatic<DropUtil> drops;
    private final long now = 10 * SECOND;

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    @BeforeEach
    void setUp() {
        World world = mock(World.class);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> blocks.getOrDefault(
                key(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)), BlockType.AIR));
        when(world.getChunkIfLoaded(anyInt(), anyInt())).thenAnswer(inv -> mock(Chunk.class));
        when(world.getWaterValueAt(anyInt(), anyInt(), anyInt())).thenReturn(-1);
        when(world.setBlockAt(anyInt(), anyInt(), anyInt(), any(BlockType.class), anyBoolean()))
                .thenAnswer(inv -> {
                    blocks.put(key(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)), inv.getArgument(3));
                    return true;
                });
        when(world.serverSinks()).thenReturn(new ServerMutationSinks());
        when(world.getSnowLayerManager()).thenReturn(new SnowLayerManager());

        ServerLevel level = mock(ServerLevel.class);
        when(level.world()).thenReturn(world);
        when(level.entityManager()).thenReturn(mock(EntityManager.class));
        ctx = new ServerWorldContext(mock(ConnectionRegistry.class));
        ctx.setServerLevel(level);
        ServerChunkHandler chunkHandler = new ServerChunkHandler();
        chunkHandler.onSessionStart();
        handler = new ServerBlockHandler(chunkHandler);

        miner = player(7, 0.5f);
        neighbour = player(8, 4.5f);
        faraway = player(9, 400f);
        drops = mockStatic(DropUtil.class);
    }

    @AfterEach
    void tearDown() {
        drops.close();
    }

    private ServerPlayer player(int id, float x) {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), id);
        sp.updateState(x, 64f, 0.5f, 0f, 0f);
        sp.markHandshakeDone();
        ctx.addPlayer(sp);
        return sp;
    }

    /** The sound events {@code sp}'s connection was handed, in order. */
    private static List<SoundEventS2C> soundsSentTo(ServerPlayer sp) {
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(sp.connection(), atLeast(0)).send(sent.capture(), anyBoolean());
        return sent.getAllValues().stream()
                .filter(SoundEventS2C.class::isInstance).map(SoundEventS2C.class::cast).toList();
    }

    private long stoneNs() {
        float s = ToolMiningRules.breakSeconds(miner.inventory().slot(miner.inventory().selectedHotbarSlot()),
                BlockType.STONE);
        assumeTrue(s > 1f, "stone must take a while to mine");
        return (long) (s * SECOND);
    }

    private void mining(boolean on) {
        miner.setMovementFlags((short) (on ? PlayerStateFlags.MINING : 0));
    }

    // ─── break / place ──────────────────────────────────────────────────────────

    @Test
    void aBreakIsHeardByNearbyPlayersButNotTheBreaker() {
        blocks.put(key(1, 64, 0), BlockType.STONE);
        handler.handleBreakStart(miner, new BlockBreakStartC2S(1, 64, 0), now);

        handler.handleBlockChange(miner, new BlockChangeC2S(1, 64, 0, (short) BlockType.AIR.getId(),
                (short) BlockType.STONE.getId()), ctx, now + stoneNs());

        assertEquals(BlockType.AIR, blocks.get(key(1, 64, 0)));
        assertEquals(List.of(SoundEventS2C.block(BlockType.STONE.getId(), SoundData.EVENT_BREAK, 1.5f, 64.5f, 0.5f)),
                soundsSentTo(neighbour));
        assertTrue(soundsSentTo(miner).isEmpty(), "the breaker's client played it already");
        assertTrue(soundsSentTo(faraway).isEmpty(), "out of hearing range");
    }

    @Test
    void anEditSoundsLikeWhatTheEditorsClientPlayed() {
        assertEquals(SoundEventS2C.block(BlockType.STONE.getId(), SoundData.EVENT_BREAK, 1.5f, 2.5f, 3.5f),
                ServerBlockHandler.editSound(1, 2, 3, BlockType.AIR, BlockType.STONE));
        assertEquals(SoundEventS2C.block(BlockType.DIRT.getId(), SoundData.EVENT_PLACE, 1.5f, 2.5f, 3.5f),
                ServerBlockHandler.editSound(1, 2, 3, BlockType.DIRT, BlockType.AIR));
        // Water is silent on the client: scoops, pours and flow resets.
        assertNull(ServerBlockHandler.editSound(1, 2, 3, BlockType.AIR, BlockType.WATER));
        assertNull(ServerBlockHandler.editSound(1, 2, 3, BlockType.WATER, BlockType.AIR));
        // Breaking a cell the server already held as air (a double break) makes no sound.
        assertNull(ServerBlockHandler.editSound(1, 2, 3, BlockType.AIR, BlockType.AIR));
    }

    @Test
    void anAddedSnowLayerIsHeardAsAPlacement() {
        blocks.put(key(1, 64, 0), BlockType.SNOW);
        miner.inventory().setSlot(miner.inventory().selectedHotbarSlot(),
                new com.stonebreak.items.ItemStack(BlockType.SNOW, 4));

        handler.handleSnowLayer(miner, new com.stonebreak.network.packet.world.SnowLayerC2S(1, 64, 0, (byte) 2), ctx);

        assertEquals(List.of(SoundEventS2C.block(BlockType.SNOW.getId(), SoundData.EVENT_PLACE, 1.5f, 64.5f, 0.5f)),
                soundsSentTo(neighbour));
        assertTrue(soundsSentTo(miner).isEmpty());
    }

    // ─── mining hits ────────────────────────────────────────────────────────────

    @Test
    void miningHitsPlayAtTheBreakersCadenceWhileTheyMine() {
        stoneNs();
        blocks.put(key(1, 64, 0), BlockType.STONE);
        handler.handleBreakStart(miner, new BlockBreakStartC2S(1, 64, 0), now);
        mining(true);

        handler.tick(ctx, now);                     // the starting hit
        handler.tick(ctx, now + 100 * MS);          // too soon for the next
        handler.tick(ctx, now + ServerBlockHandler.MINING_HIT_INTERVAL_NS);

        SoundEventS2C hit = SoundEventS2C.block(BlockType.STONE.getId(), SoundData.EVENT_HIT, 1.5f, 64.5f, 0.5f);
        assertEquals(List.of(hit, hit), soundsSentTo(neighbour));
        assertTrue(soundsSentTo(miner).isEmpty());
    }

    @Test
    void miningHitsStopWhenTheMinerLetsGo() {
        stoneNs();
        blocks.put(key(1, 64, 0), BlockType.STONE);
        handler.handleBreakStart(miner, new BlockBreakStartC2S(1, 64, 0), now);
        mining(true);
        handler.tick(ctx, now);

        mining(false);
        handler.tick(ctx, now + SECOND);

        assertEquals(1, soundsSentTo(neighbour).size());
    }

    @Test
    void miningHitsStopWhenTheBreakFinishes() {
        long stone = stoneNs();
        blocks.put(key(1, 64, 0), BlockType.STONE);
        handler.handleBreakStart(miner, new BlockBreakStartC2S(1, 64, 0), now);
        mining(true);
        handler.tick(ctx, now);

        handler.handleBlockChange(miner, new BlockChangeC2S(1, 64, 0, (short) BlockType.AIR.getId(),
                (short) BlockType.STONE.getId()), ctx, now + stone);
        handler.tick(ctx, now + stone + SECOND);

        List<SoundEventS2C> heard = soundsSentTo(neighbour);
        assertEquals(SoundData.EVENT_HIT, heard.get(0).event());
        assertEquals(SoundData.EVENT_BREAK, heard.get(heard.size() - 1).event());
        assertEquals(1, heard.stream().filter(s -> s.event().equals(SoundData.EVENT_HIT)).count(),
                "no hits after the finish (the flag lags it by a state report)");
    }

    @Test
    void miningWithoutAStartMakesNoHits() {
        blocks.put(key(1, 64, 0), BlockType.STONE);
        mining(true);

        handler.tick(ctx, now);

        assertTrue(soundsSentTo(neighbour).isEmpty());
    }
}
