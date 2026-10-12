package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.DataType;
import com.stonebreak.player.CharacterStats;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The local character as the inventory screen's side columns show it ({@code stonebreak:player.character}
 * 1, #300): level and XP, the HP/MP/SP vital bars and the six ability scores, read from
 * {@link CharacterStats} with each value also written the way the legacy screen writes it
 * ({@code levelText} "Level 3", {@code xpText} "40 / 200 xp", a vital's {@code text} "17/20", an
 * ability's {@code text} "14 (+2)") and each bar's {@code fraction} as the legacy widgets clamp it. A
 * record so the host republishes only when something changed.
 *
 * <pre>{@code
 * { present, level, levelText, xp, xpNext, xpText, xpFraction,
 *   hp: {value, max, text, fraction}, mp: {...}, sp: {...},
 *   str: {score, text, fraction}, dex, con, int, wis, cha }
 * }</pre>
 */
public record CharacterRecord(boolean present, int level, int xp, int xpNext, float[] vitals, int[] scores) {

    /** Ability keys in {@link CharacterStats} order. */
    public static final List<String> ABILITIES = List.of("str", "dex", "con", "int", "wis", "cha");
    public static final List<String> VITALS = List.of("hp", "mp", "sp");

    private static final DataType.Obj VITAL = DataType.object("value", DataType.number(), "max", DataType.number(),
        "text", DataType.string(), "fraction", DataType.number());
    private static final DataType.Obj ABILITY = DataType.object("score", DataType.integer(), "text", DataType.string(),
        "fraction", DataType.number());

    public static final DataType.Obj TYPE = type();

    /** No character (menu, loading). */
    public static final CharacterRecord NONE = new CharacterRecord(false, 0, 0, 0, new float[6], new int[6]);

    public static CharacterRecord of(CharacterStats s) {
        if (s == null) {
            return NONE;
        }
        return new CharacterRecord(true, s.getLevel(), s.getXp(), s.getXpForNextLevel(),
            new float[]{s.getHealth(), s.getMaxHealth(), s.getMana(), s.getMaxMana(), s.getStamina(), s.getMaxStamina()},
            s.getAbilityScores());
    }

    private static DataType.Obj type() {
        Map<String, DataType> f = new LinkedHashMap<>();
        f.put("present", DataType.bool());
        f.put("level", DataType.integer());
        f.put("levelText", DataType.string());
        f.put("xp", DataType.integer());
        f.put("xpNext", DataType.integer());
        f.put("xpText", DataType.string());
        f.put("xpFraction", DataType.number());
        VITALS.forEach(v -> f.put(v, VITAL));
        ABILITIES.forEach(a -> f.put(a, ABILITY));
        return DataType.object(f);
    }

    public UiValue.Obj value() {
        Map<String, UiValue> m = new LinkedHashMap<>();
        m.put("present", UiValue.of(present));
        m.put("level", UiValue.of(level));
        m.put("levelText", UiValue.of("Level " + level));
        m.put("xp", UiValue.of(xp));
        m.put("xpNext", UiValue.of(xpNext));
        m.put("xpText", UiValue.of(xp + " / " + xpNext + " xp"));
        // the legacy level widget: min(1, xp / xpMax) in float, 0 without a next level
        m.put("xpFraction", SettingsContract.exact(xpNext > 0 ? Math.min(1f, (float) xp / xpNext) : 0f));
        for (int i = 0; i < VITALS.size(); i++) {
            float v = vitals[2 * i];
            float max = vitals[2 * i + 1];
            Map<String, UiValue> vital = new LinkedHashMap<>();
            vital.put("value", SettingsContract.exact(v));
            vital.put("max", SettingsContract.exact(max));
            vital.put("text", UiValue.of((int) v + "/" + (int) max)); // MVitalBar truncates both
            vital.put("fraction", SettingsContract.exact(max > 0f ? Math.min(1f, Math.max(0f, v / max)) : 0f));
            m.put(VITALS.get(i), new UiValue.Obj(vital));
        }
        for (int i = 0; i < ABILITIES.size(); i++) {
            int score = scores[i];
            int mod = Math.floorDiv(score - 10, 2); // CharacterStats.getModifier
            Map<String, UiValue> ability = new LinkedHashMap<>();
            ability.put("score", UiValue.of(score));
            ability.put("text", UiValue.of(score + " (" + (mod >= 0 ? "+" : "") + mod + ")"));
            ability.put("fraction", SettingsContract.exact(Math.min(1f, Math.max(0f, score / 30f)))); // MStatRow clamps
            m.put(ABILITIES.get(i), new UiValue.Obj(ability));
        }
        return new UiValue.Obj(m);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CharacterRecord r && present == r.present && level == r.level && xp == r.xp
            && xpNext == r.xpNext && java.util.Arrays.equals(vitals, r.vitals) && java.util.Arrays.equals(scores, r.scores);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(present, level, xp, xpNext, java.util.Arrays.hashCode(vitals),
            java.util.Arrays.hashCode(scores));
    }
}
