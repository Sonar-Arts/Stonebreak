package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ConnectionRegistry;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.network.packet.world.BlockChangeC2S;
import com.stonebreak.network.packet.world.BlockChangeS2C;
import com.stonebreak.network.packet.world.BlockMetaS2C;
import com.stonebreak.network.packet.world.BlockStateS2C;
import com.stonebreak.network.packet.world.CubeDataS2C;
import com.stonebreak.network.server.ServerLevel;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.util.DropUtil;
import com.stonebreak.world.ServerMutationSinks;
import com.stonebreak.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
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
    /** Everything one client (the player) receives, in order: broadcasts and direct sends. */
    private final List<Packet> wire = new ArrayList<>();

    private ConnectionRegistry registry;
    private ServerWorldContext ctx;
    private ServerChunkHandler chunkHandler;
    private ServerBlockHandler blockHandler;
    private ServerPlayer player;

    private static String key(long x, long y, long z) {
        return x + "," + y + "," + z;
    }

    private static String key(org.mockito.invocation.InvocationOnMock inv) {
        return key(inv.getArgument(0, Long.class), inv.getArgument(1, Long.class), inv.getArgument(2, Long.class));
    }

    @BeforeEach
    void setUp() {
        World world = mock(World.class);
        when(world.getBlockAt(anyLong(), anyLong(), anyLong())).thenAnswer(inv -> blocks.getOrDefault(
                key(inv), BlockType.AIR));
        when(world.getBlockStateAt(anyLong(), anyLong(), anyLong())).thenAnswer(inv -> states.get(
                key(inv)));
        when(world.getWaterValueAt(anyLong(), anyLong(), anyLong())).thenAnswer(inv -> {
            String k = key(inv);
            return blocks.get(k) == BlockType.WATER ? water.getOrDefault(k, 0) : -1;
        });
        when(world.setBlockAt(anyLong(), anyLong(), anyLong(), any(BlockType.class), anyBoolean()))
                .thenAnswer(inv -> {
                    String k = key(inv);
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
        org.mockito.Mockito.doAnswer(inv -> wire.add(inv.getArgument(0)))
                .when(registry).broadcast(any(), anyBoolean());
        ctx = new ServerWorldContext(registry);
        ctx.setServerLevel(level);

        chunkHandler = new ServerChunkHandler();
        chunkHandler.onSessionStart();
        blockHandler = new ServerBlockHandler(chunkHandler);
        ServerConnection connection = mock(ServerConnection.class);
        when(connection.send(any(), anyBoolean())).thenAnswer(inv -> wire.add(inv.getArgument(0)));
        player = new ServerPlayer(connection, 7);
        player.updateState(0.5f, 64f, 0.5f, 0f, 0f);
        ctx.addPlayer(player);
        // Per-block batches only reach players holding the cube (#363, cube-keyed since #447).
        holdCube(0, 1, 0);
        holdCube(0, 2, 0);
    }

    private void holdCube(long cx, long cy, long cz) {
        player.cubeView().markSent(new com.openmason.engine.voxel.world.CubePos(cx, cy, cz), 0L, 0, 0);
    }

    private void doAnswerSetState(World world) {
        org.mockito.Mockito.doAnswer(inv -> {
            states.put(key(inv), inv.getArgument(3));
            return null;
        }).when(world).setBlockStateAt(anyLong(), anyLong(), anyLong(), any());
    }

    private void edit(int x, int y, int z, BlockType to, BlockType from) {
        if (to != BlockType.AIR) {
            // Placement debits the placer's server inventory (issue #334): hold the block.
            player.inventory().add(to.getId(), 1);
        }
        try (MockedStatic<DropUtil> drops = mockStatic(DropUtil.class)) {
            blockHandler.handleBlockChange(player,
                    new BlockChangeC2S(x, y, z, (short) to.getId(), (short) from.getId()), ctx);
        }
    }

    private List<Packet> flush() {
        wire.clear();
        blockHandler.tick(ctx);
        return List.copyOf(wire);
    }

    @Test
    void breakAndPlaceGoOutAsDeltasNotCubes() {
        blocks.put(key(2, 63, 3), BlockType.STONE);

        edit(2, 63, 3, BlockType.AIR, BlockType.STONE);
        edit(4, 63, 3, BlockType.STONE, BlockType.AIR);

        List<Packet> sent = flush();
        assertTrue(sent.stream().noneMatch(p -> p instanceof CubeDataS2C), "a per-block edit must not re-send the cube");
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
    void blockBatchesSkipPlayersThatNeverReceivedTheCube() {
        // Cube (1, 2, 0) was never sent to the player: the change rides its cube record instead,
        // so a client never fails an apply for a cube it does not hold (#363).
        player.updateState(31.5f, 64f, 2.5f, 0f, 0f); // on the border, both cells in reach
        edit(33, 64, 2, BlockType.STONE, BlockType.AIR);
        edit(30, 64, 2, BlockType.STONE, BlockType.AIR);
        assertEquals(BlockType.STONE, blocks.get(key(33, 64, 2)), "the edit itself is accepted");

        List<Packet> sent = flush();
        assertEquals(1, sent.size());
        BlockChangeS2C change = assertInstanceOf(BlockChangeS2C.class, sent.get(0));
        assertEquals(30, change.x(), "only the held cube's change goes out");
    }

    @Test
    void doorPlacementEchoesItsFacingAfterTheBlock() {
        edit(0, 64, 2, BlockType.OAK_DOOR, BlockType.AIR);

        List<Packet> sent = flush();
        assertInstanceOf(BlockChangeS2C.class, sent.get(0));
        BlockStateS2C echo = assertInstanceOf(BlockStateS2C.class, sent.get(1));
        assertEquals(states.get(key(0, 64, 2)), echo.state());
    }
}
