package com.stonebreak.world.save.serialization;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemStack;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.player.PlayerStats;
import com.stonebreak.world.save.model.PlayerData;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JsonPlayerSerializerTest {

    private static final JsonPlayerSerializer serializer = new JsonPlayerSerializer();
    private static final LocalDateTime TIMESTAMP = LocalDateTime.of(2024, 3, 15, 10, 30, 45);

    @Test
    void roundTripPreservesEverything() {
        // Build inventory with non-default slots 0 and 1; rest are AIR defaults
        ItemStack[] inventory = new ItemStack[36];
        for (int i = 0; i < inventory.length; i++) {
            inventory[i] = new ItemStack(BlockType.AIR.getId(), 0);
        }
        inventory[0] = new ItemStack(BlockType.STONE.getId(), 5);
        inventory[1] = new ItemStack(BlockType.DIRT.getId(), 12);
        inventory[1].setState("lit");

        // Build PlayerStats with non-zero values
        PlayerStats ps = new PlayerStats();
        ps.restore(3, 12.5, 100.0, 60.0, 30.0, 10.0, 5.5);
        ps.restoreKillsByType(Collections.singletonMap(EntityType.COW, 3L));

        PlayerData original = PlayerData.builder()
            .position(new Vector3f(1.5f, 72, -3.25f))
            .rotation(new Vector2f(90, -15))
            .health(13.5f)
            .flightEnabled(true)
            .flying(true)
            .spectator(true)
            .gameMode(0)
            .selectedHotbarSlot(7)
            .inventory(inventory)
            .worldName("SaveWorld")
            .selectedClassId("ranger")
            .spentAbilityCp(Map.of("str", 2))
            .skillLevels(Map.of("mining", 3))
            .acquiredFeatIds(Set.of("featA", "featB"))
            .abilityScores(new int[]{8, 14, 12, 16, 10, 11})
            .remainingCp(5)
            .remainingSp(4)
            .remainingFp(3)
            .remainingAp(2)
            .level(7)
            .xp(900)
            .stats(ps)
            .discoveredVariantsByEntityType(Map.of("COW", Set.of("highland")))
            .discoveredWeaknessEntityTypes(Set.of("COW"))
            .lastSaved(TIMESTAMP)
            .build();

        byte[] bytes = serializer.serialize(original);
        PlayerData deserialized = serializer.deserialize(bytes);

        // Position and rotation
        assertEquals(new Vector3f(1.5f, 72, -3.25f), deserialized.getPosition());
        assertEquals(new Vector2f(90, -15), deserialized.getRotation());

        // Health and booleans
        assertEquals(13.5f, deserialized.getHealth());
        assertTrue(deserialized.isFlightEnabled());
        assertTrue(deserialized.isFlying());
        assertTrue(deserialized.isSpectator());

        // Game mode and hotbar
        assertEquals(0, deserialized.getGameMode());
        assertEquals(7, deserialized.getSelectedHotbarSlot());

        // Timestamp and world
        assertEquals(TIMESTAMP, deserialized.getLastSaved());
        assertEquals("SaveWorld", deserialized.getWorldName());

        // RPG / character progression
        assertEquals("ranger", deserialized.getSelectedClassId());
        assertEquals(Map.of("str", 2), deserialized.getSpentAbilityCp());
        assertEquals(Map.of("mining", 3), deserialized.getSkillLevels());
        assertEquals(Set.of("featA", "featB"), deserialized.getAcquiredFeatIds());

        // Ability scores
        assertArrayEquals(new int[]{8, 14, 12, 16, 10, 11}, deserialized.getAbilityScores());
        assertEquals(5, deserialized.getRemainingCp());
        assertEquals(4, deserialized.getRemainingSkillPoints());
        assertEquals(3, deserialized.getRemainingFeatPoints());
        assertEquals(2, deserialized.getRemainingAp());
        assertEquals(7, deserialized.getLevel());
        assertEquals(900, deserialized.getXp());

        // Inventory assertions
        ItemStack[] inv = deserialized.getInventory();
        assertEquals(BlockType.STONE.getId(), inv[0].getBlockTypeId());
        assertEquals(5, inv[0].getCount());
        assertEquals(BlockType.DIRT.getId(), inv[1].getBlockTypeId());
        assertEquals(12, inv[1].getCount());
        assertEquals("lit", inv[1].getState());
        assertEquals(BlockType.AIR.getId(), inv[5].getBlockTypeId());
        assertEquals(0, inv[5].getCount());

        // Stats
        assertEquals(3, deserialized.getStatEntitiesKilled());
        assertEquals(12.5, deserialized.getStatDamageDealt());
        assertEquals(100.0, deserialized.getStatTotalDistance());
        assertEquals(60.0, deserialized.getStatDistanceWalked());
        assertEquals(30.0, deserialized.getStatDistanceSprinted());
        assertEquals(10.0, deserialized.getStatDistanceInAir());
        assertEquals(5.5, deserialized.getStatTimeInAir());
        assertEquals(Map.of("COW", 3L), deserialized.getStatKillsByEntityType());

        // Discoveries
        assertEquals(Map.of("COW", Set.of("highland")), deserialized.getDiscoveredVariantsByEntityType());
        assertEquals(Set.of("COW"), deserialized.getDiscoveredWeaknessEntityTypes());
    }

    @Test
    void nullWorldNameIsOmittedAndReadsBackNull() {
        PlayerData original = PlayerData.builder()
            .position(new Vector3f(0, 100, 0))
            .rotation(new Vector2f(0, 0))
            .health(20f)
            .gameMode(1)
            .lastSaved(TIMESTAMP)
            .build();

        byte[] bytes = serializer.serialize(original);
        String json = new String(bytes, StandardCharsets.UTF_8);

        assertFalse(json.contains("\"worldName\""), "JSON must not contain worldName key when null");

        PlayerData deserialized = serializer.deserialize(bytes);
        assertNull(deserialized.getWorldName());
    }

    @Test
    void minimalLegacyJsonGetsDefaults() {
        String legacyJson = "{" +
            "\"position\": {\"x\": 0, \"y\": 100, \"z\": 0}," +
            "\"rotation\": {\"x\": 0, \"y\": 0}," +
            "\"health\": 20," +
            "\"lastSaved\": \"2024-03-15T10:30:45\"," +
            "\"inventory\": []" +
            "}";

        PlayerData result = serializer.deserialize(legacyJson.getBytes(StandardCharsets.UTF_8));

        assertEquals(new Vector3f(0, 100, 0), result.getPosition());
        assertEquals(new Vector2f(0, 0), result.getRotation());
        assertEquals(20.0f, result.getHealth());
        assertFalse(result.isFlightEnabled());
        assertFalse(result.isFlying());
        assertFalse(result.isSpectator());
        assertEquals(1, result.getGameMode());
        assertEquals(0, result.getSelectedHotbarSlot());
        assertNull(result.getWorldName());
        assertNull(result.getSelectedClassId());

        assertEquals(36, result.getInventory().length);
        assertEquals(BlockType.AIR.getId(), result.getInventory()[0].getBlockTypeId());
        assertEquals(0, result.getInventory()[0].getCount());

        assertEquals(100, result.getRemainingCp());
        assertEquals(100, result.getRemainingSkillPoints());
        assertEquals(100, result.getRemainingFeatPoints());
        assertArrayEquals(new int[]{10, 10, 10, 10, 10, 10}, result.getAbilityScores());
        assertEquals(27, result.getRemainingAp());
        assertEquals(1, result.getLevel());
        assertEquals(0, result.getXp());

        assertTrue(result.getSpentAbilityCp().isEmpty());
        assertTrue(result.getSkillLevels().isEmpty());
        assertTrue(result.getAcquiredFeatIds().isEmpty());

        assertEquals(0, result.getStatEntitiesKilled());
        assertEquals(0.0, result.getStatDamageDealt());
        assertEquals(0.0, result.getStatTotalDistance());
        assertEquals(0.0, result.getStatDistanceWalked());
        assertEquals(0.0, result.getStatDistanceSprinted());
        assertEquals(0.0, result.getStatDistanceInAir());
        assertEquals(0.0, result.getStatTimeInAir());
        assertTrue(result.getStatKillsByEntityType().isEmpty());

        assertTrue(result.getDiscoveredVariantsByEntityType().isEmpty());
        assertTrue(result.getDiscoveredWeaknessEntityTypes().isEmpty());
    }

    @Test
    void missingLastSavedThrows() {
        String json = "{" +
            "\"position\": {\"x\": 0, \"y\": 100, \"z\": 0}," +
            "\"rotation\": {\"x\": 0, \"y\": 0}," +
            "\"health\": 20," +
            "\"inventory\": []" +
            "}";

        assertThrows(RuntimeException.class, () ->
            serializer.deserialize(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void inventoryRequiresExactly36Slots() {
        assertThrows(IllegalArgumentException.class, () ->
            PlayerData.builder().inventory(new ItemStack[10]).build());
    }

    @Test
    void levelAndXpClampInBuilder() {
        PlayerData data = PlayerData.builder()
            .level(-5)
            .xp(-100)
            .lastSaved(TIMESTAMP)
            .build();

        assertEquals(1, data.getLevel());
        assertEquals(0, data.getXp());
    }

    @Test
    void skillNamedLevelHijacksTopLevel() {
        // Characterization: document-wide regex matching lets a "level" key inside skillLevels
        // shadow the top-level "level" value during deserialization.
        PlayerData original = PlayerData.builder()
            .skillLevels(Map.of("level", 42))
            .level(7)
            .lastSaved(TIMESTAMP)
            .build();

        byte[] bytes = serializer.serialize(original);
        PlayerData deserialized = serializer.deserialize(bytes);

        // skillLevels {"level": 42} serializes before top-level "level": 7;
        // extractInt uses m.find() on the full document and grabs 42 first.
        assertEquals(42, deserialized.getLevel());
    }
}