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
        assertEquals(4, cs.getRemainingCp());
        assertEquals(3, cs.getRemainingSkillPoints());
        assertEquals(1, cs.getRemainingFeatPoints());
    }

    // ─────────────────────────────── Grant table

    @Test
    void grantHelpersSumAcrossLevels() {
        assertEquals(4, CharacterStats.grantedCp(1));
        assertEquals(3, CharacterStats.grantedSp(1));
        assertEquals(1, CharacterStats.grantedFp(1));

        assertEquals(7, CharacterStats.grantedCp(2));
        assertEquals(4, CharacterStats.grantedSp(2));
        assertEquals(2, CharacterStats.grantedFp(2));

        assertEquals(10, CharacterStats.grantedCp(3));
        assertEquals(5, CharacterStats.grantedSp(3));
        assertEquals(3, CharacterStats.grantedFp(3));

        // Level 1 (4 CP, 3 SP) + per level after (3 CP, 1 SP): level 10 => 4 + 27 = 31 CP, 3 + 9 = 12 SP
        assertEquals(31, CharacterStats.grantedCp(10));
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
        assertEquals(7, cs.getRemainingCp());
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
        cs.spendCpOnAbility("ranger:p0:0");
        cs.investSkillPoint("mining");
        cs.acquireFeat("double_jump");

        cs.addXp(400); // levels 2 and 3
        assertEquals(3, cs.getLevel());
        // 10 CP granted, 1 spent => 9 remaining
        assertEquals(9, cs.getRemainingCp());
        // 5 SP granted, 1 invested => 4 remaining
        assertEquals(4, cs.getRemainingSkillPoints());
        // 3 FP granted, 1 acquired => 2 remaining
        assertEquals(2, cs.getRemainingFeatPoints());
    }

    @Test
    void spendingStopsWhenThePoolIsEmpty() {
        CharacterStats cs = stats();
        // Only 4 CP at level 1: the fifth spend is a no-op.
        for (int i = 0; i < 5; i++) cs.spendCpOnAbility("ranger:p0:0");
        assertEquals(0, cs.getRemainingCp());
        assertEquals(4, cs.getRank("ranger:p0:0"));
    }

    // ─────────────────────────────── Grandfathered saves

    @Test
    void restoreKeepsStoredLegacyPoolsRegardlessOfLevel() {
        CharacterStats cs = stats();
        cs.restore(null, java.util.List.of(), java.util.Map.of(), java.util.Map.of(), java.util.Set.of(),
            100, 100, 100, new int[]{10, 10, 10, 10, 10, 10}, 27, 1, 0);
        // A level-1 character that was saved with 100 of each keeps them.
        assertEquals(100, cs.getRemainingCp());
        assertEquals(100, cs.getRemainingSkillPoints());
        assertEquals(100, cs.getRemainingFeatPoints());

        // Leveling up after a grandfathered restore still adds the new grant.
        cs.addXp(200);
        assertEquals(2, cs.getLevel());
        assertEquals(103, cs.getRemainingCp());
        assertEquals(101, cs.getRemainingSkillPoints());
        assertEquals(101, cs.getRemainingFeatPoints());
    }

    // ─────────────────────────────── Undo (character creation)

    @Test
    void undoReversesACpSpendAndRestoresTheClass() {
        CharacterStats cs = stats();
        assertFalse(cs.canUndo());
        cs.spendCpOnAbility("ranger:p0:0");
        assertTrue(cs.canUndo());
        assertEquals(3, cs.getRemainingCp());
        assertEquals("ranger", cs.getSelectedClassId());

        assertTrue(cs.undoLastAllocation());
        assertEquals(4, cs.getRemainingCp());
        assertEquals(0, cs.getRank("ranger:p0:0"));
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
        cs.acquireFeat("double_jump");
        assertEquals(0, cs.getRemainingFeatPoints());
        assertTrue(cs.hasFeat("double_jump"));

        cs.undoLastAllocation();
        assertEquals(1, cs.getRemainingFeatPoints());
        assertFalse(cs.hasFeat("double_jump"));
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
        cs.acquireFeat("double_jump");         // 1 -> 0
        cs.investSkillPoint("alchemy");  // 2 -> 1

        cs.undoLastAllocation(); // un-invest alchemy
        assertEquals(2, cs.getRemainingSkillPoints());
        assertEquals(1, cs.getSkillLevel("mining"));
        assertEquals(0, cs.getSkillLevel("alchemy"));
        assertTrue(cs.hasFeat("double_jump"));

        cs.undoLastAllocation(); // un-acquire tough
        assertEquals(1, cs.getRemainingFeatPoints());
        assertFalse(cs.hasFeat("double_jump"));
    }

    @Test
    void undoRefundsAllocationsMadeByADecrementChain() {
        CharacterStats cs = stats();
        // Only four succeed at level 1; the failed fifth must not leave an undo entry.
        for (int i = 0; i < 5; i++) cs.spendCpOnAbility("ranger:p0:0");
        for (int i = 0; i < 4; i++) assertTrue(cs.undoLastAllocation());
        assertEquals(4, cs.getRemainingCp());
        assertFalse(cs.canUndo());
        assertFalse(cs.undoLastAllocation());
    }

    @Test
    void clearUndoHistoryDropsAllAllocations() {
        CharacterStats cs = stats();
        cs.investSkillPoint("mining");
        cs.acquireFeat("double_jump");
        cs.clearUndoHistory();
        assertFalse(cs.canUndo());
        // History gone, but the allocations themselves remain (this is used at world creation).
        assertEquals(1, cs.getSkillLevel("mining"));
        assertTrue(cs.hasFeat("double_jump"));
    }

    // ─────────────────────────────── In-game session snapshot

    @Test
    void snapshotRestoreDiscardsSessionChanges() {
        CharacterStats cs = stats();
        cs.spendCpOnAbility("ranger:p0:0");
        cs.investSkillPoint("mining");
        cs.acquireFeat("double_jump");
        cs.incrementAbilityScore(0);
        CharacterStats.RpgSnapshot baseline = cs.snapshotRpgState();

        cs.spendCpOnAbility("ranger:p0:0");
        cs.spendCpOnAbility("ranger:p0:0");
        cs.spendCpOnAbility("ranger:p0:0");
        cs.investSkillPoint("alchemy");
        cs.acquireFeat("quick");
        cs.incrementAbilityScore(1);
        assertTrue(cs.hasChangedSince(baseline));

        cs.restoreRpgState(baseline);
        assertFalse(cs.hasChangedSince(baseline));
        assertEquals(3, cs.getRemainingCp());
        assertEquals(1, cs.getRank("ranger:p0:0"));
        assertEquals(1, cs.getSkillLevel("mining"));
        assertEquals(0, cs.getSkillLevel("alchemy"));
        assertTrue(cs.hasFeat("double_jump"));
        assertFalse(cs.hasFeat("quick"));
    }

    @Test
    void freshStatsHaveNoChangesAgainstTheirOwnSnapshot() {
        CharacterStats cs = stats();
        assertFalse(cs.hasChangedSince(cs.snapshotRpgState()));
    }
}
