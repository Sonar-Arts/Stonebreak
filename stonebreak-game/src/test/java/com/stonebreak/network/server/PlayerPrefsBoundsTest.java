package com.stonebreak.network.server;

import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.network.packet.player.PlayerDataS2C;
import com.stonebreak.network.packet.player.ProgressS2C;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.world.save.model.PlayerData;
import com.stonebreak.world.save.serialization.JsonPlayerSerializer;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Hardening of the {@link PlayerPrefsValidator} rules (issue #334 review): no int overflow
 * through the conservation sums, bounded collections (the accepted record must stay
 * sendable), append-only maps that MERGE omitted keys, and a validated class id.
 */
class PlayerPrefsBoundsTest {

    /** Level 3: held pools 1/2/1, AP 5, scores sum 63. */
    private static PlayerData baseline() {
        return PlayerData.builder()
                .level(3)
                .xp(40)
                .remainingCp(1)
                .remainingSp(2)
                .remainingFp(1)
                .remainingAp(5)
                .abilityScores(new int[]{12, 10, 11, 10, 10, 10})
                .spentAbilityCp(Map.of("warrior:0", 2))
                .skillLevels(Map.of("mining", 2))
                .statKillsByEntityType(Map.of("COW", 4L, "SHEEP", 2L))
                .discoveredVariantsByEntityType(Map.of("COW", Set.of("angus")))
                .build();
    }

    private static CharacterStats statsFor(PlayerData b) {
        CharacterStats s = new CharacterStats(null);
        s.restore(b.getSelectedClassId(), b.getSpentAbilityCp(), b.getSkillLevels(),
                b.getAcquiredFeatIds(), b.getRemainingCp(), b.getRemainingSkillPoints(),
                b.getRemainingFeatPoints(), b.getAbilityScores(), b.getRemainingAp(),
                b.getLevel(), b.getXp());
        return s;
    }

    private static PlayerData normalize(PlayerData candidate) {
        return PlayerPrefsValidator.normalize(
                statsFor(baseline()), baseline(), SpendFloor.NONE, candidate);
    }

    // ── #3: overflow ──────────────────────────────────────────────────────────

    @Test
    void wrappingAbilityScoresAreRefused() {
        // 10+10+MAX+MAX+21+21 wraps to 60 in int arithmetic: it used to "refund" 3 AP.
        PlayerData c = new PlayerData.Builder(baseline())
                .abilityScores(new int[]{10, 10, Integer.MAX_VALUE, Integer.MAX_VALUE, 21, 21})
                .build();
        assertNull(normalize(c));
    }

    @Test
    void wrappingCpLedgerCannotMintPoints() {
        // MAX + MAX sums to -2: the old check computed newCp = 1 + 2 - (-2) = 5.
        PlayerData c = new PlayerData.Builder(baseline())
                .spentAbilityCp(Map.of("a:0", Integer.MAX_VALUE, "b:0", Integer.MAX_VALUE))
                .build();
        assertNull(normalize(c));
    }

    @Test
    void wrappingSkillLedgerCannotMintPoints() {
        Map<String, Integer> skills = new HashMap<>();
        for (int i = 0; i < 3; i++) {
            skills.put("s" + i, Integer.MAX_VALUE);
        }
        assertNull(normalize(new PlayerData.Builder(baseline()).skillLevels(skills).build()));
    }

    // ── #8: bounded collections ───────────────────────────────────────────────

    @Test
    void paddedLedgerThatWouldOverflowTheRestorePacketIsRefused() {
        // Thousands of zero-valued keys conserve every pool but would make the accepted
        // record's re-serialization exceed PlayerDataS2C's 64 KB cap.
        Map<String, Integer> skills = new HashMap<>(baseline().getSkillLevels());
        for (int i = 0; i < 5_000; i++) {
            skills.put("pad" + i, 0);
        }
        PlayerData c = new PlayerData.Builder(baseline()).skillLevels(skills).build();
        assertTrue(new JsonPlayerSerializer().serialize(c).length > PlayerDataS2C.MAX_PLAYER_BYTES);
        assertNull(normalize(c));
    }

    @Test
    void paddedDiscoveriesAreRefused() {
        Map<String, Set<String>> variants = new HashMap<>(baseline().getDiscoveredVariantsByEntityType());
        for (int i = 0; i < 2_000; i++) {
            variants.put("T" + i, Set.of("v"));
        }
        assertNull(normalize(new PlayerData.Builder(baseline())
                .discoveredVariantsByEntityType(variants).build()));
    }

    @Test
    void acceptedRecordAlwaysFitsTheRestorePacket() {
        PlayerData accepted = normalize(baseline());
        assertNotNull(accepted);
        assertTrue(new JsonPlayerSerializer().serialize(accepted).length
                <= PlayerPrefsValidator.MAX_ACCEPTED_BYTES);
    }

    // ── #9: append-only merge + class validation ─────────────────────────────

    @Test
    void omittedKillKeysKeepTheirAcceptedCounts() {
        PlayerData c = new PlayerData.Builder(baseline())
                .statKillsByEntityType(Map.of("COW", 5L))   // SHEEP omitted
                .build();
        PlayerData accepted = normalize(c);
        assertNotNull(accepted);
        assertEquals(5L, accepted.getStatKillsByEntityType().get("COW"));
        assertEquals(2L, accepted.getStatKillsByEntityType().get("SHEEP"), "omission never drops data");
    }

    @Test
    void omittedVariantTypesKeepTheirDiscoveries() {
        PlayerData c = new PlayerData.Builder(baseline())
                .discoveredVariantsByEntityType(Map.of("SHEEP", Set.of("white")))   // COW omitted
                .build();
        PlayerData accepted = normalize(c);
        assertNotNull(accepted);
        assertEquals(Set.of("angus"), accepted.getDiscoveredVariantsByEntityType().get("COW"));
        assertEquals(Set.of("white"), accepted.getDiscoveredVariantsByEntityType().get("SHEEP"));
    }

    @Test
    void unknownClassIdIsRefused() {
        assertNull(normalize(new PlayerData.Builder(baseline())
                .selectedClassId("archmage-of-everything").build()));
        assertNotNull(normalize(new PlayerData.Builder(baseline())
                .selectedClassId("berserker").build()));
    }

    // ── #4: the server acks the prefs it processed ───────────────────────────

    @Test
    void progressionEchoCarriesTheLastProcessedPrefsSeq() {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 3);
        sp.acceptBaseline(baseline());
        assertEquals(0, sp.buildProgression().ackSeq());
        sp.recordPrefsAck(7);
        ProgressS2C echo = sp.buildProgression();
        assertEquals(7, echo.ackSeq());
        assertEquals(1, echo.remainingCp());
    }
}
