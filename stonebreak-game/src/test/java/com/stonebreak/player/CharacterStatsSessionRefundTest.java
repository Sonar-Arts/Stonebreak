package com.stonebreak.player;

import com.stonebreak.items.weapon.WeaponGroup;
import com.stonebreak.rpg.feats.FeatDefinition;
import com.stonebreak.rpg.feats.FeatRegistry;
import com.stonebreak.rpg.proficiency.ProficiencyRank;
import com.stonebreak.rpg.skills.SkillRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The in-game sheet's right-click undo: one point bought since the panel session's baseline comes
 * back, a saved point never does, and a talent rank takes the session ranks that stood on it along.
 */
class CharacterStatsSessionRefundTest {

    /** A level-1 character with the given pools and nothing invested. */
    private static CharacterStats with(int cp, int sp, int fp, int ap) {
        CharacterStats cs = new CharacterStats(null);
        cs.restore(null, List.of(), Map.of(), Map.of(), Set.of(), cp, sp, fp,
                new int[]{10, 10, 10, 10, 10, 10}, ap, 1, 0);
        return cs;
    }

    private static void addRanks(CharacterStats cs, String key, int n) {
        for (int i = 0; i < n; i++) {
            assertTrue(cs.addRank(key), "rank " + (i + 1) + " of " + key + ": " + cs.lockReason(key));
        }
    }

    @Test
    void refundsOnlyRanksBoughtThisSession() {
        CharacterStats cs = with(20, 0, 0, 0);
        addRanks(cs, "berserker:p0:0", 2);
        CharacterStats.RpgSnapshot saved = cs.snapshotRpgState();
        addRanks(cs, "berserker:p0:0", 1);
        assertTrue(cs.refundSessionRank("berserker:p0:0", saved));
        assertEquals(2, cs.getRank("berserker:p0:0"));
        assertFalse(cs.refundSessionRank("berserker:p0:0", saved), "the two saved ranks stay");
        assertFalse(cs.hasChangedSince(saved));
    }

    @Test
    void cascadesSessionRanksThatLoseTheirParentOrGate() {
        CharacterStats cs = with(20, 0, 0, 0);
        CharacterStats.RpgSnapshot saved = cs.snapshotRpgState();
        addRanks(cs, "berserker:p0:0", 1);
        addRanks(cs, "berserker:p1:0", 4);
        addRanks(cs, "berserker:p0:1", 2);
        assertTrue(cs.refundSessionRank("berserker:p1:0", saved), "Tier II loses its gate");
        assertEquals(3, cs.getRank("berserker:p1:0"));
        assertEquals(0, cs.getRank("berserker:p0:1"));
        assertEquals(20 - 4, cs.getRemainingCp());

        addRanks(cs, "berserker:p2:0", 1);
        addRanks(cs, "berserker:p0:1", 1);
        assertTrue(cs.refundSessionRank("berserker:p0:0", saved), "Crimson Howl loses its parent");
        assertEquals(0, cs.getRank("berserker:p0:1"));
        assertEquals(20 - 4, cs.getRemainingCp());
    }

    @Test
    void refusesACascadeThatWouldTakeASavedRank() {
        CharacterStats cs = with(20, 0, 0, 0);
        addRanks(cs, "berserker:p1:0", 4);
        addRanks(cs, "berserker:p0:0", 1);
        addRanks(cs, "berserker:p0:1", 1);
        CharacterStats.RpgSnapshot saved = cs.snapshotRpgState();
        addRanks(cs, "berserker:p1:0", 1);
        assertTrue(cs.refundSessionRank("berserker:p1:0", saved));
        assertFalse(cs.refundSessionRank("berserker:p1:0", saved));
        assertEquals(1, cs.getRank("berserker:p0:1"));
    }

