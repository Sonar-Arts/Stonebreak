package com.stonebreak.network.server;

import com.stonebreak.player.CharacterStats;
import com.stonebreak.world.save.model.PlayerData;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link PlayerPrefsValidator} rules (issue #334): the server OWNS level/xp and the
 * point pools — a client report can claim any level it likes and gets the server's values
 * back — while spends are validated against the pools the server's own level grants and
 * cosmetics stay monotone/append-only.
 */
class PlayerPrefsValidationTest {

    /** Level 3: grantedCp/Sp/Fp = 10/5/3. Scores sum 63, AP pool 5 (budget 68 ≤ 90). */
    private static PlayerData baseline() {
        return PlayerData.builder()
                .level(3)
                .xp(40)
                .remainingCp(1)
                .remainingSp(2)
                .remainingFp(1)
                .remainingAp(5)
                .abilityScores(new int[]{12, 10, 11, 10, 10, 10})
                .classInvestmentOrder(java.util.List.of("berserker"))
                .spentAbilityCp(Map.of("berserker:p0:0", 2))
                .skillLevels(Map.of("mining", 2))
                .acquiredFeatIds(Set.of("double_jump"))
                .build();
    }

    private static CharacterStats serverStatsFor(PlayerData baseline) {
        CharacterStats s = new CharacterStats(null);
        s.restore(baseline.getSelectedClassId(), baseline.getClassInvestmentOrder(),
                baseline.getSpentAbilityCp(),
                baseline.getSkillLevels(), baseline.getAcquiredFeatIds(),
                baseline.getRemainingCp(), baseline.getRemainingSkillPoints(),
                baseline.getRemainingFeatPoints(), baseline.getAbilityScores(),
                baseline.getRemainingAp(), baseline.getLevel(), baseline.getXp());
        return s;
    }

    @Test
    void identicalStatePasses() {
        PlayerData accepted = PlayerPrefsValidator.normalize(
                serverStatsFor(baseline()), baseline(), SpendFloor.NONE, baseline());
        assertNotNull(accepted);
        assertEquals(3, accepted.getLevel());
        assertEquals(40, accepted.getXp());
        assertEquals(1, accepted.getRemainingCp());
        assertEquals(5, accepted.getRemainingAp());
    }

    @Test
    void forgedLevelAndPoolsAreOverwritten() {
        // A modified client claims level 100 with everything maxed — the server owns
        // level/xp/pools, so the accepted state carries the server's values instead.
        PlayerData candidate = new PlayerData.Builder(baseline())
                .level(100)
                .xp(999_999)
                .remainingCp(500)
                .remainingSp(500)
                .remainingFp(500)
                .build();
        PlayerData accepted = PlayerPrefsValidator.normalize(
                serverStatsFor(baseline()), baseline(), SpendFloor.NONE, candidate);
        assertNotNull(accepted);
        assertEquals(3, accepted.getLevel(), "level is server-owned");
        assertEquals(40, accepted.getXp(), "xp is server-owned");
        assertEquals(1, accepted.getRemainingCp(), "claimed pool ignored, held pool kept");
        assertEquals(2, accepted.getRemainingSkillPoints());
        assertEquals(1, accepted.getRemainingFeatPoints());
    }

    @Test
    void spendBeyondServerHeldPoolRejected() {
        PlayerData candidate = new PlayerData.Builder(baseline())
                .spentAbilityCp(Map.of("berserker:p0:0", 11))  // grantedCp(3) == 10
                .build();
        assertNull(PlayerPrefsValidator.normalize(
                serverStatsFor(baseline()), baseline(), SpendFloor.NONE, candidate));
    }

    @Test
    void skillSpendBeyondServerHeldPoolRejected() {
        PlayerData candidate = new PlayerData.Builder(baseline())
                .skillLevels(Map.of("mining", 10))   // grantedSp(3) == 5
                .build();
        assertNull(PlayerPrefsValidator.normalize(
                serverStatsFor(baseline()), baseline(), SpendFloor.NONE, candidate));
    }

    @Test
    void featBeyondServerHeldPoolRejected() {
        // The registry currently defines a single feat, so three unknown ids both trip the
        // registry check and exceed grantedFp(3) == 3 — either rejection is correct here.
        PlayerData candidate = new PlayerData.Builder(baseline())
                .acquiredFeatIds(Set.of("double_jump", "strong", "swift", "lucky"))
                .build();
        assertNull(PlayerPrefsValidator.normalize(
                serverStatsFor(baseline()), baseline(), SpendFloor.NONE, candidate));
    }

    @Test
    void unknownFeatRejected() {
        PlayerData candidate = new PlayerData.Builder(baseline())
                .acquiredFeatIds(Set.of("not-a-feat"))
                .build();
        assertNull(PlayerPrefsValidator.normalize(
                serverStatsFor(baseline()), baseline(), SpendFloor.NONE, candidate));
    }

    @Test
    void legacyLargerPoolsArePreserved() {
        // Old saves keep their stored (legacy, larger) pools — normalization must never
        // shrink a held pool that the ledger delta doesn't explain.
        PlayerData legacy = new PlayerData.Builder(baseline())
                .remainingCp(50)
                .remainingSp(30)
                .build();
        PlayerData accepted = PlayerPrefsValidator.normalize(
                serverStatsFor(legacy), legacy, SpendFloor.NONE, legacy);
        assertNotNull(accepted);
        assertEquals(50, accepted.getRemainingCp());
        assertEquals(30, accepted.getRemainingSkillPoints());
    }

    @Test
    void multiclassRanksArePricedByInvestmentSlot() {
        // Held pool 4 (the baseline's 1 + 3). The second class's rank costs 1 CP and the
        // third class's 2 CP (MULTICLASS_PENALTY), so 2 + 1 + 2 = 5 of 6 owned CP.
        PlayerData held = new PlayerData.Builder(baseline()).remainingCp(4).build();
        PlayerData candidate = new PlayerData.Builder(held)
                .classInvestmentOrder(java.util.List.of("berserker", "ranger", "rogue"))
                .spentAbilityCp(Map.of("berserker:p0:0", 2, "ranger:p0:0", 1, "rogue:p0:0", 1))
                .build();
        PlayerData accepted = PlayerPrefsValidator.normalize(serverStatsFor(held), held, SpendFloor.NONE, candidate);
        assertNotNull(accepted);
        assertEquals(1, accepted.getRemainingCp(), "ranks priced per slot, not 1 CP each");
        assertEquals(java.util.List.of("berserker", "ranger", "rogue"), accepted.getClassInvestmentOrder());
    }

    @Test
    void investmentOrderMustListEachRankedClassOnce() {
        PlayerData missing = new PlayerData.Builder(baseline())
                .classInvestmentOrder(java.util.List.of("berserker", "berserker"))
                .spentAbilityCp(Map.of("berserker:p0:0", 1, "ranger:p0:0", 1))
                .build();
        assertNull(PlayerPrefsValidator.normalize(serverStatsFor(baseline()), baseline(), SpendFloor.NONE, missing));
        PlayerData coreAbilityKey = new PlayerData.Builder(baseline())
                .spentAbilityCp(Map.of("berserker:0", 1))
                .build();
        assertNull(PlayerPrefsValidator.normalize(serverStatsFor(baseline()), baseline(), SpendFloor.NONE, coreAbilityKey));
    }

    @Test
    void weaponProficiencyIsPaidInWtpNotSp() {
        // One proficiency rank fits the starting WTP grant and costs no SP.
        String prof = com.stonebreak.rpg.proficiency.WeaponProficiency.skillId(
                com.stonebreak.items.weapon.WeaponGroup.values()[0]);
        PlayerData candidate = new PlayerData.Builder(baseline())
                .skillLevels(Map.of("mining", 2, prof, 1))
                .build();
        PlayerData accepted = PlayerPrefsValidator.normalize(
                serverStatsFor(baseline()), baseline(), SpendFloor.NONE, candidate);
        assertNotNull(accepted);
        assertEquals(2, accepted.getRemainingSkillPoints(), "proficiency rank charged no SP");
        // A second rank exceeds the WTP the ledger earns.
        PlayerData over = new PlayerData.Builder(baseline())
                .skillLevels(Map.of("mining", 2, prof, 2))
                .build();
        assertNull(PlayerPrefsValidator.normalize(serverStatsFor(baseline()), baseline(), SpendFloor.NONE, over));
    }

    @Test
    void undoneSpendAcceptedAndPoolRecomputed() {
        // The character sheet's save-or-discard undo: the ledger shrinks, the pool grows —
        // safe because the pool is recomputed from the server level, never carried.
        PlayerData candidate = new PlayerData.Builder(baseline())
                .spentAbilityCp(Map.of())
                .skillLevels(Map.of())
                .build();
        PlayerData accepted = PlayerPrefsValidator.normalize(
                serverStatsFor(baseline()), baseline(), SpendFloor.NONE, candidate);
        assertNotNull(accepted);
        assertEquals(3, accepted.getRemainingCp(), "held pool 1 + refunded ledger 2");
        assertEquals(4, accepted.getRemainingSkillPoints(), "held pool 2 + refunded ledger 2");
        assertEquals(java.util.List.of(), accepted.getClassInvestmentOrder(), "unranked class leaves the order");
    }

    @Test
    void scoreRaiseBeyondServerApPoolRejected() {
        // Sum 63 -> 69 needs 6 AP but the server-held pool has 5.
        PlayerData candidate = new PlayerData.Builder(baseline())
                .abilityScores(new int[]{18, 10, 11, 10, 10, 10})
                .build();
        assertNull(PlayerPrefsValidator.normalize(
                serverStatsFor(baseline()), baseline(), SpendFloor.NONE, candidate));
    }

    @Test
    void scoreUndoRefundsServerApPool() {
        PlayerData candidate = new PlayerData.Builder(baseline())
                .abilityScores(new int[]{11, 10, 11, 10, 10, 10})   // one point un-done
                .build();
        PlayerData accepted = PlayerPrefsValidator.normalize(
                serverStatsFor(baseline()), baseline(), SpendFloor.NONE, candidate);
        assertNotNull(accepted);
        assertEquals(6, accepted.getRemainingAp());
    }

    @Test
    void scoreRefundCannotExceedLifetimeBudget() {
        // Dropping every score to 1 "refunds" 57 AP — but the sum+AP budget is conserved
        // at the accepted baseline's 68 (≤ the 90 creation ceiling), so the redistribution
        // can never grow the total stat budget beyond what the server granted.
        PlayerData candidate = new PlayerData.Builder(baseline())
                .abilityScores(new int[]{1, 1, 1, 1, 1, 1})
                .build();
        PlayerData accepted = PlayerPrefsValidator.normalize(
                serverStatsFor(baseline()), baseline(), SpendFloor.NONE, candidate);
        assertNotNull(accepted);
        assertEquals(62, accepted.getRemainingAp());
        assertEquals(68, 6 + accepted.getRemainingAp());   // budget unchanged
        assertTrue(6 + accepted.getRemainingAp() <= PlayerPrefsValidator.MAX_SCORE_BUDGET);
    }

    @Test
    void statisticRegressionRejected() {
        com.stonebreak.player.PlayerStats stats = new com.stonebreak.player.PlayerStats();
        stats.addDamageDealt(-1);   // below the baseline's 0
        PlayerData candidate = new PlayerData.Builder(baseline())
                .stats(stats)
                .build();
        assertNull(PlayerPrefsValidator.normalize(
                serverStatsFor(baseline()), baseline(), SpendFloor.NONE, candidate));
    }

    @Test
    void discoveryRegressionRejected() {
        PlayerData baseline = new PlayerData.Builder(baseline())
                .discoveredWeaknessEntityTypes(Set.of("cow"))
                .build();
        PlayerData candidate = new PlayerData.Builder(baseline)
                .discoveredWeaknessEntityTypes(Set.of())
                .build();
        assertNull(PlayerPrefsValidator.normalize(
                serverStatsFor(baseline), baseline, SpendFloor.NONE, candidate));
    }

    // ── Character-creation normalization ────────────────────────────────────────

    @Test
    void creationForgedPoolsOverwritten() {
        PlayerData candidate = PlayerData.builder()
                .level(50)
                .xp(9_999)
                .remainingCp(1000)
                .remainingSp(1000)
                .remainingFp(1000)
                .remainingAp(27)
                .abilityScores(new int[]{10, 10, 10, 10, 10, 10})
                .build();
        PlayerData accepted = PlayerPrefsValidator.normalizeCreation(candidate);
        assertNotNull(accepted);
        assertEquals(1, accepted.getLevel());
        assertEquals(0, accepted.getXp());
        assertEquals(CharacterStats.grantedCp(1), accepted.getRemainingCp());
        assertEquals(CharacterStats.grantedSp(1), accepted.getRemainingSkillPoints());
        assertEquals(CharacterStats.grantedFp(1), accepted.getRemainingFeatPoints());
        assertEquals(27, accepted.getRemainingAp());
    }

    @Test
    void creationSpendBeyondLevelOneGrantRejected() {
        PlayerData candidate = PlayerData.builder()
                .remainingCp(1)
                .spentAbilityCp(Map.of("berserker:p0:0", 5))   // grantedCp(1) == 4
                .abilityScores(new int[]{10, 10, 10, 10, 10, 10})
                .build();
        assertNull(PlayerPrefsValidator.normalizeCreation(candidate));
    }

    @Test
    void creationScoreBudgetEnforced() {
        PlayerData candidate = PlayerData.builder()
                .remainingAp(27)
                .abilityScores(new int[]{13, 10, 13, 10, 10, 10})   // sum 66 + 27 = 93 > 90
                .build();
        assertNull(PlayerPrefsValidator.normalizeCreation(candidate));
    }

    @Test
    void maxedSingleScoreWithinBudgetAccepted() {
        // A score can legitimately reach 10 + 27 AP (+ a background bonus on spawn), so the
        // per-score check must not reject it while the sum budget holds.
        PlayerData candidate = PlayerData.builder()
                .remainingAp(0)
                .abilityScores(new int[]{40, 10, 10, 10, 10, 10})   // sum 90 = budget
                .build();
        PlayerData accepted = PlayerPrefsValidator.normalizeCreation(candidate);
        assertNotNull(accepted);
        assertEquals(40, accepted.getAbilityScores()[0]);
    }

    @Test
    void theCreationRecordSurvivesTheStrictSaveReader() {
        // #358 made the reader throw without an inventory array; the server parses the
        // late-join creation blob with it, so a blob without one fell back to defaults.
        PlayerData parsed = new com.stonebreak.world.save.serialization.JsonPlayerSerializer()
                .deserialize(new CharacterStats(null).toCreationJson());
        assertNotNull(PlayerPrefsValidator.normalizeCreation(parsed));
    }
}
