package com.stonebreak.rpg.proficiency;

import com.stonebreak.items.weapon.WeaponGroup;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.player.PlayerConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a Weapons skill list shows, shared by the character sheet and character creation: one row per
 * {@link WeaponGroup} with its proficiency rank and what the next rank needs.
 */
public final class WeaponTraining {

    /**
     * @param note  the next rank and its attack speed, the level that unlocks it, or "Mastered"
     * @param gated true when the next rank waits on character level (or there is none)
     */
    public record Row(WeaponGroup group, String name, String rank, String note, boolean gated, boolean canTrain) {}

    private WeaponTraining() {
    }

    public static List<Row> rows(CharacterStats stats) {
        List<Row> rows = new ArrayList<>();
        WeaponGroup[] groups = WeaponGroup.values();
        for (int i = 0; i < groups.length; i++) {
            WeaponGroup g = groups[i];
            ProficiencyRank rank = stats.getProficiency(g);
            ProficiencyRank next = rank.next();
            boolean gated = next != null && stats.getLevel() < next.minCharacterLevel();
            String note;
            if (next == null) {
                note = "Mastered";
            } else if (gated) {
                note = next.getDisplayName() + " unlocks at level " + next.minCharacterLevel();
            } else {
                note = "Next: " + next.getDisplayName()
                    + String.format(Locale.ROOT, " (x%.2f speed)", next.attackSpeedMultiplier());
            }
            rows.add(new Row(g, WeaponProficiency.SKILLS.get(i).name(), rank.getDisplayName(), note,
                gated || next == null, stats.canTrainWeapon(g)));
        }
        return rows;
    }

    /** How WTP is earned, for under the list. */
    public static String hint() {
        return "Earn 1 WTP per " + PlayerConstants.WTP_CLASS_RANKS_PER_POINT
            + " ranks in each class. Any class's ranks train any weapon.";
    }

    /** The group named {@code name} ({@link WeaponGroup#name()}), or null. */
    public static WeaponGroup group(String name) {
        for (WeaponGroup g : WeaponGroup.values()) {
            if (g.name().equals(name)) return g;
        }
        return null;
    }
}
