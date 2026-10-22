package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ConnectionRegistry;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.stairs.StairState;
import com.stonebreak.blocks.stalagmite.StalagmiteState;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.ItemDrop;
import com.stonebreak.network.packet.player.InventorySyncS2C;
import com.stonebreak.network.packet.world.BlockChangeC2S;
import com.stonebreak.network.packet.world.BlockToggleC2S;
import com.stonebreak.network.packet.world.SnowLayerC2S;
import com.stonebreak.network.server.ServerInventory;
import com.stonebreak.network.server.ServerLevel;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.util.DropUtil;
import com.stonebreak.world.ServerMutationSinks;
import com.stonebreak.world.SnowLayerManager;
import com.stonebreak.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #334 review findings on {@link ServerBlockHandler}: bucket conversions never lose or
 * mint a bucket, a bucket poured into flowing water is not scooped by its own AIR half, snow
 * layers cost one SNOW per layer, refused/expired placements refund exactly what they took,
 * and stalagmite growth costs a server-side water bucket.
 */
class BlockEditInventoryTest {

    private static final String WATER = ItemType.BUCKET_STATE_WATER;
    private static final String EMPTY = ItemType.BUCKET_STATE_EMPTY;

    private final Map<String, BlockType> blocks = new HashMap<>();
    private final Map<String, String> states = new HashMap<>();
    private final Map<String, Integer> water = new HashMap<>();
    /** False = every chunk "not loaded yet": edits go to the retry queue. */
    private boolean chunksLoaded = true;

