package com.stonebreak.world.save.serialization;

import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemStack;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerPlayerDataFactory;
import com.stonebreak.world.save.model.PlayerData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The persistence blob is SERVER-BUILT (issue #334): the position comes from the cached
 * {@code PlayerStateC2S}, the inventory from the replica, progression from the accepted
 * baseline — and a full save/load round trip restores all of it.
 */
class ServerPlayerDataBlobTest {

    @Test
    void serverBuiltBlobRestoresFullPlayer() {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 9);
        sp.setUsername("Tester");
        sp.updateState(12.5f, 70f, -3.25f, 90f, -15f);
        sp.setHealth(18.5f);
        sp.setGameMode(0);
        sp.inventory().add(BlockType.STONE.getId(), 12);
        sp.inventory().selectHotbarSlot(2);
        sp.acceptBaseline(PlayerData.builder()
                .level(4)
                .xp(30)
                .remainingCp(3)
                .remainingAp(7)
                .abilityScores(new int[]{13, 11, 10, 10, 12, 10})
                .skillLevels(java.util.Map.of("mining", 3))
                .acquiredFeatIds(java.util.Set.of("tough"))
                .spentAbilityCp(java.util.Map.of("warrior:0", 1))
                .statKillsByEntityType(java.util.Map.of("COW", 5L))
                .discoveredWeaknessEntityTypes(java.util.Set.of("cow"))
                .build());

        PlayerData built = ServerPlayerDataFactory.build(sp, "world-one");

        // Server constants: position from the cached state, world name from the level.
        assertEquals(12.5f, built.getPosition().x, 1e-5f);
        assertEquals(-3.25f, built.getPosition().z, 1e-5f);
        assertEquals("world-one", built.getWorldName());
        assertEquals(90f, built.getRotation().x, 1e-5f);
        // Validated mirror + replica.
        assertEquals(18.5f, built.getHealth(), 1e-5f);
        assertEquals(0, built.getGameMode());
        assertEquals(2, built.getSelectedHotbarSlot());
        assertEquals(12, built.getInventory()[0].getCount());
        // Accepted progression baseline.
        assertEquals(4, built.getLevel());
        assertEquals(3, built.getRemainingCp());
        assertEquals(13, built.getAbilityScores()[0]);

        // Full round trip through the shipped JSON format.
        JsonPlayerSerializer serializer = new JsonPlayerSerializer();
        PlayerData restored = serializer.deserialize(serializer.serialize(built));

        assertEquals(12.5f, restored.getPosition().x, 1e-5f);
        assertEquals("world-one", restored.getWorldName());
        assertEquals(18.5f, restored.getHealth(), 1e-5f);
        assertEquals(2, restored.getSelectedHotbarSlot());
        assertEquals(12, restored.getInventory()[0].getCount());
        assertEquals(BlockType.STONE.getId(), restored.getInventory()[0].getBlockTypeId());
        assertEquals(4, restored.getLevel());
        assertEquals(3, restored.getRemainingCp());
        assertEquals(7, restored.getRemainingAp());
        assertEquals(13, restored.getAbilityScores()[0]);
        assertEquals(3, restored.getSkillLevels().get("mining"));
        assertTrue(restored.getAcquiredFeatIds().contains("tough"));
        // Statistics and discoveries survive server-built saves too (reviewer note a).
        assertEquals(5L, restored.getStatKillsByEntityType().get("COW"));
        assertTrue(restored.getDiscoveredWeaknessEntityTypes().contains("cow"));
        // The client's own inventory/progression report can no longer erase any of this.
    }

    @Test
    void xpEarnedAfterTheLastPrefsCommitReachesTheSave() {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 9);
        sp.setUsername("Tester");
        sp.acceptBaseline(PlayerData.builder()
                .level(2)
                .xp(10)
                .remainingCp(1)
                .build());
        // Server-granted kill XP after the last accepted prefs commit — e.g. an abnormal
        // disconnect or a client that withholds PlayerPrefsC2S entirely.
        sp.serverStats().addXp(500);

        PlayerData built = ServerPlayerDataFactory.build(sp, "world-one");

        assertEquals(sp.serverStats().getLevel(), built.getLevel(),
            "server-granted XP must advance the persisted level");
        assertEquals(sp.serverStats().getXp(), built.getXp());
        assertEquals(sp.serverStats().getRemainingCp(), built.getRemainingCp(),
            "level-up point grants must reach the save blob");
        // The refreshed baseline is the new validation anchor for later prefs commits.
        assertEquals(sp.serverStats().getLevel(), sp.progressionBaseline().getLevel());
    }
}
