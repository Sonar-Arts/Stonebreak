package com.stonebreak.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the level-based currency economy: every character starts with the
 * level-1 grant, each level-up credits its grant, unspent points accumulate,
 * and legacy saves keep their stored (grandfathered) pools on restore.
 */
class CharacterStatsProgressionTest {

    private static CharacterStats stats() {
        return new CharacterStats(null);
    }

    // ─────────────────────────────── Starting pools

    @Test
    void freshCharacterStartsWithTheLevelOneGrant() {
        CharacterStats cs = stats();
        assertEquals(1, cs.getLevel());
        assertEquals(1, cs.getRemainingCp());
        assertEquals(3, cs.getRemainingSkillPoints());
        assertEquals(1, cs.getRemainingFeatPoints());
    }

    // ─────────────────────────────── Grant table

    @Test
    void grantHelpersSumAcrossLevels() {
        assertEquals(1, CharacterStats.grantedCp(1));
        assertEquals(3, CharacterStats.grantedSp(1));
        assertEquals(1, CharacterStats.grantedFp(1));

        assertEquals(2, CharacterStats.grantedCp(2));
        assertEquals(4, CharacterStats.grantedSp(2));
        assertEquals(2, CharacterStats.grantedFp(2));

        assertEquals(3, CharacterStats.grantedCp(3));
        assertEquals(5, CharacterStats.grantedSp(3));
        assertEquals(3, CharacterStats.grantedFp(3));

        // Level 1 (3 SP) + one per level after: level 10 => 3 + 9 = 12 SP
        assertEquals(10, CharacterStats.grantedCp(10));
        assertEquals(12, CharacterStats.grantedSp(10));
        assertEquals(10, CharacterStats.grantedFp(10));
    }

    // ─────────────────────────────── Level-up crediting

    @Test
    void levelingUpCreditsTheNewLevelsGrant() {
        CharacterStats cs = stats();
        // Level 1 -> 2 needs 200 XP (getXpForNextLevel at level 1)
        cs.addXp(200);
        assertEquals(2, cs.getLevel());
        assertEquals(2, cs.getRemainingCp());
        assertEquals(4, cs.getRemainingSkillPoints());
        assertEquals(2, cs.getRemainingFeatPoints());
    }

    @Test
    void aMultiLevelChainCreditsEveryLevel() {
        CharacterStats cs = stats();
        // 600 XP chains through level 2, 3 and 4 (200 XP each).
        cs.addXp(600);
        assertEquals(4, cs.getLevel());
        assertEquals(CharacterStats.grantedCp(4), cs.getRemainingCp());
        assertEquals(CharacterStats.grantedSp(4), cs.getRemainingSkillPoints());
        assertEquals(CharacterStats.grantedFp(4), cs.getRemainingFeatPoints());
    }

    @Test
    void unspentPointsAccumulateAcrossLevels() {
        CharacterStats cs = stats();
        cs.spendCpOnAbility("ranger:0");
        cs.investSkillPoint("mining");
        cs.acquireFeat("someFeat");

        cs.addXp(400); // levels 2 and 3
        assertEquals(3, cs.getLevel());
        // 3 CP granted, 1 spent => 2 remaining
        assertEquals(2, cs.getRemainingCp());
        // 5 SP granted, 1 invested => 4 remaining
        assertEquals(4, cs.getRemainingSkillPoints());
        // 3 FP granted, 1 acquired => 2 remaining
        assertEquals(2, cs.getRemainingFeatPoints());
    }

    @Test
    void spendingStopsWhenThePoolIsEmpty() {
        CharacterStats cs = stats();
        cs.spendCpOnAbility("ranger:0");
        // Only 1 CP at level 1: the second spend is a no-op.
        cs.spendCpOnAbility("ranger:0");
        assertEquals(0, cs.getRemainingCp());
        assertEquals(1, cs.getSpentCp("ranger:0"));
    }

    // ─────────────────────────────── Grandfathered saves

    @Test
    void restoreKeepsStoredLegacyPoolsRegardlessOfLevel() {
        CharacterStats cs = stats();
        cs.restore(null, java.util.Map.of(), java.util.Map.of(), java.util.Set.of(),
            100, 100, 100, new int[]{10, 10, 10, 10, 10, 10}, 27, 1, 0);
        // A level-1 character that was saved with 100 of each keeps them.
        assertEquals(100, cs.getRemainingCp());
        assertEquals(100, cs.getRemainingSkillPoints());
        assertEquals(100, cs.getRemainingFeatPoints());

        // Leveling up after a grandfathered restore still adds the new grant.
        cs.addXp(200);
        assertEquals(2, cs.getLevel());
        assertEquals(101, cs.getRemainingCp());
        assertEquals(101, cs.getRemainingSkillPoints());
        assertEquals(101, cs.getRemainingFeatPoints());
    }

    // ─────────────────────────────── Undo (character creation)

