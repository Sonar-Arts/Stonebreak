package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ConnectionRegistry;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.furnace.FurnaceStateRegistry;
import com.stonebreak.items.ItemStack;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.network.packet.player.InventorySyncS2C;
import com.stonebreak.network.packet.world.BlockChangeC2S;
import com.stonebreak.network.packet.world.BlockChangeS2C;
import com.stonebreak.network.packet.world.SnowLayerC2S;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #370 (and the client-trusted {@code prev} of #333 it depends on): two players editing
 * one block inside a round trip. The server's own cell decides — a placement only lands in air
 * or water, a break only removes the block the client saw, a cell that is already air drops
 * nothing, and a container leaving its cell always drops its contents exactly once.
 */
class ConcurrentBlockEditTest {

    private static final int X = 1, Y = 64, Z = 0;

    private final Map<String, BlockType> blocks = new HashMap<>();
    /** False = every chunk "not loaded yet": edits go to the retry queue. */
    private boolean chunksLoaded = true;

    private ServerWorldContext ctx;
    private ServerBlockHandler handler;
    private ServerPlayer player;
    private FurnaceStateRegistry furnaces;
    private SnowLayerManager snow;
    private MockedStatic<DropUtil> drops;

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    @BeforeEach
    void setUp() {
        World world = mock(World.class);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> chunksLoaded
                ? blocks.getOrDefault(key(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)), BlockType.AIR)
                : BlockType.AIR); // an unloaded chunk reads AIR, like World.getBlockAt
        when(world.getChunkIfLoaded(anyInt(), anyInt())).thenAnswer(inv -> chunksLoaded ? mock(Chunk.class) : null);
        when(world.getWaterValueAt(anyInt(), anyInt(), anyInt())).thenReturn(-1);
        when(world.setBlockAt(anyInt(), anyInt(), anyInt(), any(BlockType.class), anyBoolean()))
                .thenAnswer(inv -> {
                    if (!chunksLoaded) {
                        return false;
                    }
                    blocks.put(key(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)), inv.getArgument(3));
                    return true;
                });
        when(world.serverSinks()).thenReturn(new ServerMutationSinks());
        snow = new SnowLayerManager();
        when(world.getSnowLayerManager()).thenReturn(snow);
        furnaces = mock(FurnaceStateRegistry.class);
        when(world.getFurnaceRegistry()).thenReturn(furnaces);

        ServerLevel level = mock(ServerLevel.class);
        when(level.world()).thenReturn(world);
        when(level.entityManager()).thenReturn(mock(EntityManager.class));
        ctx = new ServerWorldContext(mock(ConnectionRegistry.class));
        ctx.setServerLevel(level);

        ServerChunkHandler chunkHandler = new ServerChunkHandler();
        chunkHandler.onSessionStart();
        handler = new ServerBlockHandler(chunkHandler);
        player = new ServerPlayer(mock(ServerConnection.class), 7);
        player.updateState(0.5f, 64f, 0.5f, 0f, 0f);
        drops = mockStatic(DropUtil.class);
    }

    @AfterEach
    void tearDown() {
        drops.close();
    }

    /** The server's arrival clock for the packets these tests send. */
    private long now = 1_000_000_000L;

    private void edit(BlockType to, BlockType clientSaw) {
        handler.handleBlockChange(player,
                new BlockChangeC2S(X, Y, Z, (short) to.getId(), (short) clientSaw.getId()), ctx, now);
    }

    /** A whole honest break (issue #333): the start, a minute of mining, then the finish. */
    private void mine(BlockType clientSaw) {
        handler.handleBreakStart(player, new com.stonebreak.network.packet.world.BlockBreakStartC2S(X, Y, Z), now);
        now += 60_000_000_000L;
        edit(BlockType.AIR, clientSaw);
    }

    private BlockType cell() {
        return blocks.getOrDefault(key(X, Y, Z), BlockType.AIR);
    }

    private List<Packet> sent() {
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(player.connection(), atLeast(0)).send(sent.capture(), anyBoolean());
        return sent.getAllValues();
    }

    private long syncs() {
        return sent().stream().filter(p -> p instanceof InventorySyncS2C).count();
    }

    /** The block the server echoed back to this placer for the edited cell, or null. */
    private BlockType revertedTo() {
        BlockType last = null;
        for (Packet p : sent()) {
            if (p instanceof BlockChangeS2C b && b.x() == X && b.y() == Y && b.z() == Z) {
                last = BlockType.getById(b.blockTypeId() & 0xFFFF);
            }
        }
        return last;
    }

    private void verifyDropsFor(BlockType broken, int times) {
        drops.verify(() -> DropUtil.handleBlockBroken(any(), any(), eq(broken), any(), anyInt(), any()),
                times(times));
    }

    // ─── 1. placements never overwrite ──────────────────────────────────────────

    @Test
    void placingIntoAnotherPlayersFreshBlockIsRefusedAndTakesNothing() {
        blocks.put(key(X, Y, Z), BlockType.STONE); // player A's block, not yet seen by B
        player.inventory().setSlot(0, new ItemStack(BlockType.DIRT, 5));

        edit(BlockType.DIRT, BlockType.AIR);

        assertEquals(BlockType.STONE, cell(), "A's block survives");
        assertEquals(5, player.inventory().slot(0).getCount(), "nothing debited");
        assertEquals(BlockType.STONE, revertedTo(), "the placer is shown the real block");
        assertTrue(syncs() >= 1, "the sync undoes the placer's predicted debit");
    }

    @Test
    void placingIntoWaterIsStillAllowed() {
        blocks.put(key(X, Y, Z), BlockType.WATER);
        player.inventory().setSlot(0, new ItemStack(BlockType.DIRT, 5));

        edit(BlockType.DIRT, BlockType.WATER);

        assertEquals(BlockType.DIRT, cell());
        assertEquals(4, player.inventory().slot(0).getCount());
    }

    @Test
    void aFurnaceCannotBeOverwrittenByAPlacement() {
        blocks.put(key(X, Y, Z), BlockType.FURNACE);
        player.inventory().setSlot(0, new ItemStack(BlockType.STONE, 5));

        edit(BlockType.STONE, BlockType.AIR);

        assertEquals(BlockType.FURNACE, cell());
        verify(furnaces, never()).onBlockBroken(any(), anyInt(), anyInt(), anyInt());
    }

    // ─── 2. breaks drop exactly once, from the server's block ──────────────────

    @Test
    void twoPlayersBreakingOneBlockDropItOnce() {
        blocks.put(key(X, Y, Z), BlockType.STONE);

        mine(BlockType.STONE); // player A
        edit(BlockType.AIR, BlockType.STONE); // player B, who had not seen A's break yet

        assertEquals(BlockType.AIR, cell());
        verifyDropsFor(BlockType.STONE, 1);
    }

    @Test
    void breakingLeavesTheServerAlreadyDecayedDropsNothing() {
        edit(BlockType.AIR, BlockType.LEAVES); // the server cell is already air

        drops.verify(() -> DropUtil.handleBlockBroken(any(), any(), any(), any(), anyInt(), any()), never());
    }

    @Test
    void breakingABlockSomeoneReplacedIsRefused() {
        blocks.put(key(X, Y, Z), BlockType.DIRT); // A broke the stone and placed dirt

        edit(BlockType.AIR, BlockType.STONE); // B was still mining the stone

        assertEquals(BlockType.DIRT, cell(), "A's dirt survives");
        assertEquals(BlockType.DIRT, revertedTo());
        drops.verify(() -> DropUtil.handleBlockBroken(any(), any(), any(), any(), anyInt(), any()), never());
    }

    @Test
    void aForgedPrevNeverChoosesTheDrop() {
        blocks.put(key(X, Y, Z), BlockType.STONE);

        edit(BlockType.AIR, BlockType.IRON_ORE); // #333: the client claims an ore

        assertEquals(BlockType.STONE, cell(), "refused");
        verifyDropsFor(BlockType.IRON_ORE, 0);
    }

    @Test
    void anUnknownPrevBreaksWhatTheServerHolds() {
        blocks.put(key(X, Y, Z), BlockType.STONE);

        mine(BlockType.AIR);

        assertEquals(BlockType.AIR, cell());
        verifyDropsFor(BlockType.STONE, 1);
    }

    // ─── 3. containers leave their cell exactly once ───────────────────────────

    @Test
    void aFurnaceBrokenTwiceDropsItsContentsOnce() {
        blocks.put(key(X, Y, Z), BlockType.FURNACE);

        mine(BlockType.FURNACE);
        edit(BlockType.AIR, BlockType.FURNACE);

        verify(furnaces, times(1)).onBlockBroken(any(), eq(X), eq(Y), eq(Z));
    }

    // ─── retry queue: the cell is checked once the chunk loads ──────────────────

    @Test
    void aPlacementWaitingForItsChunkIsRefundedWhenTheCellTurnsOutTaken() {
        player.inventory().setSlot(0, new ItemStack(BlockType.DIRT, 5));
        chunksLoaded = false;

        edit(BlockType.DIRT, BlockType.AIR);
        assertEquals(4, player.inventory().slot(0).getCount(), "debited on arrival");

        blocks.put(key(X, Y, Z), BlockType.STONE);
        chunksLoaded = true;
        handler.tick(ctx, now);

        assertEquals(BlockType.STONE, cell(), "the waiting placement did not overwrite");
        assertEquals(5, player.inventory().slot(0).getCount(), "refunded exactly");
        assertEquals(BlockType.STONE, revertedTo());
    }

    @Test
    void aBreakWaitingForItsChunkDropsTheServersBlock() {
        chunksLoaded = false;
        mine(BlockType.STONE);

        blocks.put(key(X, Y, Z), BlockType.STONE);
        chunksLoaded = true;
        handler.tick(ctx, now);

        assertEquals(BlockType.AIR, cell());
        verifyDropsFor(BlockType.STONE, 1);
    }

    // ─── snow layers race the same way ──────────────────────────────────────────

    @Test
    void aSnowLayerAnotherPlayerAddedFirstCostsNothingAndResyncs() {
        blocks.put(key(X, Y, Z), BlockType.SNOW);
        snow.setSnowLayers(X, Y, Z, 4); // A already raised 3 → 4
        player.inventory().setSlot(0, new ItemStack(BlockType.SNOW, 3));

        handler.handleSnowLayer(player, new SnowLayerC2S(X, Y, Z, (byte) 4), ctx); // B: 3 → 4 too

        assertEquals(4, snow.getSnowLayers(X, Y, Z));
        assertEquals(3, player.inventory().slot(0).getCount(), "nothing debited");
        assertEquals(1, syncs(), "B's mirror spent a SNOW the server never took");
    }

    @Test
    void theLastSnowLayerAutoSwitchesLikeAPlacement() {
        blocks.put(key(X, Y, Z), BlockType.SNOW);
        snow.setSnowLayers(X, Y, Z, 1);
        player.inventory().setSlot(0, new ItemStack(BlockType.SNOW, 1));
        player.inventory().setSlot(5, new ItemStack(BlockType.SNOW, 9));

        handler.handleSnowLayer(player, new SnowLayerC2S(X, Y, Z, (byte) 2), ctx);

        assertEquals(2, snow.getSnowLayers(X, Y, Z));
        assertEquals(9, player.inventory().slot(0).getCount(), "the slot-5 stack moved into the hand");
        assertTrue(player.inventory().slot(5).isEmpty());
    }
}
