package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataCollection;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.stonebreak.player.Player;
import com.stonebreak.player.combat.arcanist.ArcanistAbilityController;
import com.stonebreak.rendering.UI.components.hotbar.HealthHeartsRenderer;
import com.stonebreak.ui.HotbarScreen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The gameplay HUD around the hotbar ({@code stonebreak:hud} 1, #300), as the legacy HUD reads it:
 * <ul>
 *   <li>root {@code hud}: {@code present}, {@code heartSize} (device px, the heart sprite's native size
 *       snapped near 28), {@code hearts} (how many: 2 HP each), {@code stamina}/{@code mana} {@code {shown, fraction}} (mana only for the
 *       Arcanist), {@code dodge {ready, fraction, text}}, {@code classId} (whose gauge the
 *       {@code stonebreak:class-gauge} provider paints) and {@code tooltip {shown, text, alpha, slot}} (the
 *       selected item's name over hotbar slot {@code slot}: 1.5 s, then a 0.5 s fade, from {@link HotbarScreen});</li>
 *   <li>collection {@code hearts}: one row per heart ({@code heart:i}, 2 HP each) with its
 *       {@code state} full, half or empty (thresholds 0.75 / 0.25).</li>
 * </ul>
 * Hotbar slots are the {@code stonebreak:hotbar} contract. Republished only when a shown value
 * changed (a regenerating bar changes every frame: that is the HUD's high-frequency path).
 */
public final class HudContracts {

    public static final HostContract HUD = HostContract.of("stonebreak:hud", 1);

    private static final DataType.Obj BAR = DataType.object("shown", DataType.bool(), "fraction", DataType.number());
    public static final DataType.Obj TYPE = DataType.object("present", DataType.bool(), "heartSize", DataType.integer(),
        "hearts", DataType.integer(), "stamina", BAR, "mana", BAR,
        "dodge", DataType.object("ready", DataType.bool(), "fraction", DataType.number(), "text", DataType.string()),
        "classId", DataType.string(),
        "tooltip", DataType.object("shown", DataType.bool(), "text", DataType.string(), "alpha", DataType.number(),
            "slot", DataType.integer()));
    public static final DataType.ListOf HEARTS = DataType.list(
        DataType.object("heart", DataType.string(), "state", DataType.string()), "heart");

    /** The game behind the HUD. */
    public interface Services {
        /** The local player, or null outside a world. */
        default Player hudPlayer() {
            return null;
        }

        /** The hotbar's tooltip state, or null. */
        default HotbarScreen hotbarScreen() {
            return null;
        }

        /** The heart sprite's drawn edge in device px ({@link HealthHeartsRenderer#heartSize}). */
        default int heartSize() {
            return HealthHeartsRenderer.heartSize();
        }
    }

    /** What the HUD shows, as primitives: equal snapshots publish nothing. */
    private record Snapshot(boolean present, int heartSize, int hearts, boolean staminaShown, float stamina,
                            boolean manaShown, float mana, float dodge, String classId, boolean tooltipShown,
                            String tooltip, float tooltipAlpha, int slot) {
    }

    private final Services services;
    private final DataCell cell;
    private final DataCollection hearts = new DataCollection(HEARTS);
    private Snapshot last;
    private String[] heartStates = new String[0];
    private int heartSize = -1;

    public HudContracts(UiHost ui, Services services) {
        this.services = services;
        cell = ui.data().register("hud", new DataCell(TYPE, value(empty())), HUD);
        ui.data().register("hearts", hearts, HUD);
    }

    /** UI thread, once per frame. An unchanged HUD allocates only its snapshot. */
    public void poll() {
        Player p = services.hudPlayer();
        if (p != null && heartSize < 0) {
            heartSize = services.heartSize(); // textures load once; the size never changes after
        }
        Snapshot now = p == null ? empty() : snapshot(p, services.hotbarScreen());
        if (!now.equals(last)) {
            last = now;
            cell.set(value(now));
        }
        publishHearts(p);
    }

    /** Back to no player (world left). */
    public void clear() {
        last = empty();
        cell.set(value(last));
        heartStates = new String[0];
        hearts.setAll(List.of());
    }

    private Snapshot snapshot(Player p, HotbarScreen hotbar) {
        float maxStamina = p.getMaxStamina();
        String classId = p.getCharacterStats() == null ? "" : Objects.toString(p.getCharacterStats().getSelectedClassId(), "");
        boolean mana = ArcanistAbilityController.CLASS_ID.equals(classId) && p.getMaxMana() > 0;
        float dodge = p.getDodge() == null ? 1f : Math.max(0f, Math.min(1f, p.getDodge().getCooldownProgress()));
        boolean tip = hotbar != null && hotbar.shouldShowTooltip() && hotbar.getTooltipText() != null;
        return new Snapshot(true, heartSize, (int) Math.ceil(p.getMaxHealth() / 2.0f), maxStamina > 0,
            maxStamina > 0 ? Math.max(0f, Math.min(1f, p.getStamina() / maxStamina)) : 0f,
            mana, mana ? Math.max(0f, Math.min(1f, p.getMana() / p.getMaxMana())) : 0f,
            dodge, classId, tip, tip ? hotbar.getTooltipText() : "", tip ? hotbar.getTooltipAlpha() : 0f,
            hotbar == null ? 0 : hotbar.getSelectedSlotIndex());
    }

    private static Snapshot empty() {
        return new Snapshot(false, 0, 0, false, 0, false, 0, 1, "", false, "", 0, 0);
    }

    private static UiValue.Obj value(Snapshot s) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        m.put("present", UiValue.of(s.present()));
        m.put("heartSize", UiValue.of(s.heartSize()));
        m.put("hearts", UiValue.of(s.hearts()));
        m.put("stamina", bar(s.staminaShown(), s.stamina()));
        m.put("mana", bar(s.manaShown(), s.mana()));
        Map<String, UiValue> d = new LinkedHashMap<>();
        boolean ready = s.dodge() >= 1f;
        d.put("ready", UiValue.of(ready));
        d.put("fraction", SettingsContract.exact(s.dodge()));
        d.put("text", UiValue.of(ready ? "Dodge: Ready" : "Dodge"));
        m.put("dodge", new UiValue.Obj(d));
        m.put("classId", UiValue.of(s.classId()));
        Map<String, UiValue> t = new LinkedHashMap<>();
        t.put("shown", UiValue.of(s.tooltipShown()));
        t.put("text", UiValue.of(s.tooltip()));
        t.put("alpha", SettingsContract.exact(s.tooltipAlpha()));
        t.put("slot", UiValue.of(s.slot()));
        m.put("tooltip", new UiValue.Obj(t));
        return new UiValue.Obj(m);
    }

    private static UiValue.Obj bar(boolean shown, float fraction) {
        return new UiValue.Obj(Map.of("shown", UiValue.of(shown), "fraction", SettingsContract.exact(fraction)));
    }

    /** One row per heart, republished when the count or any heart's sprite changed. */
    private void publishHearts(Player p) {
        int total = p == null ? 0 : (int) Math.ceil(p.getMaxHealth() / 2.0f);
        float filled = p == null ? 0f : p.getHealth() / 2.0f;
        boolean changed = total != heartStates.length;
        if (!changed) {
            for (int i = 0; i < total; i++) {
                if (!heartStates[i].equals(HealthHeartsRenderer.heartState(Math.max(0f, Math.min(1f, filled - i))))) {
                    changed = true;
                    break;
                }
            }
        }
        if (!changed) {
            return;
        }
        heartStates = new String[total];
        List<UiValue> rows = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            heartStates[i] = HealthHeartsRenderer.heartState(Math.max(0f, Math.min(1f, filled - i)));
            rows.add(new UiValue.Obj(Map.of("heart", UiValue.of("heart:" + i), "state", UiValue.of(heartStates[i]))));
        }
        hearts.setAll(rows);
    }
}