    @Test
    void emptyingASessionClassFreesItsSlot() {
        CharacterStats cs = with(10, 0, 0, 0);
        addRanks(cs, "ranger:p0:0", 1);
        CharacterStats.RpgSnapshot saved = cs.snapshotRpgState();
        addRanks(cs, "rogue:p0:0", 1);
        addRanks(cs, "berserker:p0:0", 1);
        assertTrue(cs.refundSessionRank("rogue:p0:0", saved));
        assertEquals(List.of("ranger", "berserker"), cs.getClassInvestmentOrder());
        assertEquals(10 - 1 - 1, cs.getRemainingCp(), "Berserker re-prices to the second slot");
        assertTrue(cs.refundSessionRank("berserker:p0:0", saved));
        assertFalse(cs.hasChangedSince(saved));
    }

    @Test
    void refundsSkillFeatAbilityAndWeaponPointsAboveTheBaseline() {
        CharacterStats cs = with(0, 3, 2, 3);
        String skill = SkillRegistry.ALL.get(0).id();
        String feat = FeatRegistry.ALL.stream().filter(f -> f.level() <= 1).map(FeatDefinition::id).findFirst()
                .orElseThrow();
        WeaponGroup group = WeaponGroup.values()[0];
        cs.investSkillPoint(skill);
        cs.incrementAbilityScore(2);
        CharacterStats.RpgSnapshot saved = cs.snapshotRpgState();

        cs.investSkillPoint(skill);
        cs.acquireFeat(feat);
        cs.incrementAbilityScore(2);
        assertTrue(cs.trainWeapon(group));

        assertTrue(cs.refundSessionSkillPoint(skill, saved));
        assertFalse(cs.refundSessionSkillPoint(skill, saved), "the saved skill point stays");
        assertEquals(1, cs.getSkillLevel(skill));
        assertTrue(cs.refundSessionFeat(feat, saved));
        assertFalse(cs.refundSessionFeat(feat, saved));
        assertTrue(cs.refundSessionAbilityPoint(2, saved));
        assertFalse(cs.refundSessionAbilityPoint(2, saved), "the saved ability point stays");
        assertEquals(11, cs.getConstitution());
        assertTrue(cs.refundSessionWeaponRank(group, saved));
        assertFalse(cs.refundSessionWeaponRank(group, saved));
        assertEquals(ProficiencyRank.UNTRAINED, cs.getProficiency(group));
        assertFalse(cs.hasChangedSince(saved));
    }

    @Test
    void aRefundLeavesTheUndoHistoryInStep() {
        CharacterStats cs = with(20, 2, 0, 2);
        CharacterStats.RpgSnapshot blank = cs.snapshotRpgState();
        String skill = SkillRegistry.ALL.get(0).id();
        addRanks(cs, "berserker:p1:0", 4);
        addRanks(cs, "berserker:p0:0", 1);
        addRanks(cs, "berserker:p0:1", 1);
        cs.incrementAbilityScore(1);
        cs.investSkillPoint(skill);
        assertTrue(cs.refundSessionAbilityPoint(1, blank));
        assertTrue(cs.refundSessionRank("berserker:p1:0", blank), "takes the Tier II rank along");
        assertTrue(cs.undoLastAllocation());
        assertEquals(0, cs.getSkillLevel(skill), "Undo skips the refunded point and takes the skill");
        while (cs.undoLastAllocation()) {
            // the rest of the ranks
        }
        assertFalse(cs.hasChangedSince(blank), "the history held exactly what was left");
    }

    @Test
    void refusesWithoutASession() {
        CharacterStats cs = with(5, 1, 0, 1);
        addRanks(cs, "ranger:p0:0", 1);
        cs.investSkillPoint(SkillRegistry.ALL.get(0).id());
        cs.incrementAbilityScore(0);
        assertFalse(cs.refundSessionRank("ranger:p0:0", null));
        assertFalse(cs.refundSessionSkillPoint(SkillRegistry.ALL.get(0).id(), null));
        assertFalse(cs.refundSessionAbilityPoint(0, null));
    }
}
