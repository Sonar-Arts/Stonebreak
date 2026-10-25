package com.stonebreak.rpg.classes;

import com.stonebreak.player.CharacterStats;
import com.stonebreak.player.combat.stealth.StealthConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The talent-tree rules: tier gates count ranks in lower tiers, path nodes need their parent,
 * ranks stop at the node's cap, multiclass slots set the CP price per rank (and re-price when a
 * class empties), and gameplay class checks follow every invested class, not just the primary.
 */
class ClassTreeRulesTest {

    /** A level-1 character with {@code cp} CP to spend and nothing invested. */
    private static CharacterStats withCp(int cp) {
        CharacterStats cs = new CharacterStats(null);
        cs.restore(null, List.of(), Map.of(), Map.of(), Set.of(), cp, 0, 0,
                new int[]{10, 10, 10, 10, 10, 10}, 0, 1, 0);
        return cs;
    }

    private static void addRanks(CharacterStats cs, String key, int n) {
        for (int i = 0; i < n; i++) {
            assertTrue(cs.addRank(key), "rank " + (i + 1) + " of " + key + ": " + cs.lockReason(key));
        }
    }

    @Test
    void everyRegisteredClassHasAFullTree() {
        for (PlayerClassDefinition cls : ClassRegistry.ALL) {
            assertEquals(PlayerClassDefinition.PATHS, cls.paths().size(), cls.id());
            for (int p = 0; p < cls.paths().size(); p++) {
                List<TalentNode> nodes = cls.paths().get(p).nodes();
                for (int t = 0; t < nodes.size(); t++) {
                    String key = ClassRegistry.pathKey(cls.id(), p, t);
                    assertEquals(key, nodes.get(t).key());
                    assertEquals(t, ClassRegistry.tierOf(key));
                    assertEquals(nodes.get(t).type().defaultMaxRank(), ClassRegistry.maxRankOf(key));
                }
            }
            assertEquals(-1, ClassRegistry.tierOf(cls.id() + ":0"), "core abilities sit outside the tiers");
            assertTrue(ClassRegistry.isAbilityKey(cls.id() + ":0"));
            assertFalse(ClassRegistry.isValidKey(cls.id() + ":0"), "and take no ranks");
        }
        assertEquals("One Shot", ClassRegistry.findNode("ranger:p2:3").orElseThrow().name());
        assertFalse(ClassRegistry.isValidKey("ranger:p3:0"));
        assertFalse(ClassRegistry.isValidKey("ranger:2"));
    }

    @Test
    void tierTwoOpensAtFiveRanksInTierOne() {
        CharacterStats cs = withCp(20);
        addRanks(cs, "berserker:p0:0", 1);
        addRanks(cs, "berserker:p1:0", 3);
        assertFalse(cs.canAddRank("berserker:p0:1"));
        assertEquals("Needs 5 ranks in Tier I", cs.lockReason("berserker:p0:1"));
        addRanks(cs, "berserker:p2:0", 1);
        assertTrue(cs.addRank("berserker:p0:1"));
    }

    @Test
    void tierThreeNeedsItsParentInThePath() {
        CharacterStats cs = withCp(40);
        addRanks(cs, "berserker:p0:0", 8);
        addRanks(cs, "berserker:p0:1", 7); // 15 ranks below tier III, none past Tier I in Wrecker
        addRanks(cs, "berserker:p1:0", 1);
        assertEquals("Needs Earthsplitter", cs.lockReason("berserker:p1:2"));
        addRanks(cs, "berserker:p1:1", 1);
        assertTrue(cs.addRank("berserker:p1:2"));
    }

    @Test
    void nodesStopAtTheirMaxRank() {
        CharacterStats cs = withCp(60);
        addRanks(cs, "berserker:p0:0", 8);
        assertEquals("Max rank", cs.lockReason("berserker:p0:0"));
        addRanks(cs, "berserker:p1:0", 8);
        addRanks(cs, "berserker:p0:1", 10);
        addRanks(cs, "berserker:p0:2", 5);
        assertTrue(cs.addRank("berserker:p0:3"));
        assertFalse(cs.addRank("berserker:p0:3"), "a capstone has one rank");
        assertEquals(1, cs.getRank("berserker:p0:3"));
    }

    @Test
    void everyCoreAbilityComesFromAPath() {
        for (PlayerClassDefinition cls : ClassRegistry.ALL) {
            assertEquals(2, cls.abilities().size(), cls.id());
            for (int i = 0; i < cls.abilities().size(); i++) {
                assertFalse(ClassRegistry.pathsGranting(ClassRegistry.coreKey(cls.id(), i)).isEmpty(),
                    cls.id() + " ability " + i + " has a path");
            }
        }
        assertEquals(List.of("Bloodletter", "Frenzy"), ClassRegistry.pathsGranting("berserker:0").stream()
            .map(TalentPath::name).toList());
        assertEquals(List.of("Hunter", "Marksman"), ClassRegistry.pathsGranting("ranger:1").stream()
            .map(TalentPath::name).toList());
    }

    @Test
    void thePathOpenedFirstDecidesTheAbility() {
        CharacterStats cs = withCp(1);
        assertFalse(cs.hasAbility("berserker:0"));
        assertFalse(cs.canAddRank("berserker:0"), "core abilities are not bought");
        assertEquals("Comes with Bloodletter or Frenzy", cs.lockReason("berserker:0"));
        assertEquals("Comes with Wrecker", cs.lockReason("berserker:1"));

        addRanks(cs, "berserker:p1:0", 1); // Wrecker: Skull Crusher, not Rampage
        assertTrue(cs.hasAbility("berserker:1"));
        assertFalse(cs.hasAbility("berserker:0"));
        assertEquals(0, cs.getRemainingCp(), "the ability itself cost nothing");

        assertTrue(cs.undoLastAllocation());
        assertFalse(cs.hasAbility("berserker:1"), "undoing the path's last rank takes the ability back");
    }