    @Test
    void undoReversesACpSpendAndRestoresTheClass() {
        CharacterStats cs = stats();
        assertFalse(cs.canUndo());
        cs.spendCpOnAbility("ranger:0");
        assertTrue(cs.canUndo());
        assertEquals(0, cs.getRemainingCp());
        assertEquals("ranger", cs.getSelectedClassId());

        assertTrue(cs.undoLastAllocation());
        assertEquals(1, cs.getRemainingCp());
        assertEquals(0, cs.getSpentCp("ranger:0"));
        assertNull(cs.getSelectedClassId());
        assertFalse(cs.canUndo());
    }

    @Test
    void undoReversesASkillPoint() {
        CharacterStats cs = stats();
        cs.investSkillPoint("mining");
        assertEquals(2, cs.getRemainingSkillPoints());
        assertEquals(1, cs.getSkillLevel("mining"));

        cs.undoLastAllocation();
        assertEquals(3, cs.getRemainingSkillPoints());
        assertEquals(0, cs.getSkillLevel("mining"));
    }

    @Test
    void undoReversesAFeatAcquisition() {
        CharacterStats cs = stats();
        cs.acquireFeat("tough");
        assertEquals(0, cs.getRemainingFeatPoints());
        assertTrue(cs.hasFeat("tough"));

        cs.undoLastAllocation();
        assertEquals(1, cs.getRemainingFeatPoints());
        assertFalse(cs.hasFeat("tough"));
    }

    @Test
    void undoReversesAnAbilityScoreRaise() {
        CharacterStats cs = stats();
        int before = cs.getStrength();
        cs.incrementAbilityScore(0);
        assertEquals(before + 1, cs.getStrength());
        assertEquals(26, cs.getRemainingAp());

        cs.undoLastAllocation();
        assertEquals(before, cs.getStrength());
        assertEquals(27, cs.getRemainingAp());
    }

    @Test
    void undoIsLastInFirstOut() {
        CharacterStats cs = stats();
        cs.investSkillPoint("mining");   // 3 -> 2
        cs.acquireFeat("tough");         // 1 -> 0
        cs.investSkillPoint("alchemy");  // 2 -> 1

        cs.undoLastAllocation(); // un-invest alchemy
        assertEquals(2, cs.getRemainingSkillPoints());
        assertEquals(1, cs.getSkillLevel("mining"));
        assertEquals(0, cs.getSkillLevel("alchemy"));
        assertTrue(cs.hasFeat("tough"));

        cs.undoLastAllocation(); // un-acquire tough
        assertEquals(1, cs.getRemainingFeatPoints());
        assertFalse(cs.hasFeat("tough"));
    }

    @Test
    void undoRefundsAllocationsMadeByADecrementChain() {
        CharacterStats cs = stats();
        cs.spendCpOnAbility("ranger:0");
        cs.spendCpOnAbility("ranger:0"); // only one succeeds at level 1
        cs.undoLastAllocation();
        assertEquals(1, cs.getRemainingCp());
        assertFalse(cs.canUndo());
        assertFalse(cs.undoLastAllocation());
    }

    @Test
    void clearUndoHistoryDropsAllAllocations() {
        CharacterStats cs = stats();
        cs.investSkillPoint("mining");
        cs.acquireFeat("tough");
        cs.clearUndoHistory();
        assertFalse(cs.canUndo());
        // History gone, but the allocations themselves remain (this is used at world creation).
        assertEquals(1, cs.getSkillLevel("mining"));
        assertTrue(cs.hasFeat("tough"));
    }

    // ─────────────────────────────── In-game session snapshot

    @Test
    void snapshotRestoreDiscardsSessionChanges() {
        CharacterStats cs = stats();
        cs.spendCpOnAbility("ranger:0");
        cs.investSkillPoint("mining");
        cs.acquireFeat("tough");
        cs.incrementAbilityScore(0);
        CharacterStats.RpgSnapshot baseline = cs.snapshotRpgState();

        cs.spendCpOnAbility("ranger:0");
        cs.spendCpOnAbility("ranger:0");
        cs.spendCpOnAbility("ranger:0");
        cs.investSkillPoint("alchemy");
        cs.acquireFeat("quick");
        cs.incrementAbilityScore(1);
        assertTrue(cs.hasChangedSince(baseline));

        cs.restoreRpgState(baseline);
        assertFalse(cs.hasChangedSince(baseline));
        assertEquals(0, cs.getRemainingCp());
        assertEquals(1, cs.getSpentCp("ranger:0"));
        assertEquals(1, cs.getSkillLevel("mining"));
        assertEquals(0, cs.getSkillLevel("alchemy"));
        assertTrue(cs.hasFeat("tough"));
        assertFalse(cs.hasFeat("quick"));
    }

    @Test
    void freshStatsHaveNoChangesAgainstTheirOwnSnapshot() {
        CharacterStats cs = stats();
        assertFalse(cs.hasChangedSince(cs.snapshotRpgState()));
    }
}
