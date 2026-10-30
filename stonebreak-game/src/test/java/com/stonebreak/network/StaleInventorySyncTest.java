package com.stonebreak.network;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.network.client.InventorySyncApplier;
import com.stonebreak.network.packet.SequencedIntent;
import com.stonebreak.network.packet.entity.ProjectileSpawnC2S;
import com.stonebreak.network.packet.player.ConsumeHeldC2S;
import com.stonebreak.network.packet.player.ContainerActionC2S;
import com.stonebreak.network.packet.player.DropItemC2S;
import com.stonebreak.network.packet.player.InventorySyncS2C;
import com.stonebreak.network.packet.world.BlockChangeC2S;
import com.stonebreak.network.packet.world.BlockToggleC2S;
import com.stonebreak.network.packet.world.SnowLayerC2S;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

/**
 * A placed block reappearing in the hotbar: the client spends the block at once, and an
 * {@code InventorySyncS2C} the server built before it saw the placement (a pickup, a refund,
 * someone else's refusal) still counts it. Every inventory-predicting intent is numbered, so
 * that sync is skipped instead of handing the block back; the client then asks for a fresh
 * sync, which the server builds after the placement and which brings the pickup along.
 */
class StaleInventorySyncTest {

    private static final int DIRT = BlockType.DIRT.getId();

    private static int count(Inventory inv, int itemId) {
        return inv.getItemCount(itemId);
    }

    private static List<InventorySyncS2C> syncsSent(ServerPlayer sp) {
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(sp.connection(), atLeast(0)).send(sent.capture(), anyBoolean());
        return sent.getAllValues().stream()
                .filter(p -> p instanceof InventorySyncS2C).map(p -> (InventorySyncS2C) p).toList();
    }

    @Test
    void aPickupSyncBuiltBeforeThePlacementDoesNotRefillTheBlock() {
        try (MockedStatic<Game> globals = mockStatic(Game.class)) {
            globals.when(Game::getInstance).thenReturn(mock(Game.class));
            SlotIntentSeq.markSessionStart();
            InventorySyncApplier.resetSession();
            Player client = new Player(mock(World.class));
            Inventory mirror = client.getInventory();
            ServerPlayer server = new ServerPlayer(mock(ServerConnection.class), 7);
            server.inventory().setSlot(0, new ItemStack(BlockType.DIRT, 10));

            // Join: the seeded sync applies.
            assertTrue(InventorySyncApplier.applyServerSync(client, server.inventorySync().payload(), 0));
            assertEquals(10, count(mirror, DIRT));

            // The client places one dirt: spent at once, intent numbered and in flight.
            mirror.removeItem(DIRT, 1);
            BlockChangeC2S placement = new BlockChangeC2S(3, 64, 0, (short) DIRT, (short) 0, "",
                    SlotIntentSeq.next());

            // Meanwhile the server gives a pickup and syncs — it has not seen the placement.
            server.inventory().add(ItemType.STICK.getId(), 1);
            InventorySyncS2C pickupSync = server.inventorySync();
            mirror.addItemAndReturnCount(new ItemStack(ItemType.STICK, 1)); // GiveItemS2C

            assertFalse(InventorySyncApplier.applyServerSync(client, pickupSync.payload(), pickupSync.ack()),
                    "the sync predates our placement");
            assertEquals(9, count(mirror, DIRT), "the placed dirt is not handed back");

            // The server processes the placement (routePlay records the number first), then the
            // client's InventoryResyncC2S, answered once after the drain.
            server.recordActionSeq(placement.seq());
            server.inventory().debitOneWithAutoSwitch(DIRT);
            server.requestInventoryResync();
            server.flushInventoryResync();

            List<InventorySyncS2C> syncs = syncsSent(server);
            InventorySyncS2C answer = syncs.get(syncs.size() - 1);
            assertEquals(placement.seq(), answer.ack());
            assertTrue(InventorySyncApplier.applyServerSync(client, answer.payload(), answer.ack()));
            assertEquals(9, count(mirror, DIRT));
            assertEquals(1, count(mirror, ItemType.STICK.getId()), "the pickup arrives with it");
        }
    }

    @Test
    void aResyncIsAnsweredOnceAndNotWhenASyncAlreadyWentOut() {
        ServerPlayer server = new ServerPlayer(mock(ServerConnection.class), 7);

        server.requestInventoryResync();
        server.requestInventoryResync();
        server.flushInventoryResync();
        server.flushInventoryResync();
        assertEquals(1, syncsSent(server).size(), "a burst of requests costs one sync");

        server.requestInventoryResync();
        server.send(server.inventorySync(), false); // a refusal answered it first
        server.flushInventoryResync();
        assertEquals(2, syncsSent(server).size());
    }

    @Test
    void onlyAnUnansweredNewestIntentAsksForAResync() {
        SlotIntentSeq.next(); // a placement: an accepted one is never answered with a sync
        assertTrue(SlotIntentSeq.skippedSyncNeedsResync());
        SlotIntentSeq.nextAnswered(); // a container click: its own answer is on the way
        assertFalse(SlotIntentSeq.skippedSyncNeedsResync());
        SlotIntentSeq.next();
        assertTrue(SlotIntentSeq.skippedSyncNeedsResync());
    }

    @Test
    void everyInventoryPredictingIntentIsNumbered() {
        List<Object> intents = List.of(
                new BlockChangeC2S(0, 0, 0, (short) 1, (short) 0, "", 1),
                new DropItemC2S(1, 1, 1),
                new SnowLayerC2S(0, 0, 0, (byte) 2, 1),
                new BlockToggleC2S(0, 0, 0, 1),
                new ProjectileSpawnC2S(ProjectileSpawnC2S.KIND_ARROW, 0, 0, 0, 0, 0, 0, new float[0], 1),
                new ConsumeHeldC2S(1, 1),
                new ContainerActionC2S(0, 0, 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0,
                        (byte) 0, (short) 0, false, 1));
        for (Object intent : intents) {
            assertEquals(1, assertInstanceOf(SequencedIntent.class, intent).seq(), intent.getClass().getSimpleName());
        }
    }
}
