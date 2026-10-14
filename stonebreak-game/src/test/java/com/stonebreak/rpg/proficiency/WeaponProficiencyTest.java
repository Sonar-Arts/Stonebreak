package com.stonebreak.rpg.proficiency;

import com.stonebreak.items.weapon.WeaponGroup;
import com.stonebreak.player.CharacterStats;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Weapon proficiency ranks ride on the skill system: SP raises a group's rank one
 * step at a time, Expert and Master wait for their character level, Master is the
 * cap, undo refunds, and the rank survives the creation-JSON round trip.
 */
class WeaponProficiencyTest {

    private static final String ONE_HANDED = WeaponProficiency.skillId(WeaponGroup.ONE_HANDED);

    @Test
    void skillIdsMapBackToTheirGroups() {
        for (WeaponGroup group : WeaponGroup.values()) {
            assertEquals(Optional.of(group), WeaponProficiency.groupOf(WeaponProficiency.skillId(group)));
        }
        assertEquals(Optional.empty(), WeaponProficiency.groupOf("stealth"));
        assertEquals(Optional.empty(), WeaponProficiency.groupOf("proficiency.polearm"));
        assertEquals(WeaponGroup.values().length, WeaponProficiency.SKILLS.size());
    }

    @Test
    void ranksClampAndHigherRanksSwingFaster() {
        assertEquals(ProficiencyRank.UNTRAINED, ProficiencyRank.fromLevel(-1));
        assertEquals(ProficiencyRank.MASTER, ProficiencyRank.fromLevel(9));
        assertNull(ProficiencyRank.MASTER.next());
        for (ProficiencyRank rank : ProficiencyRank.values()) {
            ProficiencyRank next = rank.next();
            if (next != null) {
                assertTrue(next.attackSpeedMultiplier() > rank.attackSpeedMultiplier());
            }
        }
    }

    @Test
    void newCharactersAreUntrainedInEveryGroup() {
        CharacterStats cs = new CharacterStats(null);
        for (WeaponGroup group : WeaponGroup.values()) {
            assertEquals(ProficiencyRank.UNTRAINED, cs.getProficiency(group));
        }
    }

    @Test
    void skillPointsRaiseTheRankUntilTheLevelGate() {
        CharacterStats cs = new CharacterStats(null);
        cs.investSkillPoint(ONE_HANDED);
        assertEquals(ProficiencyRank.TRAINED, cs.getProficiency(WeaponGroup.ONE_HANDED));

        int sp = cs.getRemainingSkillPoints();
        cs.investSkillPoint(ONE_HANDED); // Expert needs level 5
        assertEquals(ProficiencyRank.TRAINED, cs.getProficiency(WeaponGroup.ONE_HANDED));
        assertEquals(sp, cs.getRemainingSkillPoints(), "a refused raise must not spend SP");

        cs.addXp(800); // level 1 -> 5
        assertEquals(ProficiencyRank.EXPERT.minCharacterLevel(), cs.getLevel());
        cs.investSkillPoint(ONE_HANDED);
        assertEquals(ProficiencyRank.EXPERT, cs.getProficiency(WeaponGroup.ONE_HANDED));
    }

    @Test
    void masterIsTheCap() {
        CharacterStats cs = new CharacterStats(null);
        cs.addXp(100_000);
        cs.setProficiency(WeaponGroup.TWO_HANDED, ProficiencyRank.MASTER);
        int sp = cs.getRemainingSkillPoints();
        cs.investSkillPoint(WeaponProficiency.skillId(WeaponGroup.TWO_HANDED));
        assertEquals(ProficiencyRank.MASTER, cs.getProficiency(WeaponGroup.TWO_HANDED));
        assertEquals(sp, cs.getRemainingSkillPoints());
    }

    @Test
    void undoRefundsAProficiencyPoint() {
        CharacterStats cs = new CharacterStats(null);
        int sp = cs.getRemainingSkillPoints();
        cs.investSkillPoint(ONE_HANDED);
        assertTrue(cs.undoLastAllocation());
        assertEquals(ProficiencyRank.UNTRAINED, cs.getProficiency(WeaponGroup.ONE_HANDED));
        assertEquals(sp, cs.getRemainingSkillPoints());
    }

    @Test
    void settingUntrainedClearsTheStoredLevel() {
        CharacterStats cs = new CharacterStats(null);
        cs.setProficiency(WeaponGroup.RANGED, ProficiencyRank.EXPERT);
        cs.setProficiency(WeaponGroup.RANGED, ProficiencyRank.UNTRAINED);
        assertFalse(cs.getSkillLevels().containsKey(WeaponProficiency.skillId(WeaponGroup.RANGED)));
    }

    @Test
    void rankSurvivesTheCreationJsonRoundTrip() {
        CharacterStats cs = new CharacterStats(null);
        cs.setProficiency(WeaponGroup.RANGED, ProficiencyRank.EXPERT);
        CharacterStats joined = new CharacterStats(null);
        joined.applyFromJoinData(cs.toCreationJson());
        assertEquals(ProficiencyRank.EXPERT, joined.getProficiency(WeaponGroup.RANGED));
    }
}
