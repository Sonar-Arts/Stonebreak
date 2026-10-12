package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.stonebreak.ui.crosshair.CrosshairSettings;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The crosshair as it is applied ({@code stonebreak:crosshair} 1, #301): root {@code crosshair} with
 * {@code style} (SIMPLE_CROSS, DOT, CIRCLE, SQUARE, T_SHAPE, PLUS_DOT), {@code size}, {@code thickness},
 * {@code gap} and {@code outlineThickness} in device px (the crosshair ignores the UI scale),
 * {@code outline}, and the drawn colours {@code color} (fill) and {@code edge} (outline) as
 * {@code #RRGGBBAA} with the opacity folded in. The values are the ones the game applied
 * ({@code CrosshairConfigurator} at start-up and on settings Apply), read from the
 * {@link CrosshairSettings} that keeps them, not the settings being edited.
 */
public final class CrosshairContracts {

    public static final HostContract CROSSHAIR = HostContract.of("stonebreak:crosshair", 1);

    public static final DataType.Obj TYPE = DataType.object("style", DataType.string(), "size", DataType.number(),
        "thickness", DataType.number(), "gap", DataType.number(), "outline", DataType.bool(),
        "outlineThickness", DataType.number(), "color", DataType.string(), "edge", DataType.string());

    /** The game behind the crosshair. */
    public interface Services {
        /** The applied crosshair, or null (no renderer yet: the defaults are shown). */
        default CrosshairSettings crosshair() {
            return null;
        }
    }

    /** What the crosshair shows, as primitives: equal snapshots publish nothing. */
    record Snapshot(String style, float size, float thickness, float gap, boolean outline, float outlineThickness,
                    int color, int edge) {

        static Snapshot of(CrosshairSettings c) {
            return new Snapshot(c.getStyle().name(), c.getSize(), c.getThickness(), c.getGap(), c.hasOutline(),
                c.getOutlineThickness(), c.fillArgb(), c.edgeArgb());
        }

        UiValue.Obj value() {
            Map<String, UiValue> m = new LinkedHashMap<>();
            m.put("style", UiValue.of(style));
            m.put("size", SettingsContract.exact(size));
            m.put("thickness", SettingsContract.exact(thickness));
            m.put("gap", SettingsContract.exact(gap));
            m.put("outline", UiValue.of(outline));
            m.put("outlineThickness", SettingsContract.exact(outlineThickness));
            m.put("color", hex(color));
            m.put("edge", hex(edge));
            return new UiValue.Obj(m);
        }
    }

    /** The default configuration, shown before (or without) a game. */
    private static final Snapshot DEFAULTS = Snapshot.of(new CrosshairSettings());

    private final Services services;
    private final DataCell cell;
    private Snapshot last = DEFAULTS;

    public CrosshairContracts(UiHost ui, Services services) {
        this.services = services;
        cell = ui.data().register("crosshair", new DataCell(TYPE, DEFAULTS.value()), CROSSHAIR);
    }

    /** UI thread, once per frame: republishes only after an Apply changed something. */
    public void poll() {
        CrosshairSettings c = services.crosshair();
        Snapshot now = c == null ? DEFAULTS : Snapshot.of(c);
        if (!now.equals(last)) {
            last = now;
            cell.set(now.value());
        }
    }

    /** {@code #RRGGBBAA} of an ARGB colour. */
    static UiValue hex(int argb) {
        return UiValue.of(String.format("#%02X%02X%02X%02X", (argb >>> 16) & 0xFF, (argb >>> 8) & 0xFF, argb & 0xFF,
            (argb >>> 24) & 0xFF));
    }
}
