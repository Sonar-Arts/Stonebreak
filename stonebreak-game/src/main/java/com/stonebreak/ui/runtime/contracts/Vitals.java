package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.DataType;
import com.stonebreak.player.Player;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The local player's vitals for HUD documents ({@code stonebreak:player.vitals}): the values the
 * legacy HUD bars read. A record so the host republishes only when one actually changed.
 */
public record Vitals(float health, float maxHealth, float stamina, float maxStamina, float mana, float maxMana,
                     int level, int xp, int xpNext, boolean dead, boolean present) {

    public static final DataType.Obj TYPE = DataType.object("present", DataType.bool(), "health", DataType.number(),
        "maxHealth", DataType.number(), "stamina", DataType.number(), "maxStamina", DataType.number(),
        "mana", DataType.number(), "maxMana", DataType.number(), "level", DataType.integer(), "xp", DataType.integer(),
        "xpNext", DataType.integer(), "dead", DataType.bool());

    /** No player (menu, loading). */
    public static final Vitals NONE = new Vitals(0, 0, 0, 0, 0, 0, 0, 0, 0, false, false);

    public static Vitals of(Player p) {
        if (p == null) {
            return NONE;
        }
        var stats = p.getCharacterStats();
        // Hundredths: regenerating bars change every frame; a HUD cannot show finer steps anyway.
        return new Vitals(q(p.getHealth()), q(p.getMaxHealth()), q(p.getStamina()), q(p.getMaxStamina()),
            q(p.getMana()), q(p.getMaxMana()), stats == null ? 0 : stats.getLevel(), stats == null ? 0 : stats.getXp(),
            stats == null ? 0 : stats.getXpForNextLevel(), p.isDead(), true);
    }

    private static float q(float v) {
        return Float.isFinite(v) ? Math.round(v * 100f) / 100f : 0f;
    }

    public UiValue.Obj value() {
        Map<String, UiValue> m = new LinkedHashMap<>();
        m.put("present", UiValue.of(present));
        m.put("health", SettingsContract.exact(health));
        m.put("maxHealth", SettingsContract.exact(maxHealth));
        m.put("stamina", SettingsContract.exact(stamina));
        m.put("maxStamina", SettingsContract.exact(maxStamina));
        m.put("mana", SettingsContract.exact(mana));
        m.put("maxMana", SettingsContract.exact(maxMana));
        m.put("level", UiValue.of(level));
        m.put("xp", UiValue.of(xp));
        m.put("xpNext", UiValue.of(xpNext));
        m.put("dead", UiValue.of(dead));
        return new UiValue.Obj(m);
    }
}
