package com.stonebreak.rpg.proficiency;

import com.stonebreak.items.weapon.WeaponGroup;
import com.stonebreak.player.CharacterStats;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Weapon proficiency ranks are stored as skills but bought with Weapon Training Points:
 * WTP comes from class ranks (per class) plus a starting grant, SP can no longer buy a
 * rank, Expert and Master wait for their character level, Master is the cap, undo
 * refunds, and the rank survives the creation-JSON round trip.
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

    /** A level-{@code level} character with {@code cp} CP to spend and nothing invested. */
    private static CharacterStats withCp(int cp, int level) {
        CharacterStats cs = new CharacterStats(null);
        cs.restore(null, List.of(), Map.of(), Map.of(), Set.of(), cp, 0, 0,
                new int[]{10, 10, 10, 10, 10, 10}, 0, level, 0);
        return cs;
    }

    private static void addRanks(CharacterStats cs, String key, int n) {
        for (int i = 0; i < n; i++) {
            assertTrue(cs.addRank(key), "rank " + (i + 1) + " of " + key);
        }
    }

    @Test
    void skillPointsCannotRaiseProficiency() {
        CharacterStats cs = new CharacterStats(null);
        int sp = cs.getRemainingSkillPoints();
        cs.investSkillPoint(ONE_HANDED);
        assertEquals(ProficiencyRank.UNTRAINED, cs.getProficiency(WeaponGroup.ONE_HANDED));
        assertEquals(sp, cs.getRemainingSkillPoints(), "SP must not buy proficiency");
    }

    @Test
    void theStartingGrantTrainsOneGroup() {
        CharacterStats cs = new CharacterStats(null);
        assertEquals(1, cs.getRemainingWtp());
        assertTrue(cs.trainWeapon(WeaponGroup.ONE_HANDED));
        assertEquals(ProficiencyRank.TRAINED, cs.getProficiency(WeaponGroup.ONE_HANDED));
        assertEquals(0, cs.getRemainingWtp());
        assertFalse(cs.trainWeapon(WeaponGroup.RANGED), "no WTP left");
        assertEquals(ProficiencyRank.UNTRAINED, cs.getProficiency(WeaponGroup.RANGED));
    }

    @Test
    void wtpComesFromRanksCountedPerClass() {
        CharacterStats cs = withCp(20, 1);
        addRanks(cs, "berserker:p0:0", 4);
        assertEquals(1, cs.earnedWtp(), "4 ranks earn nothing beyond the starting grant");
        addRanks(cs, "berserker:p0:0", 1);
        assertEquals(2, cs.earnedWtp(), "5 ranks earn one");
        addRanks(cs, "rogue:p0:0", 4);
        assertEquals(2, cs.earnedWtp(), "5 + 4 in two classes is 1 earned, not 1.8");
    }

    @Test
    void trainingRespectsTheLevelGate() {
        CharacterStats cs = withCp(20, 1);
        addRanks(cs, "berserker:p0:0", 5); // 2 WTP
        assertTrue(cs.trainWeapon(WeaponGroup.ONE_HANDED));
        assertEquals(1, cs.getRemainingWtp());
        assertFalse(cs.canTrainWeapon(WeaponGroup.ONE_HANDED), "Expert needs level 5");
        assertFalse(cs.trainWeapon(WeaponGroup.ONE_HANDED));
        assertEquals(1, cs.getRemainingWtp(), "a refused raise must not spend WTP");

        cs.addXp(800); // level 1 -> 5
        assertEquals(ProficiencyRank.EXPERT.minCharacterLevel(), cs.getLevel());
        assertTrue(cs.trainWeapon(WeaponGroup.ONE_HANDED));
        assertEquals(ProficiencyRank.EXPERT, cs.getProficiency(WeaponGroup.ONE_HANDED));
    }

    @Test
    void masterIsTheCap() {
        CharacterStats cs = withCp(20, 20);
        addRanks(cs, "berserker:p0:0", 8);
        addRanks(cs, "berserker:p1:0", 2);
        cs.setProficiency(WeaponGroup.TWO_HANDED, ProficiencyRank.MASTER);
        assertFalse(cs.trainWeapon(WeaponGroup.TWO_HANDED));
        assertEquals(ProficiencyRank.MASTER, cs.getProficiency(WeaponGroup.TWO_HANDED));
    }

    @Test
    void undoRefundsATrainingPoint() {
        CharacterStats cs = new CharacterStats(null);
        cs.trainWeapon(WeaponGroup.ONE_HANDED);
        assertTrue(cs.undoLastAllocation());
        assertEquals(ProficiencyRank.UNTRAINED, cs.getProficiency(WeaponGroup.ONE_HANDED));
        assertEquals(1, cs.getRemainingWtp());
    }

    @Test
    void overdrawnLegacyProficiencyClampsAndBlocksTraining() {
        CharacterStats cs = new CharacterStats(null);
        cs.setProficiency(WeaponGroup.RANGED, ProficiencyRank.TRAINED);
        cs.setProficiency(WeaponGroup.UNARMED, ProficiencyRank.TRAINED); // 2 spent, 1 earned
        assertEquals(0, cs.getRemainingWtp());
        assertFalse(cs.canTrainWeapon(WeaponGroup.ONE_HANDED));
        assertEquals(ProficiencyRank.TRAINED, cs.getProficiency(WeaponGroup.RANGED), "ranks are not stripped");
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
