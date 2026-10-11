package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataCollection;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.stonebreak.ui.worldHud.StealthHud;
import com.stonebreak.ui.worldHud.WorldMarker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The world-anchored HUD ({@code stonebreak:world.hud} 1, #301): what is drawn over the 3D view in
 * PLAYING, as typed data.
 * <ul>
 *   <li>collection {@code worldMarkers} (identity {@code marker}), in paint order (damage numbers, the Ranger's
 *       prey and snare, Doubt counts, awareness glyphs, name tags): {@code kind} (damage, prey, trap, doubt,
 *       awareness, nametag), {@code x}/{@code y} in device px of the window, {@code text}, {@code size}
 *       (font, logical px), {@code color}, {@code shadow} ({@code #RRGGBBAA}), {@code fraction} (prey health)
 *       and {@code half} (snare half-size, device px). See {@link WorldMarker} for each kind's anchor.</li>
 *   <li>root {@code stealth}: {@code shown}, {@code label}, {@code color}, {@code cooling}, {@code fraction}
 *       (the re-entry bar, 1 → 0).</li>
 * </ul>
 * <b>Projection contract:</b> markers are projected by {@link WorldMarker#project} through the player
 * camera's {@code proj × view} at render time and left out when behind the camera or outside the view
 * ({@code |ndc| > 1}). There is no occlusion: markers read through terrain. The
 * game publishes right before the document paints, so positions are never a frame behind the camera.
 */
public final class WorldHudContracts {

    public static final HostContract WORLD_HUD = HostContract.of("stonebreak:world.hud", 1);

    public static final DataType.ListOf MARKERS = DataType.list(DataType.object("marker", DataType.string(),
        "kind", DataType.string(), "x", DataType.number(), "y", DataType.number(), "text", DataType.string(),
        "size", DataType.number(), "color", DataType.string(), "shadow", DataType.string(),
        "fraction", DataType.number(), "half", DataType.number()), "marker");
    public static final DataType.Obj STEALTH = DataType.object("shown", DataType.bool(), "label", DataType.string(),
        "color", DataType.string(), "cooling", DataType.bool(), "fraction", DataType.number());

    private final DataCollection markers = new DataCollection(MARKERS);
    private final DataCell stealth;
    private List<WorldMarker> lastMarkers = List.of();
    private StealthHud.State lastStealth = StealthHud.State.NONE;

    public WorldHudContracts(UiHost ui) {
        ui.data().register("worldMarkers", markers, WORLD_HUD);
        stealth = ui.data().register("stealth", new DataCell(STEALTH, value(StealthHud.State.NONE)), WORLD_HUD);
    }

    /** UI thread, right before the world HUD paints: republishes only what changed. */
    public void publish(List<WorldMarker> now, StealthHud.State stealthNow) {
        if (!now.equals(lastMarkers)) {
            lastMarkers = List.copyOf(now);
            List<UiValue> rows = new ArrayList<>(now.size());
            for (WorldMarker m : now) {
                rows.add(row(m));
            }
            markers.setAll(rows);
        }
        if (!stealthNow.equals(lastStealth)) {
            lastStealth = stealthNow;
            stealth.set(value(stealthNow));
        }
    }

    /** Back to nothing (world left). */
    public void clear() {
        publish(List.of(), StealthHud.State.NONE);
    }

    private static UiValue row(WorldMarker m) {
        Map<String, UiValue> r = new LinkedHashMap<>();
        r.put("marker", UiValue.of(m.id()));
        r.put("kind", UiValue.of(m.kind().wire()));
        r.put("x", SettingsContract.exact(m.x()));
        r.put("y", SettingsContract.exact(m.y()));
        r.put("text", UiValue.of(m.text()));
        r.put("size", SettingsContract.exact(m.size()));
        r.put("color", CrosshairContracts.hex(m.color()));
        r.put("shadow", CrosshairContracts.hex(m.shadow()));
        r.put("fraction", SettingsContract.exact(m.fraction()));
        r.put("half", SettingsContract.exact(m.half()));
        return new UiValue.Obj(r);
    }

    private static UiValue.Obj value(StealthHud.State s) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        m.put("shown", UiValue.of(s.shown()));
        m.put("label", UiValue.of(s.label()));
        m.put("color", CrosshairContracts.hex(s.labelColor()));
        m.put("cooling", UiValue.of(s.cooling()));
        m.put("fraction", SettingsContract.exact(s.fraction()));
        return new UiValue.Obj(m);
    }
}