    @Test
    void legacyCoreAbilityRanksAreDroppedAndRefunded() {
        CharacterStats cs = new CharacterStats(null);
        cs.restore("ranger", List.of("ranger", "rogue"), Map.of("ranger:0", 1, "rogue:0", 1, "rogue:p0:0", 1),
                Map.of(), Set.of(), 0, 0, 0, new int[]{10, 10, 10, 10, 10, 10}, 0, 1, 0);
        assertEquals(0, cs.getRank("ranger:0"));
        assertEquals(List.of("rogue"), cs.getClassInvestmentOrder(), "Ranger had only a core rank");
        assertEquals(2, cs.getRemainingCp(), "both core ranks refunded at 1 CP");
        assertTrue(cs.hasAbility("rogue:0"), "Assassin still grants Shadow Step");
    }

    @Test
    void thirdClassPaysThePenaltyAndRepricesWhenTheSecondEmpties() {
        CharacterStats cs = withCp(10);
        addRanks(cs, "ranger:p0:0", 1);
        addRanks(cs, "rogue:p0:0", 1);
        assertEquals(2, cs.getCpCostPerRank("berserker"), "a third class costs 1 + P");
        addRanks(cs, "berserker:p0:0", 2);
        assertEquals(List.of("ranger", "rogue", "berserker"), cs.getClassInvestmentOrder());
        assertEquals(10 - 1 - 1 - 4, cs.getRemainingCp());

        assertTrue(cs.removeRank("rogue:p0:0"));
        assertEquals(List.of("ranger", "berserker"), cs.getClassInvestmentOrder());
        assertEquals(1, cs.getCpCostPerRank("berserker"));
        assertEquals(10 - 1 - 2, cs.getRemainingCp(), "Rogue's rank and Berserker's penalty are refunded");
    }

    @Test
    void undoRemovesRanksAndFreesTheSlot() {
        CharacterStats cs = withCp(10);
        addRanks(cs, "ranger:p0:0", 1);
        addRanks(cs, "rogue:p0:0", 1);
        addRanks(cs, "berserker:p0:0", 1);
        assertEquals(10 - 4, cs.getRemainingCp());
        assertTrue(cs.undoLastAllocation());
        assertEquals(List.of("ranger", "rogue"), cs.getClassInvestmentOrder());
        assertEquals(10 - 2, cs.getRemainingCp());
        cs.undoLastAllocation();
        cs.undoLastAllocation();
        assertEquals(List.of(), cs.getClassInvestmentOrder());
        assertNull(cs.getSelectedClassId());
        assertEquals(10, cs.getRemainingCp());
    }

    @Test
    void notEnoughCpNamesTheCost() {
        CharacterStats cs = withCp(3);
        addRanks(cs, "ranger:p0:0", 1);
        addRanks(cs, "rogue:p0:0", 1);
        assertEquals("Not enough CP (costs 2)", cs.lockReason("berserker:p0:0"));
    }

    @Test
    void removeRankRefusesToBreakTheTree() {
        CharacterStats cs = withCp(20);
        addRanks(cs, "berserker:p1:0", 4);
        addRanks(cs, "berserker:p0:0", 1);
        addRanks(cs, "berserker:p0:1", 1);
        assertFalse(cs.canRemoveRank("berserker:p1:0"), "Tier II would lose its gate");
        assertFalse(cs.canRemoveRank("berserker:p0:0"), "Crimson Howl would lose its parent");
        assertTrue(cs.canRemoveRank("berserker:p0:1"));
    }

    @Test
    void secondaryClassesCountForGameplayChecks() {
        CharacterStats cs = withCp(5);
        addRanks(cs, "ranger:p0:0", 1);
        addRanks(cs, "rogue:p0:0", 1);
        assertEquals("ranger", cs.getSelectedClassId(), "the first class stays primary");
        assertTrue(cs.hasClass("rogue"));
        assertFalse(cs.hasClass("arcanist"));
        assertSame(StealthConfig.ROGUE, StealthConfig.forClass(cs));
    }

    @Test
    void joinDataCarriesOrderAndPathRanks() {
        CharacterStats cs = withCp(20);
        addRanks(cs, "rogue:p0:0", 4);
        addRanks(cs, "rogue:p1:0", 1);
        addRanks(cs, "ranger:p0:0", 1);
        CharacterStats joined = new CharacterStats(null);
        joined.applyFromJoinData(cs.toCreationJson());
        assertEquals(List.of("rogue", "ranger"), joined.getClassInvestmentOrder());
        assertEquals(1, joined.getRank("rogue:p1:0"));
        assertEquals(cs.getRemainingCp(), joined.getRemainingCp());
        assertEquals("rogue", joined.getSelectedClassId());
    }

    @Test
    void snapshotsTrackTheInvestmentOrder() {
        CharacterStats cs = withCp(5);
        CharacterStats.RpgSnapshot before = cs.snapshotRpgState();
        addRanks(cs, "ranger:p0:0", 1);
        assertTrue(cs.hasChangedSince(before));
        cs.restoreRpgState(before);
        assertEquals(List.of(), cs.getClassInvestmentOrder());
        assertEquals(5, cs.getRemainingCp());
    }
}
