package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.ui.worldSelect.WorldSelectScreen;
import com.stonebreak.ui.worldSelect.managers.WorldStateManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The legacy side of the world select gate (#299): {@link WorldSelectFixtures} screens drawn by
 * {@code SkijaWorldSelectRenderer}, parts from its layout sink, hit regions probed through its mouse
 * handler and each part's click resolved by the legacy click rules (card over rows, dialog over
 * everything, disabled buttons do nothing = {@code "fired []"}). Not a test class.
 */
public final class LegacyWorldSelectCapture implements MigrationGate.Renderer {

    static final String ACTION = "stonebreak:screen.world-select.";
    /** The parts a dialog case compares (everything else is under its scrim). */
    static final List<String> DIALOG_PARTS = List.of("dialog", "confirm", "cancel");
    /** Parts that are drawn, not pressed. */
    static final List<String> PANELS = List.of("card", "dialog");

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        int w = c.viewport().width();
        int h = c.viewport().height();
        String v = c.variant();
        try (LegacyUiRaster raster = new LegacyUiRaster(w, h, c.viewport().uiScale())) {
            WorldSelectScreen s = WorldSelectFixtures.screen(raster.backend(), v);
            try {
                Map<String, float[]> raw = new LinkedHashMap<>();
                s.renderer().setLayoutSink(raw::put);
                s.render(w, h);
                s.renderer().setLayoutSink(null);
                Map<String, float[]> parts = order(raw, v.startsWith("delete"));
                String hover = WorldSelectFixtures.hover(v);
                if (!hover.isEmpty()) {
                    float[] r = parts.get(hover);
                    s.handleMouseMove(r[0] + r[2] / 2f, r[1] + r[3] / 2f, w, h);
                }
                raster.reset();
                s.render(w, h);
                var image = raster.capture();
                int card = WorldSelectFixtures.cardWorld(v);
                Map<String, float[]> hits = new LinkedHashMap<>();
                Map<String, String> actions = new LinkedHashMap<>();
                parts.forEach((name, r) -> {
                    if (PANELS.contains(name)) {
                        return;
                    }
                    hits.put(name, LegacyDeathCapture.probeRegion((x, y) -> {
                        WorldSelectFixtures.openCard(s, card); // a row move closes it
                        s.handleMouseMove(x, y, w, h);
                        // over the card its own row reads hovered too: that is the card answering
                        return hovered(s, name) && !(name.startsWith("row") && inside(parts.get("card"), x, y));
                    }, r, w, h));
                    actions.put(name, click(s, parts, r[0] + r[2] / 2f, r[1] + r[3] / 2f));
                });
                return new MigrationGate.Capture(image, parts, hits, actions);
            } finally {
                s.dispose();
            }
        }
    }

    /** Card parts first, then the rows and buttons (the order the document capture clicks them in). */
    static Map<String, float[]> order(Map<String, float[]> raw, boolean dialog) {
        Map<String, float[]> out = new LinkedHashMap<>();
        for (String n : List.of("card", "folder", "backup")) {
            if (!dialog && raw.containsKey(n)) {
                out.put(n, raw.get(n));
            }
        }
        raw.forEach((n, r) -> {
            if (dialog ? DIALOG_PARTS.contains(n) : !out.containsKey(n)) {
                out.put(n, r);
            }
        });
        return out;
    }

    private static boolean hovered(WorldSelectScreen s, String part) {
        WorldStateManager st = s.getStateManager();
        if (part.startsWith("row")) {
            return st.getHoveredIndex() == st.getScrollOffset() + Integer.parseInt(part.substring(3));
        }
        return switch (part) {
            case "folder" -> "card-folder".equals(st.getHoveredCardButton());
            case "backup" -> "card-backup".equals(st.getHoveredCardButton());
            case "confirm" -> "confirm-delete".equals(st.getHoveredButton());
            case "cancel" -> "confirm-cancel".equals(st.getHoveredButton());
            default -> part.equals(st.getHoveredButton());
        };
    }

    /** What a left click at (x, y) does, by the legacy mouse handler's precedence. */
    private static String click(WorldSelectScreen s, Map<String, float[]> parts, float x, float y) {
        WorldStateManager st = s.getStateManager();
        if (st.isShowDeleteDialog()) {
            if (!inside(parts.get("dialog"), x, y)) {
                return ACTION + "cancel-delete";
            }
            if (inside(parts.get("confirm"), x, y)) {
                return ACTION + "confirm-delete";
            }
            return inside(parts.get("cancel"), x, y) ? ACTION + "cancel-delete" : "fired []";
        }
        if (parts.containsKey("card") && inside(parts.get("card"), x, y)) {
            if (inside(parts.get("folder"), x, y)) {
                return ACTION + "open-folder";
            }
            if (inside(parts.get("backup"), x, y)) {
                return s.getBackupService().isRunning(st.getCardWorld()) ? "fired []" : ACTION + "backup";
            }
            return "fired []";
        }
        for (int k = 0; k < 8; k++) {
            if (inside(parts.get("row" + k), x, y)) {
                return ACTION + "select " + (st.getScrollOffset() + k);
            }
        }
        boolean selection = s.hasSelection();
        for (String b : List.of("play", "create", "delete", "back")) {
            if (inside(parts.get(b), x, y)) {
                boolean enabled = selection || b.equals("create") || b.equals("back");
                return enabled ? ACTION + b : "fired []";
            }
        }
        return "fired []";
    }

    private static boolean inside(float[] r, float x, float y) {
        return r != null && x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3];
    }
}
