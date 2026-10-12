package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.DataCollection;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.masonry.MStatPanel;
import com.stonebreak.ui.DebugOverlay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The F3 debug overlay ({@code stonebreak:debug} 1, #301): collection {@code debugCards} (identity
 * {@code card}: ram, vram, uiBudget when a document is monitored, debug), each the {@code MStatPanel} the
 * overlay built on its cadence: {@code column} (left, right), {@code title}, {@code height} (the panel's
 * measured height, the legacy stacking rule, device px), the usage {@code bar}
 * ({@code barFraction}, {@code barColor}, {@code barCaption}) and {@code lines} (a section header, with
 * {@code gap} when the previous line was a row, or a {@code label}/{@code value} row). Published when a
 * card was rebuilt, never per frame.
 */
public final class DebugContracts {

    public static final HostContract DEBUG = HostContract.of("stonebreak:debug", 1);

    private static final DataType.ListOf LINES = DataType.list(DataType.object("line", DataType.string(),
        "section", DataType.bool(), "gap", DataType.bool(), "label", DataType.string(), "value", DataType.string()),
        "line");
    public static final DataType.ListOf CARDS = DataType.list(DataType.object("card", DataType.string(),
        "column", DataType.string(), "title", DataType.string(), "height", DataType.number(),
        "bar", DataType.bool(), "barFraction", DataType.number(), "barColor", DataType.string(),
        "barCaption", DataType.string(), "lines", LINES), "card");

    private final DataCollection cards = new DataCollection(CARDS);
    private DebugOverlay.Cards last;

    public DebugContracts(UiHost ui) {
        ui.data().register("debugCards", cards, DEBUG);
    }

    /** UI thread, right before the overlay paints. */
    public void publish(DebugOverlay.Cards now) {
        if (now == null || now.equals(last)) {
            return; // identical panel objects: nothing was rebuilt
        }
        last = now;
        List<UiValue> rows = new ArrayList<>(4);
        rows.add(card("ram", "left", now.ram()));
        rows.add(card("vram", "left", now.vram()));
        if (now.uiBudget() != null) {
            rows.add(card("uiBudget", "left", now.uiBudget()));
        }
        rows.add(card("debug", "right", now.debug()));
        cards.setAll(rows);
    }

    static UiValue card(String id, String column, MStatPanel p) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        m.put("card", UiValue.of(id));
        m.put("column", UiValue.of(column));
        m.put("title", UiValue.of(p.title()));
        m.put("height", SettingsContract.exact(p.measureHeight()));
        m.put("bar", UiValue.of(p.hasBar()));
        m.put("barFraction", SettingsContract.exact(p.barFraction()));
        m.put("barColor", CrosshairContracts.hex(p.barColor()));
        m.put("barCaption", UiValue.of(p.barCaption() == null ? "" : p.barCaption()));
        List<UiValue> lines = new ArrayList<>();
        boolean prevSection = false;
        for (MStatPanel.Line l : p.lines()) {
            lines.add(new UiValue.Obj(Map.of("line", UiValue.of(String.valueOf(lines.size())),
                "section", UiValue.of(l.section()), "gap", UiValue.of(l.section() && !prevSection),
                "label", UiValue.of(l.label() == null ? "" : l.label()),
                "value", UiValue.of(l.value() == null ? "" : l.value()))));
            prevSection = l.section();
        }
        m.put("lines", new UiValue.Arr(lines));
        return new UiValue.Obj(m);
    }
}
