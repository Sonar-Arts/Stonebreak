package com.stonebreak.rpg.proficiency;

import com.stonebreak.items.weapon.WeaponGroup;
import com.stonebreak.rpg.skills.SkillDefinition;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Weapon proficiencies are skills: each {@link WeaponGroup} has a skill id
 * ({@code proficiency.one_handed}, ...) whose level is the ordinal of its
 * {@link ProficiencyRank}. That reuses SP investment, undo, snapshots, saves and
 * multiplayer sync, and an absent level (old saves) reads as Untrained.
 */
public final class WeaponProficiency {

    private static final String SKILL_PREFIX = "proficiency.";

    /** One skill per weapon group, in {@link WeaponGroup} order. */
    public static final List<SkillDefinition> SKILLS = Arrays.stream(WeaponGroup.values())
            .map(g -> new SkillDefinition(skillId(g), g.getDisplayName() + " Weapons",
                    "Swing " + g.getDisplayName().toLowerCase(Locale.ROOT) + " weapons faster."))
            .toList();

    private WeaponProficiency() {
    }

    public static String skillId(WeaponGroup group) {
        return SKILL_PREFIX + group.name().toLowerCase(Locale.ROOT);
    }

    /** The weapon group a skill id tracks, or empty for ordinary skills. */
    public static Optional<WeaponGroup> groupOf(String skillId) {
        if (skillId == null || !skillId.startsWith(SKILL_PREFIX)) {
            return Optional.empty();
        }
        String name = skillId.substring(SKILL_PREFIX.length()).toUpperCase(Locale.ROOT);
        return Arrays.stream(WeaponGroup.values()).filter(g -> g.name().equals(name)).findFirst();
    }

    /** Whether a character at {@code characterLevel} may advance past {@code current}. */
    public static boolean canRaise(ProficiencyRank current, int characterLevel) {
        ProficiencyRank next = current.next();
        return next != null && characterLevel >= next.minCharacterLevel();
    }
}
