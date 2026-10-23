package com.stonebreak.network.server;

import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.rpg.backgrounds.BackgroundRegistry;
import com.stonebreak.world.save.model.PlayerData;
import com.stonebreak.world.save.serialization.JsonPlayerSerializer;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * How the server seeds a player's record (issue #334): a fresh character starts from what a
 * newly created character legitimately has (level-1 pools, its point-buy, its background's
 * bonuses and items — all applied server-side), a loaded one from its save (transform
 * included), and an unreadable save is never seeded over.
 */
class FreshPlayerSeedingTest {

    /** A remote (non-Local channel) player. */
    private static ServerPlayer remotePlayer() {
        return new ServerPlayer(mock(ServerConnection.class), 11);
    }

    /** What the creation screen produces: all 27 AP spent, a CP spend, a class. */
    private static CharacterStats createdCharacter(String background) {
        CharacterStats c = new CharacterStats(null);
        c.setSelectedBackground(background);
        for (int i = 0; i < 27; i++) {
            c.incrementAbilityScore(i % 6);
        }
        c.spendCpOnAbility("berserker:0");
        return c;
    }

    @Test
    void aFreshPlayerStartsFromTheLevelOneGrantsNotLegacyHundredPointPools() {
        ServerPlayer sp = remotePlayer();
        assertTrue(new IntegratedServer().seedFromBlob(sp, null));

        CharacterStats s = sp.serverStats();
        assertEquals(CharacterStats.grantedCp(1), s.getRemainingCp());
        assertEquals(CharacterStats.grantedSp(1), s.getRemainingSkillPoints());
        assertEquals(CharacterStats.grantedFp(1), s.getRemainingFeatPoints());
        assertEquals(27, s.getRemainingAp());
        assertEquals(1, s.getLevel());
    }

    @Test
    void theCreationJsonKeepsTheClassThroughTheSaveReader() {
        PlayerData parsed = new JsonPlayerSerializer()
                .deserialize(createdCharacter("adventurer").toCreationJson());
        assertEquals("berserker", parsed.getSelectedClassId(),
                "toCreationJson wrote \"class\", the reader wants \"characterClass\"");

        // ...and the client's lenient join-data reader still reads it back.
        CharacterStats mirror = new CharacterStats(null);
        mirror.applyFromJoinData(createdCharacter("adventurer").toCreationJson());
        assertEquals("berserker", mirror.getSelectedClassId());
    }

    @Test
    void creationAppliesBackgroundBonusesAndItemsServerSide() {
        IntegratedServer server = new IntegratedServer();
        ServerPlayer sp = remotePlayer();
        CharacterStats created = createdCharacter("mercenary");

        assertTrue(server.acceptCharacterCreation(sp, created.toCreationJson()));

        int[] bonuses = BackgroundRegistry.findById("mercenary").orElseThrow().abilityScoreBonuses();
        int[] expected = CharacterStats.withBackgroundBonuses(created.getAbilityScores(), bonuses);
        assertArrayEquals(expected, sp.serverStats().getAbilityScores(), "bonus applied once, server-side");
        assertEquals(0, sp.serverStats().getRemainingAp());
        assertEquals("berserker", sp.serverStats().getSelectedClassId());
        assertEquals(CharacterStats.grantedCp(1) - 1, sp.serverStats().getRemainingCp());
        assertEquals(1, sp.inventory().countOf(ItemType.SWORD.getId()),
                "the background's item is in the authoritative replica");

        // The echo the client applies carries the same (bonus-included) scores.
        CharacterStats mirror = new CharacterStats(null);
        mirror.applyFromJoinData(sp.characterCreationBlob());
        assertArrayEquals(expected, mirror.getAbilityScores());
        assertEquals(0, mirror.getRemainingAp());
    }

    @Test
    void aHostWhoSpentEveryApOnAMercenaryIsNotRefused() {
        // The client mirror after giveStartingItems: creation scores + the background bonus.
        IntegratedServer server = new IntegratedServer();
        ServerPlayer sp = remotePlayer();
        CharacterStats client = createdCharacter("mercenary");
        assertTrue(server.acceptCharacterCreation(sp, client.toCreationJson()));
        client.applyBackgroundBonuses(
                BackgroundRegistry.findById("mercenary").orElseThrow().abilityScoreBonuses());

        PlayerData report = new PlayerData.Builder(sp.progressionBaseline())
                .abilityScores(client.getAbilityScores())
                .remainingAp(client.getRemainingAp())
                .build();
        PlayerData accepted = PlayerPrefsValidator.normalize(
                sp.serverStats(), sp.progressionBaseline(), SpendFloor.NONE, report);
        assertNotNull(accepted, "was newAp = 27 - 28 < 0 against the default record");
        assertEquals(0, accepted.getRemainingAp());
    }

    @Test
    void aForgedCreationOverTheBudgetIsRefused() {
        CharacterStats c = createdCharacter("adventurer");
        String forged = new String(c.toCreationJson(), StandardCharsets.UTF_8)
                .replace("\"remainingAp\":0", "\"remainingAp\":3");   // 87 + 3 > 87
        ServerPlayer sp = remotePlayer();
        assertFalse(new IntegratedServer().acceptCharacterCreation(sp,
                forged.getBytes(StandardCharsets.UTF_8)));
        assertFalse(sp.isInventorySeeded());
    }

    @Test
    void theStoredTransformIsSeededSoAnEarlySaveKeepsIt() {
        PlayerData saved = new PlayerData.Builder(PlayerData.createDefault("w"))
                .position(new Vector3f(120.5f, 71f, -33f))
                .rotation(new Vector2f(90f, -10f))
                .build();
        ServerPlayer sp = remotePlayer();
        assertTrue(new IntegratedServer().seedFromBlob(sp, new JsonPlayerSerializer().serialize(saved)));
        sp.markInventorySeeded();

        // Quit before the first PlayerStateC2S: the persisted record must keep the position.
        PlayerData persisted = ServerPlayerDataFactory.build(sp, "w");
        assertEquals(new Vector3f(120.5f, 71f, -33f), persisted.getPosition());
        assertEquals(90f, persisted.getRotation().x, 1e-6);
        assertEquals(0L, sp.lastStateNs(), "seeding is not a client report");
    }

    @Test
    void anUnreadableSaveIsNeitherSeededNorCleared() {
        ServerPlayer sp = remotePlayer();
        sp.inventory().add(ItemType.STICK.getId(), 3);
        boolean seeded = new IntegratedServer().seedFromBlob(sp,
                "{\"position\":{\"x\":1}}".getBytes(StandardCharsets.UTF_8));   // no inventory array
        assertFalse(seeded, "a remote player with an unreadable save must not be seeded");
        assertEquals(3, sp.inventory().countOf(ItemType.STICK.getId()), "replica untouched");
        assertFalse(sp.isInventorySeeded());
    }

    @Test
    void theFreshRecordStandsAtTheSpawn() {
        PlayerData fresh = PlayerPrefsValidator.freshCharacter(null, null, "w", new Vector3f(4, 66, 9));
        assertEquals(new Vector3f(4, 66, 9), fresh.getPosition());
        ItemStack[] inv = fresh.getInventory();
        assertEquals(36, inv.length);
    }
}