    private ServerWorldContext ctx;
    private ServerLevel level;
    private EntityManager entities;
    private ServerBlockHandler handler;
    private ServerPlayer player;
    private SnowLayerManager snow;

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
                    if (!chunksLoaded) {
                        return false;
                    }
                    String k = key(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
                    BlockType type = inv.getArgument(3);
                    if (blocks.getOrDefault(k, BlockType.AIR) != type) {
                        states.remove(k);
                        water.remove(k); // AIR clears the flow entry; new WATER is a source
                    }
                    blocks.put(k, type);
                    return true;
                });
        org.mockito.Mockito.doAnswer(inv -> {
            states.put(key(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)), inv.getArgument(3));
            return null;
        }).when(world).setBlockStateAt(anyInt(), anyInt(), anyInt(), any());
        when(world.serverSinks()).thenReturn(new ServerMutationSinks());
        snow = new SnowLayerManager();
        when(world.getSnowLayerManager()).thenReturn(snow);

        level = mock(ServerLevel.class);
        entities = mock(EntityManager.class);
        when(level.world()).thenReturn(world);
        when(level.entityManager()).thenReturn(entities);
        ctx = new ServerWorldContext(mock(ConnectionRegistry.class));
        ctx.setServerLevel(level);

        ServerChunkHandler chunkHandler = new ServerChunkHandler();
        chunkHandler.onSessionStart();
        handler = new ServerBlockHandler(chunkHandler);
        player = new ServerPlayer(mock(ServerConnection.class), 7);
        player.updateState(0.5f, 64f, 0.5f, 0f, 0f);
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private void edit(int x, int y, int z, BlockType to, BlockType from) {
        try (MockedStatic<DropUtil> drops = mockStatic(DropUtil.class)) {
            handler.handleBlockChange(player,
                    new BlockChangeC2S(x, y, z, (short) to.getId(), (short) from.getId()), ctx);
        }
    }

    private void tick() {
        try (MockedStatic<DropUtil> drops = mockStatic(DropUtil.class)) {
            handler.tick(ctx);
        }
    }

    private ServerInventory inv() {
        return player.inventory();
    }

    private void bucketsAt(int slot, int count, String state) {
        inv().setSlot(slot, new ItemStack(ItemType.WOODEN_BUCKET, count, state));
        inv().selectHotbarSlot(slot);
    }

    /** Buckets carrying {@code state} across all 36 slots. */
    private int buckets(String state) {
        int n = 0;
        for (int i = 0; i < ServerInventory.TOTAL_SLOTS; i++) {
            ItemStack s = inv().slot(i);
            if (!s.isEmpty() && s.getBlockTypeId() == ItemType.WOODEN_BUCKET.getId()
                    && Objects.equals(state, s.getState())) {
                n += s.getCount();
            }
        }
        return n;
    }

    private void fillSlots(int fromInclusive, int toExclusive) {
        for (int i = fromInclusive; i < toExclusive; i++) {
            inv().setSlot(i, new ItemStack(BlockType.STONE, 64));
        }
    }

    private long syncs() {
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(player.connection(), atLeast(0)).send(sent.capture(), anyBoolean());
        List<Packet> all = sent.getAllValues();
        return all.stream().filter(p -> p instanceof InventorySyncS2C).count();
    }

    // ─── Finding 1: conversions never lose or mint ──────────────────────────────

    @Test
    void pouringALoneWaterBucketInHotbarZeroLeavesAnEmptyBucket() {
        bucketsAt(0, 1, WATER);

        edit(0, 64, 2, BlockType.WATER, BlockType.AIR);

        assertEquals(1, buckets(EMPTY), "the poured bucket stays, emptied");
        assertEquals(0, buckets(WATER));
    }

    @Test
    void pouringALoneBucketInALaterSlotBehindFullSlotsKeepsIt() {
        fillSlots(0, 4);
        bucketsAt(4, 1, WATER);

        edit(0, 64, 2, BlockType.WATER, BlockType.AIR);

        assertEquals(1, buckets(EMPTY));
        assertEquals(EMPTY, inv().slot(4).getState(), "converted in place in the selected slot");
    }

    @Test
    void pouringFromAStackOfTwoEmptiesExactlyOne() {
        bucketsAt(0, 2, WATER);

        edit(0, 64, 2, BlockType.WATER, BlockType.AIR);

        assertEquals(1, buckets(WATER), "the client empties one bucket per pour");
        assertEquals(1, buckets(EMPTY));
    }

    @Test
    void scoopingWithALoneEmptyBucketInHotbarZeroFillsIt() {
        bucketsAt(0, 1, EMPTY);
        blocks.put(key(0, 64, 2), BlockType.WATER); // a source (no flow entry)

        edit(0, 64, 2, BlockType.AIR, BlockType.WATER);

        assertEquals(1, buckets(WATER), "the scooped bucket stays, filled");
        assertEquals(0, buckets(EMPTY));
    }

    @Test
    void scoopingWithALoneBucketInALaterSlotKeepsIt() {
        fillSlots(0, 6);
        bucketsAt(6, 1, EMPTY);
        blocks.put(key(0, 64, 2), BlockType.WATER);

        edit(0, 64, 2, BlockType.AIR, BlockType.WATER);

        assertEquals(1, buckets(WATER));
        assertEquals(WATER, inv().slot(6).getState());
    }

    @Test
    void scoopingFromAStackOfTwoFillsExactlyOne() {
        bucketsAt(3, 2, EMPTY);
        blocks.put(key(0, 64, 2), BlockType.WATER);

        edit(0, 64, 2, BlockType.AIR, BlockType.WATER);

        assertEquals(1, buckets(WATER));
        assertEquals(1, buckets(EMPTY));
    }

    @Test
    void aConvertedBucketThatFitsNowhereDropsInsteadOfVanishing() {
        fillSlots(0, ServerInventory.TOTAL_SLOTS);
        bucketsAt(0, 2, WATER);

        edit(0, 64, 2, BlockType.WATER, BlockType.AIR);

        assertEquals(1, buckets(WATER));
        verify(entities).addEntity(argThat(e -> e instanceof ItemDrop d
                && d.getItemStack().getItem() == ItemType.WOODEN_BUCKET
                && EMPTY.equals(d.getItemStack().getState())));
    }

    // ─── Finding 2: flowing water, source-only scoops, retried scoops ───────────

    @Test
    void pouringIntoFlowingWaterIsNotScoopedByItsOwnAirHalf() {
        bucketsAt(0, 1, WATER);
        blocks.put(key(0, 64, 2), BlockType.WATER);
        water.put(key(0, 64, 2), 5); // flowing

        // BlockPlacer.handleWaterBucket sends AIR then WATER for a flowing cell.
        edit(0, 64, 2, BlockType.AIR, BlockType.WATER);
        edit(0, 64, 2, BlockType.WATER, BlockType.AIR);

        assertEquals(BlockType.WATER, blocks.get(key(0, 64, 2)), "the pour was accepted, not reverted");
        assertEquals(0, buckets(WATER));
        assertEquals(1, buckets(EMPTY), "the pour emptied the bucket");
        assertEquals(0, syncs(), "no refusal");
    }

    @Test
    void flowingWaterIsNeverScooped() {
        bucketsAt(0, 1, EMPTY);
        blocks.put(key(0, 64, 2), BlockType.WATER);
        water.put(key(0, 64, 2), 3);

        edit(0, 64, 2, BlockType.AIR, BlockType.WATER);

        assertEquals(0, buckets(WATER), "handleEmptyBucket refuses flowing water");
        assertEquals(1, buckets(EMPTY));
    }

    @Test
    void aWaterBucketDoesNotScoop() {
        bucketsAt(0, 1, WATER);
        blocks.put(key(0, 64, 2), BlockType.WATER); // source

        edit(0, 64, 2, BlockType.AIR, BlockType.WATER);

        assertEquals(1, buckets(WATER));
        assertEquals(0, buckets(EMPTY));
    }

    @Test
    void aScoopThatWaitedForItsChunkStillFillsTheBucket() {
        bucketsAt(0, 1, EMPTY);
        blocks.put(key(0, 64, 2), BlockType.WATER);
        chunksLoaded = false;

        edit(0, 64, 2, BlockType.AIR, BlockType.WATER);
        assertEquals(0, buckets(WATER), "not applied yet");

        chunksLoaded = true;
        tick();

        assertEquals(1, buckets(WATER), "the retry ran the scoop");
        assertEquals(0, buckets(EMPTY));
    }

    // ─── Finding 3: one SNOW per layer ──────────────────────────────────────────

    @Test
    void raisingSnowSeveralLayersCostsOneSnowPerLayer() {
        blocks.put(key(0, 64, 2), BlockType.SNOW);
        inv().add(BlockType.SNOW.getId(), 10);

        handler.handleSnowLayer(player, new SnowLayerC2S(0, 64, 2, (byte) 8), ctx);

        assertEquals(8, snow.getSnowLayers(0, 64, 2));
        assertEquals(3, inv().countOf(BlockType.SNOW.getId()), "1 → 8 layers costs 7 SNOW");
    }

    @Test
    void raisingSnowBeyondTheHeldSnowIsRefused() {
        blocks.put(key(0, 64, 2), BlockType.SNOW);
        inv().add(BlockType.SNOW.getId(), 1);

        handler.handleSnowLayer(player, new SnowLayerC2S(0, 64, 2, (byte) 8), ctx);

        assertEquals(1, snow.getSnowLayers(0, 64, 2), "nothing written");
        assertEquals(1, inv().countOf(BlockType.SNOW.getId()), "nothing debited");
        assertEquals(1, syncs());
    }

    // ─── Finding 4: refunds return exactly what the debit took ──────────────────

    @Test
    void aTorchRefusedForItsPlacementStateIsRefunded() {
        com.stonebreak.items.Item torch = com.stonebreak.blocks.torch.TorchBlock.item();
        inv().add(torch.getId(), 2);

        // No placement state ⇒ validatePlacement refuses after the debit.
        edit(0, 64, 2, BlockType.TORCH_PLACED, BlockType.AIR);

        assertEquals(BlockType.AIR, blocks.get(key(0, 64, 2)), "the cell was reverted");
        assertEquals(2, inv().countOf(torch.getId()), "the torch ITEM came back");
        assertEquals(0, inv().countOf(BlockType.TORCH_PLACED.getId()), "not the torch block");
        assertTrue(syncs() >= 1, "the refund was synced");
    }

    @Test
    void anExpiredBucketPourRefillsTheBucketInsteadOfGrantingAWaterBlock() {
        bucketsAt(0, 1, WATER);
        chunksLoaded = false;

        edit(0, 64, 2, BlockType.WATER, BlockType.AIR);
        assertEquals(1, buckets(EMPTY), "debited when validated");
        for (int i = 0; i < 25; i++) {
            tick();
        }

        assertEquals(1, buckets(WATER), "the bucket was refilled");
        assertEquals(0, buckets(EMPTY));
        assertEquals(0, inv().countOf(BlockType.WATER.getId()), "no WATER block minted");
        assertTrue(syncs() >= 1);
    }

    @Test
    void placementCostsABlockEvenWithCheatsAndCreativeSaveMode() {
        // Saves default to gameMode 1 and /obtain needs cheats on; the client always pays for
        // a placement, so a free one here left the replica full and the next sync (a toss,
        // an /obtain) handed every placed block back.
        when(level.cheatsEnabled()).thenReturn(true);
        player.setGameMode(1);
        inv().setSlot(0, new ItemStack(BlockType.STONE, 10));

        edit(0, 64, 2, BlockType.STONE, BlockType.AIR);

        assertEquals(9, inv().countOf(BlockType.STONE.getId()));
    }

    @Test
    void placingTheLastHeldBlockRefillsTheSelectedSlot() {
        // Mirrors Inventory.removeItem's auto-switch, so the next sync doesn't move the stack back.
        inv().setSlot(0, new ItemStack(BlockType.STONE, 1));
        inv().setSlot(12, new ItemStack(BlockType.STONE, 20));

        edit(0, 64, 2, BlockType.STONE, BlockType.AIR);

        assertEquals(20, inv().slot(0).getCount(), "the next stack moved into the held slot");
        assertTrue(inv().slot(12).isEmpty());
    }

    @Test
    void anExpiredRefundThatFitsNowhereDrops() {
        inv().setSlot(0, new ItemStack(BlockType.STONE, 1));
        chunksLoaded = false;

        edit(0, 64, 2, BlockType.STONE, BlockType.AIR);
        fillSlots(0, ServerInventory.TOTAL_SLOTS); // the inventory filled up meanwhile
        for (int i = 0; i < 25; i++) {
            tick();
        }

        verify(entities).addEntity(any());
    }

    // ─── Finding 5: stalagmite growth costs a server-side water bucket ──────────

    private void stalagmiteAt(int x, int y, int z) {
        blocks.put(key(x, y, z), BlockType.LIMESTONE_STALAGMITE);
        states.put(key(x, y, z), new StalagmiteState(1, false, StairState.Facing.SOUTH).toStateString());
    }

    @Test
    void growingAStalagmiteEmptiesTheWaterBucket() {
        stalagmiteAt(0, 64, 2);
        bucketsAt(0, 1, WATER);

        handler.handleBlockToggle(player, new BlockToggleC2S(0, 64, 2), ctx);

        assertEquals(BlockType.LIMESTONE_STALAGMITE, blocks.get(key(0, 65, 2)), "it grew");
        assertEquals(0, buckets(WATER));
        assertEquals(1, buckets(EMPTY));
    }

    @Test
    void oneBucketGrowsAStalagmiteOnlyOnce() {
        stalagmiteAt(0, 64, 2);
        bucketsAt(0, 1, WATER);

        handler.handleBlockToggle(player, new BlockToggleC2S(0, 64, 2), ctx);
        handler.handleBlockToggle(player, new BlockToggleC2S(0, 64, 2), ctx);

        assertEquals(BlockType.AIR, blocks.getOrDefault(key(0, 66, 2), BlockType.AIR),
                "the second watering had no water to pour");
        assertEquals(1, syncs(), "the refusal synced the client's emptied copy");
    }

    @Test
    void growingWithoutABucketIsRefused() {
        stalagmiteAt(0, 64, 2);

        handler.handleBlockToggle(player, new BlockToggleC2S(0, 64, 2), ctx);

        assertEquals(BlockType.AIR, blocks.getOrDefault(key(0, 65, 2), BlockType.AIR));
        assertEquals(1, syncs());
        verify(entities, never()).addEntity(any());
    }
}
