package com.stonebreak.ui.fidelity;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.masonry.MDropdown;
import com.openmason.engine.ui.masonry.MWidget;
import com.stonebreak.ui.settingsMenu.SettingsMenu;
import com.stonebreak.ui.settingsMenu.config.CategoryState;
import com.stonebreak.ui.settingsMenu.managers.StateManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The legacy side of the settings gate (#299): {@link SettingsFixtures} menus drawn by
 * {@code SkijaSettingsRenderer}, parts from its layout sink, hit regions probed through its mouse
 * handler. A press on a part is {@code press <part>} ({@code scrollbar} for the scrollbar); with a
 * dropdown open only its header and list are compared (the list covers what is under it), with
 * the UI-scale confirmation up only Keep and Revert. Not a test class.
 */
public final class LegacySettingsCapture implements MigrationGate.Renderer {

    static final String ACTION = "stonebreak:screen.settings.";
    /** Parts that are drawn, not pressed. */
    static final List<String> PANELS = List.of("viewport", "dialog");

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        int w = c.viewport().width();
        int h = c.viewport().height();
        String v = c.variant();
        try (LegacyUiRaster raster = new LegacyUiRaster(w, h, c.viewport().uiScale())) {
            UiValue.Obj before = SettingsFixtures.pin();
            SettingsMenu m = SettingsFixtures.menu(raster.backend(), v, w, h);
            try {
                Map<String, float[]> raw = new LinkedHashMap<>();
                m.renderer().setLayoutSink(raw::put);
                m.layout(w, h);
                m.renderer().setLayoutSink(null);
                Map<String, float[]> parts = parts(raw, m.getStateManager());
                String hover = SettingsFixtures.hover(v);
                if (!hover.isEmpty()) {
                    float[] r = raw.get(hover);
                    m.handleMouseMove(r[0] + r[2] / 2f, r[1] + r[3] / 2f, w, h);
                }
                raster.reset();
                m.render(w, h);
                var image = raster.capture();
                Map<String, float[]> hits = new LinkedHashMap<>();
                Map<String, String> actions = new LinkedHashMap<>();
                parts.forEach((name, r) -> {
                    if (PANELS.contains(name)) {
                        return;
                    }
                    hits.put(name, LegacyDeathCapture.probeRegion((x, y) -> {
                        m.handleMouseMove(x, y, w, h);
                        return hovered(m.getStateManager(), name, x, y, raw);
                    }, r, w, h));
                    actions.put(name, click(raw, parts, name, r[0] + r[2] / 2f, r[1] + r[3] / 2f, w, h));
                });
                return new MigrationGate.Capture(image, parts, hits, actions);
            } finally {
                m.dispose();
                SettingsFixtures.restore(before);
            }
        }
    }

    /** The compared parts: everything, or the open dropdown's header and list, or the confirmation. */
    static Map<String, float[]> parts(Map<String, float[]> raw, StateManager st) {
        Map<String, float[]> out = new LinkedHashMap<>();
        if (st.isUiScaleConfirmActive()) {
            for (String n : List.of("dialog", "keep", "revert")) {
                out.put(n, raw.get(n));
            }
            return out;
        }
        MDropdown open = st.openDropdown();
        if (open != null) {
            String header = "row" + rowOf(st, open);
            out.put(header, raw.get(header));
            raw.forEach((n, r) -> {
                if (n.startsWith("item")) {
                    out.put(n, r);
                }
            });
            return out;
        }
        raw.forEach((n, r) -> {
            if (!n.equals("dialog")) {
                out.put(n, r);
            }
        });
        return out;
    }

    static int rowOf(StateManager st, MWidget w) {
        CategoryState.SettingType[] s = st.getSelectedCategory().getSettings();
        for (int i = 0; i < s.length; i++) {
            if (st.widget(s[i]) == w) {
                return i;
            }
        }
        return -1;
    }

    private static boolean hovered(StateManager st, String part, float x, float y, Map<String, float[]> raw) {
        if (part.startsWith("category")) {
            return st.getCategoryButtons().get(Integer.parseInt(part.substring(8))).isHovered();
        }
        if (part.startsWith("item")) {
            MDropdown d = st.openDropdown();
            return d != null && d.itemUnderMouse(x, y) == Integer.parseInt(part.substring(4));
        }
        if (part.startsWith("row")) {
            MWidget w = st.widget(st.getSelectedCategory().getSettings()[Integer.parseInt(part.substring(3))]);
            // a row is pressed only inside the viewport's clip (the scrollbar takes its strip first)
            return w.isHovered() && inside(raw.get("viewport"), x, y) && !inside(raw.get("scrollbar"), x, y);
        }
        if (part.equals("scrollbar")) {
            return inside(raw.get("scrollbar"), x, y);
        }
        return switch (part) {
            case "apply" -> st.getApplyButton().isHovered();
            case "back" -> st.getBackButton().isHovered();
            case "keep" -> st.getKeepUiScaleButton().isHovered();
            case "revert" -> st.getRevertUiScaleButton().isHovered();
            default -> false;
        };
    }

    /**
     * What a press at a part's centre reaches, by the legacy mouse handler's precedence: with the
     * confirmation or a dropdown open the compared parts are on top (the press is theirs); else the
     * scrollbar, the categories, the rows inside the viewport, Apply, Back, and otherwise nothing
     * ({@code press none}: the document's catch-all, which only closes a dropdown).
     */
    static String click(Map<String, float[]> raw, Map<String, float[]> parts, String part, float x, float y,
                        int w, int h) {
        if (x < 0 || y < 0 || x > w || y > h) {
            return "fired []";
        }
        if (parts.containsKey("keep") || parts.containsKey("item0")) {
            return ACTION + "press " + part;
        }
        if (inside(raw.get("scrollbar"), x, y)) {
            return ACTION + "scrollbar";
        }
        for (Map.Entry<String, float[]> e : raw.entrySet()) {
            String n = e.getKey();
            boolean row = n.startsWith("row");
            if ((n.startsWith("category") || (row && inside(raw.get("viewport"), x, y))) && inside(e.getValue(), x, y)) {
                return ACTION + "press " + n;
            }
        }
        for (String n : List.of("apply", "back")) {
            if (inside(raw.get(n), x, y)) {
                return ACTION + "press " + n;
            }
        }
        return ACTION + "press none";
    }

    static boolean inside(float[] r, float x, float y) {
        return r != null && x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3];
    }
}
