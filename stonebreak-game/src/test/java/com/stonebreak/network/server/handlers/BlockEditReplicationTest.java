package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ConnectionRegistry;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.network.packet.world.BlockChangeC2S;
import com.stonebreak.network.packet.world.BlockChangeS2C;
import com.stonebreak.network.packet.world.BlockMetaS2C;
import com.stonebreak.network.packet.world.BlockStateS2C;
import com.stonebreak.network.packet.world.ChunkDataS2C;
import com.stonebreak.network.server.ServerLevel;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.util.DropUtil;
import com.stonebreak.world.ServerMutationSinks;
import com.stonebreak.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #362: an accepted block edit reaches clients as per-block packets only. It must not
 * bump the chunk version (which re-streams the whole chunk to every viewer and briefly undoes
 * the editor's newer predictions), so everything the old re-stream silently corrected now
 * has to ride the per-block feed: the water level of a bucket poured into flowing water, and
 * block-state echoes that must land after their block change.
 */
class BlockEditReplicationTest {

    private final Map<String, BlockType> blocks = new HashMap<>();
    private final Map<String, String> states = new HashMap<>();
    private final Map<String, Integer> water = new HashMap<>();

    private ConnectionRegistry registry;
    private ServerWorldContext ctx;
    private ServerChunkHandler chunkHandler;
    private ServerBlockHandler blockHandler;
    private ServerPlayer player;

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    @BeforeEach
    void setUp() {
        World world = mock(World.class);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> blocks.getOrDefault(
                key(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)), BlockType.AIR));
        when(world.getBlockStateAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> states.get(
                key(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2))));
        when(world.getWaterValueAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
            String k = key(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
            return blocks.get(k) == BlockType.WATER ? water.getOrDefault(k, 0) : -1;
        });
        when(world.setBlockAt(anyInt(), anyInt(), anyInt(), any(BlockType.class), anyBoolean()))
                .thenAnswer(inv -> {
                    String k = key(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
                    BlockType type = inv.getArgument(3);
                    if (blocks.getOrDefault(k, BlockType.AIR) != type) {
                        states.remove(k); // Chunk.setBlock drops the state on a type change
                        water.remove(k); // AIR clears the flow entry; new WATER is a source
                    }
                    blocks.put(k, type);
                    return true;
                });
        doAnswerSetState(world);
        when(world.serverSinks()).thenReturn(new ServerMutationSinks());

        ServerLevel level = mock(ServerLevel.class);
        when(level.world()).thenReturn(world);
        registry = mock(ConnectionRegistry.class);
        ctx = new ServerWorldContext(registry);
        ctx.setServerLevel(level);

        chunkHandler = new ServerChunkHandler();
        chunkHandler.onSessionStart();
        blockHandler = new ServerBlockHandler(chunkHandler);
        player = new ServerPlayer(mock(ServerConnection.class), 7);
        player.updateState(0.5f, 64f, 0.5f, 0f, 0f);
    }

    private void doAnswerSetState(World world) {
        org.mockito.Mockito.doAnswer(inv -> {
            states.put(key(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)), inv.getArgument(3));
            return null;
        }).when(world).setBlockStateAt(anyInt(), anyInt(), anyInt(), any());
    }

    private void edit(int x, int y, int z, BlockType to, BlockType from) {
        try (MockedStatic<DropUtil> drops = mockStatic(DropUtil.class)) {
            blockHandler.handleBlockChange(player,
                    new BlockChangeC2S(x, y, z, (short) to.getId(), (short) from.getId()), ctx);
        }
    }

    private List<Packet> flush() {
        blockHandler.tick(ctx);
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(registry, atLeastOnce()).broadcast(sent.capture(), anyBoolean());
        return sent.getAllValues();
    }

    @Test
    void breakAndPlaceDoNotBumpTheChunkVersion() {
        blocks.put(key(2, 63, 3), BlockType.STONE);

        edit(2, 63, 3, BlockType.AIR, BlockType.STONE);
        edit(4, 63, 3, BlockType.STONE, BlockType.AIR);

        assertEquals(0, chunkHandler.chunkVersion(0, 0), "a per-block edit must not re-stream the chunk");
        List<Packet> sent = flush();
        assertTrue(sent.stream().noneMatch(p -> p instanceof ChunkDataS2C));
        assertTrue(sent.stream().anyMatch(p -> p instanceof com.stonebreak.network.packet.world.MultiBlockChangeS2C
                || p instanceof BlockChangeS2C));
    }

    @Test
    void bucketIntoFlowingWaterSendsTheSourceLevelAfterTheBlockChange() {
        blocks.put(key(1, 63, 1), BlockType.WATER);
        water.put(key(1, 63, 1), 5); // flowing, level 5

        // BlockPlacer sends AIR then WATER; the section batch merges them into one WATER change
        // that a client whose cell is already WATER applies as a no-op.
        edit(1, 63, 1, BlockType.AIR, BlockType.WATER);
        edit(1, 63, 1, BlockType.WATER, BlockType.AIR);

        List<Packet> sent = flush();
        BlockChangeS2C change = assertInstanceOf(BlockChangeS2C.class, sent.get(0));
        assertEquals(BlockType.WATER.getId(), change.blockTypeId());
        BlockMetaS2C meta = assertInstanceOf(BlockMetaS2C.class, sent.get(1));
        assertEquals(BlockMetaS2C.KIND_WATER_LEVEL, meta.metaKind());
        int localPos = (1 << 8) | (15 << 4) | 1; // (1, 63 & 15, 1)
        assertArrayEquals(new int[] {localPos << 16}, meta.packed(), "level 0 = source");
    }

    @Test
    void stateEchoesFollowTheBlockBatchAndCarryTheCurrentState() {
        // A furnace/workbench listener fires during placement, before the block batch flushes.
        blocks.put(key(5, 64, 5), BlockType.WORKBENCH);
        states.put(key(5, 64, 5), "workbench:grid=");
        blockHandler.queueStateEcho(5, 64, 5);
        blockHandler.queueStateEcho(5, 64, 5); // queued twice → echoed once
        edit(6, 64, 5, BlockType.STONE, BlockType.AIR);
        // A cell whose block was broken after its echo was queued sends nothing.
        blockHandler.queueStateEcho(7, 64, 5);

        List<Packet> sent = flush();
        assertEquals(2, sent.size());
        assertInstanceOf(BlockChangeS2C.class, sent.get(0));
        BlockStateS2C echo = assertInstanceOf(BlockStateS2C.class, sent.get(1));
        assertEquals("workbench:grid=", echo.state());
        assertEquals(5, echo.x());
    }

    @Test
    void doorPlacementEchoesItsFacingAfterTheBlock() {
        edit(0, 64, 2, BlockType.OAK_DOOR, BlockType.AIR);

        List<Packet> sent = flush();
        assertInstanceOf(BlockChangeS2C.class, sent.get(0));
        BlockStateS2C echo = assertInstanceOf(BlockStateS2C.class, sent.get(1));
        assertEquals(states.get(key(0, 64, 2)), echo.state());
        assertEquals(0, chunkHandler.chunkVersion(0, 0));
    }
}
