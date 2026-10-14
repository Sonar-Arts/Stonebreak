package com.stonebreak.world.save.serialization;

import com.stonebreak.items.weapon.WeaponGroup;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.rpg.proficiency.ProficiencyRank;
import com.stonebreak.rpg.proficiency.WeaponProficiency;
import com.stonebreak.world.save.model.PlayerData;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Saves carry the multiclass investment order next to the ranks and proficiency; saves that
 * predate it rebuild the order from the ranks and re-derive CP from the level.
 */
class ClassTreePersistenceTest {

    private static final JsonPlayerSerializer serializer = new JsonPlayerSerializer();

    /** The same mapping {@code StateConverter} applies on load. */
    private static CharacterStats load(PlayerData data) {
        CharacterStats cs = new CharacterStats(null);
        cs.restore(data.getSelectedClassId(), data.getClassInvestmentOrder(),
                new HashMap<>(data.getSpentAbilityCp()), new HashMap<>(data.getSkillLevels()),
                new HashSet<>(data.getAcquiredFeatIds()), data.getRemainingCp(),
                data.getRemainingSkillPoints(), data.getRemainingFeatPoints(),
                data.getAbilityScores(), data.getRemainingAp(), data.getLevel(), data.getXp());
        return cs;
    }

    @Test
    void orderRanksAndProficiencySurviveASave() {
        PlayerData data = PlayerData.builder()
                .selectedClassId("rogue")
                .classInvestmentOrder(List.of("rogue", "ranger", "arcanist"))
                .spentAbilityCp(Map.of("rogue:0", 5, "rogue:p0:0", 1, "ranger:0", 1, "arcanist:1", 2))
                .skillLevels(Map.of(WeaponProficiency.skillId(WeaponGroup.RANGED), 1))
                .remainingCp(3)
                .level(12)
                .build();
        CharacterStats cs = load(serializer.deserialize(serializer.serialize(data)));

        assertEquals(List.of("rogue", "ranger", "arcanist"), cs.getClassInvestmentOrder());
        assertEquals(1, cs.getRank("rogue:p0:0"));
        assertEquals(2, cs.getCpCostPerRank("arcanist"));
        assertEquals(3, cs.getRemainingCp(), "a save with an order keeps its stored CP");
        assertEquals(ProficiencyRank.TRAINED, cs.getProficiency(WeaponGroup.RANGED));
    }

    @Test
    void oldSavesRebuildTheOrderAndCp() {
        String legacy = new String(serializer.serialize(PlayerData.builder()
                .selectedClassId("ranger")
                .spentAbilityCp(Map.of("berserker:0", 1, "ranger:0", 1, "ranger:1", 1, "rogue:0", 1))
                .remainingCp(100)
                .level(10)
                .build()), StandardCharsets.UTF_8);
        PlayerData data = serializer.deserialize(legacy.getBytes(StandardCharsets.UTF_8));
        assertNull(data.getClassInvestmentOrder(), "the field is absent in old saves");

        CharacterStats cs = load(data);
        assertEquals(List.of("ranger", "berserker", "rogue"), cs.getClassInvestmentOrder(),
                "selected class first, then registry order");
        assertEquals("ranger", cs.getSelectedClassId());
        // ranger 2 ranks x1 + berserker 1 x1 + rogue 1 x2 = 5 of the 10 CP granted by level 10
        assertEquals(CharacterStats.grantedCp(10) - 5, cs.getRemainingCp());
    }
}
