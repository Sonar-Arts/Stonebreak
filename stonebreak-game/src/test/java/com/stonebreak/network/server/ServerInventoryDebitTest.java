package com.stonebreak.network.server;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.ItemDrop;
import com.stonebreak.network.packet.entity.ProjectileSpawnC2S;
import com.stonebreak.network.packet.player.DropItemC2S;
import com.stonebreak.network.packet.player.GiveItemS2C;
import com.stonebreak.network.packet.player.InventorySyncS2C;
import com.stonebreak.network.packet.world.BlockChangeC2S;
import com.stonebreak.network.packet.world.BlockChangeS2C;
import com.stonebreak.network.packet.world.BlockMetaS2C;
import com.stonebreak.network.packet.world.SnowLayerC2S;
import com.stonebreak.network.server.handlers.ServerBlockHandler;
import com.stonebreak.network.server.handlers.ServerChunkHandler;
import com.stonebreak.network.server.handlers.ServerEntityHandler;
import com.stonebreak.network.server.handlers.ServerPlayerHandler;
import com.stonebreak.world.SnowLayerManager;
import com.stonebreak.world.World;
import org.mockito.Mockito;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DropItemC2S is a DEBIT request (issue #334): the server spawns a drop only when its own
 * replica holds the stack, and every refusal syncs the sender instead of mutating.
 */
class ServerInventoryDebitTest {

    /** A non-spawnable context: no world, no entities — for the no-spawn refusal test. */
    private final ServerWorldContext ctx = Mockito.mock(ServerWorldContext.class);

    private final ServerPlayerHandler handler = new ServerPlayerHandler();

    private static ServerPlayer newPlayer() {
        return new ServerPlayer(mock(ServerConnection.class), 7);
    }

    /** A toss-ready player: real position reported, spawnable world/entities wired. */
    private static ServerPlayer tossReadyPlayer() {
        ServerPlayer sp = newPlayer();
        sp.updateState(10.5f, 65f, 10.5f, 0f, 0f);
        return sp;
    }

    private static List<Packet> sent(ServerPlayer sp) {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(sp.connection(), atLeastOnce()).send(captor.capture(), anyBoolean());
        return captor.getAllValues();
    }

    private static long syncCount(ServerPlayer sp) {
        return sent(sp).stream().filter(p -> p instanceof InventorySyncS2C).count();
    }

    @Test
    void dropRequestWithoutItemsRefusedAndSyncs() {
        ServerPlayer sp = tossReadyPlayer();
        sp.inventory().add(ItemType.STICK.getId(), 2);

        handler.handleDropItem(sp, new DropItemC2S(ItemType.WOODEN_PICKAXE.getId(), 1), fakes().ctx());

        assertEquals(2, sp.inventory().countOf(ItemType.STICK.getId()), "nothing was debited");
        assertEquals(0, sp.inventory().countOf(ItemType.WOODEN_PICKAXE.getId()));
        assertEquals(1, syncCount(sp), "exactly one refusal sync");
        assertTrue(sp.inventory().carried().isEmpty());
    }

    @Test
    void dropWithStockDebitsTheReplica() {
        ServerPlayer sp = tossReadyPlayer();
        sp.inventory().add(ItemType.STICK.getId(), 5);

        handler.handleDropItem(sp, new DropItemC2S(ItemType.STICK.getId(), 3), fakes().ctx());

        assertEquals(2, sp.inventory().countOf(ItemType.STICK.getId()), "the toss debited");
        assertTrue(syncCount(sp) >= 1, "the debit is synced back");
    }

    @Test
    void dropRateLimitRefusedAndSyncs() {
        ServerPlayer sp = tossReadyPlayer();
        sp.inventory().add(ItemType.STICK.getId(), 1000);

        for (int i = 0; i < 25; i++) {
            handler.handleDropItem(sp, new DropItemC2S(ItemType.STICK.getId(), 1), fakes().ctx());
        }

        assertEquals(980, sp.inventory().countOf(ItemType.STICK.getId()),
            "only the 20 in-window tosses debited; the rest were refused");
        assertEquals(25, syncCount(sp), "every request — accepted or refused — synced");
    }

    @Test
    void negativeCountRefusedWithoutDebit() {
        ServerPlayer sp = tossReadyPlayer();
        sp.inventory().add(ItemType.STICK.getId(), 5);

        handler.handleDropItem(sp, new DropItemC2S(ItemType.STICK.getId(), -4), fakes().ctx());

        assertEquals(5, sp.inventory().countOf(ItemType.STICK.getId()));
        assertEquals(1, syncCount(sp));
    }

    @Test
    void tossWithoutSpawnableWorldRefusedWithoutDebit() {
        // World/entities never wired (plain mock) and no state ever reported: the toss has
        // no spawn target, so the debit must not run — the stock survives intact.
        ServerPlayer sp = newPlayer();
        sp.inventory().add(ItemType.STICK.getId(), 5);

        handler.handleDropItem(sp, new DropItemC2S(ItemType.STICK.getId(), 3), ctx);

        assertEquals(5, sp.inventory().countOf(ItemType.STICK.getId()),
            "no debit without a spawn");
        assertEquals(1, syncCount(sp), "the refusal synced the sender");
    }

    // ─── Placement / snow / arrow debits ────────────────────────────────────────

    private record Fakes(ServerWorldContext ctx, World world, SnowLayerManager snow,
                         EntityManager em) {}

    private static Fakes fakes() {
        ServerWorldContext ctx = mock(ServerWorldContext.class);
        World world = mock(World.class);
        SnowLayerManager snow = new SnowLayerManager();
        EntityManager em = Mockito.spy(new EntityManager(world));
        when(ctx.world()).thenReturn(world);
        when(world.getSnowLayerManager()).thenReturn(snow);
        when(ctx.entityManager()).thenReturn(em);
        return new Fakes(ctx, world, snow, em);
    }

    @Test
    void placementWithoutItemRevertsAndSyncs() {
        Fakes f = fakes();
        when(f.world().getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(BlockType.AIR);
        ServerBlockHandler blocks = new ServerBlockHandler(new ServerChunkHandler());
        ServerPlayer sp = newPlayer();
        sp.updateState(10.5f, 65f, 10.5f, 0f, 0f);
        sp.inventory().add(ItemType.STICK.getId(), 2); // has items — just not the placed block

        blocks.handleBlockChange(sp, new BlockChangeC2S(10, 65, 10,
                (short) BlockType.STONE.getId(), (short) BlockType.AIR.getId()), f.ctx());

        assertEquals(2, sp.inventory().countOf(ItemType.STICK.getId()), "nothing was debited");
        assertEquals(0, sp.inventory().countOf(BlockType.STONE.getId()));
        assertTrue(syncCount(sp) >= 1, "the refusal synced the sender");
        assertTrue(sent(sp).stream().anyMatch(p -> p instanceof BlockChangeS2C b
                        && b.blockTypeId() == BlockType.AIR.getId()),
            "the optimistic placement was reverted");
    }

    @Test
    void torchPlacementDebitsTheTorchItemNotTheBlockId() {
        Fakes f = fakes();
        ServerBlockHandler blocks = new ServerBlockHandler(new ServerChunkHandler());
        ServerPlayer sp = newPlayer();
        sp.updateState(10.5f, 65f, 10.5f, 0f, 0f);
        com.stonebreak.items.Item torch = com.stonebreak.blocks.torch.TorchBlock.item();
        sp.inventory().add(torch.getId(), 2);

        blocks.handleBlockChange(sp, new BlockChangeC2S(10, 65, 10,
                (short) BlockType.TORCH_PLACED.getId(), (short) BlockType.AIR.getId()), f.ctx());

        assertEquals(1, sp.inventory().countOf(torch.getId()),
            "the torch ITEM was debited — its id differs from the placed block's");
    }

    @Test
    void waterPlacementFromBucketEmptiesOneBucketOfTheSelectedStack() {
        Fakes f = fakes();
        ServerBlockHandler blocks = new ServerBlockHandler(new ServerChunkHandler());
        ServerPlayer sp = newPlayer();
        sp.updateState(10.5f, 65f, 10.5f, 0f, 0f);
        sp.inventory().add(ItemType.WOODEN_BUCKET.getId(), 2, ItemType.BUCKET_STATE_WATER);
        sp.inventory().selectHotbarSlot(0);

        blocks.handleBlockChange(sp, new BlockChangeC2S(10, 65, 10,
                (short) BlockType.WATER.getId(), (short) BlockType.AIR.getId()), f.ctx());

        // One pour empties one bucket — BlockPlacer.handleWaterBucket's stack rule.
        assertEquals(1, countState(sp, ItemType.WOODEN_BUCKET.getId(), ItemType.BUCKET_STATE_WATER),
            "one water bucket remains");
        assertEquals(1, countState(sp, ItemType.WOODEN_BUCKET.getId(), ItemType.BUCKET_STATE_EMPTY),
            "exactly one bucket was emptied");
    }

    @Test
    void bucketScoopFillsExactlyOneBucket() {
        Fakes f = fakes();
        ServerBlockHandler blocks = new ServerBlockHandler(new ServerChunkHandler());
        ServerPlayer sp = newPlayer();
        sp.updateState(10.5f, 65f, 10.5f, 0f, 0f);
        sp.inventory().add(ItemType.WOODEN_BUCKET.getId(), 3, ItemType.BUCKET_STATE_EMPTY);
        sp.inventory().selectHotbarSlot(0);
        when(f.world().setBlockAt(anyInt(), anyInt(), anyInt(), any(), anyBoolean())).thenReturn(true);
        when(f.world().getBlockAt(10, 65, 10)).thenReturn(BlockType.WATER);

        blocks.handleBlockChange(sp, new BlockChangeC2S(10, 65, 10,
                (short) BlockType.AIR.getId(), (short) BlockType.WATER.getId()), f.ctx());

        assertEquals(2, countState(sp, ItemType.WOODEN_BUCKET.getId(), ItemType.BUCKET_STATE_EMPTY));
        assertEquals(1, countState(sp, ItemType.WOODEN_BUCKET.getId(), ItemType.BUCKET_STATE_WATER),
            "the scoop filled exactly one bucket");
    }

    @Test
    void scoopRequiresServerSideWaterNotAClientClaim() {
        Fakes f = fakes();
        ServerBlockHandler blocks = new ServerBlockHandler(new ServerChunkHandler());
        ServerPlayer sp = newPlayer();
        sp.updateState(10.5f, 65f, 10.5f, 0f, 0f);
        sp.inventory().add(ItemType.WOODEN_BUCKET.getId(), 3, ItemType.BUCKET_STATE_EMPTY);
        sp.inventory().selectHotbarSlot(0);
        when(f.world().setBlockAt(anyInt(), anyInt(), anyInt(), any(), anyBoolean())).thenReturn(true);
        // The server's own cell holds no water; the client claims prev=WATER anyway.
        when(f.world().getBlockAt(10, 65, 10)).thenReturn(BlockType.AIR);

        blocks.handleBlockChange(sp, new BlockChangeC2S(10, 65, 10,
                (short) BlockType.AIR.getId(), (short) BlockType.WATER.getId()), f.ctx());

        assertEquals(3, countState(sp, ItemType.WOODEN_BUCKET.getId(), ItemType.BUCKET_STATE_EMPTY),
            "a client-claimed prev=WATER over a dry server cell must not fill a bucket");
        assertEquals(0, countState(sp, ItemType.WOODEN_BUCKET.getId(), ItemType.BUCKET_STATE_WATER));
    }

    /** Count of {@code itemId} stacks carrying exactly {@code state} across all 36 slots. */
    private static long countState(ServerPlayer sp, int itemId, String state) {
        long n = 0;
        for (int i = 0; i < ServerInventory.TOTAL_SLOTS; i++) {
            ItemStack s = sp.inventory().slot(i);
            if (!s.isEmpty() && s.getBlockTypeId() == itemId
                    && java.util.Objects.equals(state, s.getState())) {
                n += s.getCount();
            }
        }
        return n;
    }

    @Test
    void snowWithoutSnowRefusedAndSyncs() {
        Fakes f = fakes();
        when(f.world().getBlockAt(10, 65, 10)).thenReturn(BlockType.SNOW);
        ServerBlockHandler blocks = new ServerBlockHandler(new ServerChunkHandler());
        ServerPlayer sp = newPlayer();
        sp.updateState(10.5f, 65f, 10.5f, 0f, 0f);

        blocks.handleSnowLayer(sp, new SnowLayerC2S(10, 65, 10, (byte) 3), f.ctx());

        assertEquals(1, f.snow().getSnowLayers(10, 65, 10),
            "the cell stayed at the untracked default — no layers were written");
        assertEquals(1, syncCount(sp), "the refusal synced the sender");
        assertTrue(sent(sp).stream().anyMatch(p -> p instanceof BlockMetaS2C),
            "the actual layer count was re-asserted so the optimistic edit reverts");
    }

    @Test
    void arrowWithoutArrowRefusedAndSyncs() {
        Fakes f = fakes();
        ServerEntityHandler entities = new ServerEntityHandler();
        ServerPlayer sp = newPlayer();
        sp.updateState(10.5f, 65f, 10.5f, 0f, 0f);

        entities.handleProjectileSpawn(sp, new ProjectileSpawnC2S(
                ProjectileSpawnC2S.KIND_ARROW, 11f, 65f, 11f, 1f, 0f, 0f, new float[0]), f.ctx());

        verify(f.em(), never()).spawnArrow(any(), any());
        assertEquals(1, syncCount(sp), "the refusal synced the sender");
    }

    @Test
    void giveOverflowRespawnedServerSide() {
        Fakes f = fakes();
        ServerPlayer sp = newPlayer();
        sp.updateState(10.5f, 65f, 10.5f, 0f, 0f);
        for (int i = 0; i < 36; i++) {
            sp.inventory().setSlot(i, new ItemStack(ItemType.STICK, 64)); // replica full
        }

        new IntegratedServer().giveToPlayer(sp, f.ctx(), ItemType.STICK.getId(), 10);
        sp.flushInventoryResync(); // pickups' syncs coalesce to one per tick

        assertTrue(sent(sp).stream().noneMatch(p -> p instanceof GiveItemS2C),
            "nothing fit, so the client is told it got nothing");
        verify(f.em()).addEntity(argThat(e -> e instanceof ItemDrop d
                && d.getStackCount() == 10));
        assertEquals(1, syncCount(sp), "the sync is the reconciliation point");
    }
}
