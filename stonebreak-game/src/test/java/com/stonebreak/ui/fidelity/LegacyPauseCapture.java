package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.ui.PauseMenu;
import com.stonebreak.ui.UiOnlineState;
import com.stonebreak.ui.pauseMenu.SkijaPauseMenuRenderer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The legacy side of the pause menu's fidelity gate (#296, consumed by #297): renders
 * {@link SkijaPauseMenuRenderer} on a {@link LegacyUiRaster} for one {@link FidelityCase} and
 * reports the rects of its parts under the names of {@code ui/fixtures/legacy-geometry.json}, the
 * hit regions {@link PauseMenu}'s own bounds checks answer for, and the action each button fires.
 *
 * <p>Variants are {@code <where>-<session>[-hover-<button>]}:
 * <ul>
 *   <li>{@code field}: the menu over the world, which the frame renderer draws <b>twice</b>
 *       (once in the in-game UI pass, once in the modal pass), so two 0x78 scrims stack to about
 *       72 % darkness; {@code battle}: over a Focus battle, drawn once (about 47 %). The ledger's
 *       hard visual 13: a migration must reproduce the darker field pause on purpose.</li>
 *   <li>{@code offline} (five buttons) or {@code online} (six, with Resync World).</li>
 *   <li>{@code hover-<button>}: that button highlighted (Resume too since #297; it never did before).</li>
 * </ul>
 */
public final class LegacyPauseCapture implements MigrationGate.Renderer {

    public static final List<String> BUTTONS = List.of("resume", "statistics", "glossary", "settings", "resync", "quit");

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        String[] v = c.variant().split("-");
        int passes = switch (v[0]) {
            case "field" -> 2;
            case "battle" -> 1;
            default -> throw new IllegalArgumentException("pause variant must start with field or battle: " + c.variant());
        };
        boolean online = switch (v[1]) {
            case "online" -> true;
            case "offline" -> false;
            default -> throw new IllegalArgumentException("pause variant needs online or offline: " + c.variant());
        };
        String hover = v.length >= 4 && v[2].equals("hover") ? v[3] : "";
        if (!hover.isEmpty() && !BUTTONS.contains(hover)) {
            throw new IllegalArgumentException("unknown pause button " + hover);
        }
        int w = c.viewport().width();
        int h = c.viewport().height();
        float s = c.viewport().uiScale();
        try (LegacyUiRaster raster = new LegacyUiRaster(w, h, s)) {
            SkijaPauseMenuRenderer renderer = new SkijaPauseMenuRenderer(raster.backend());
            Map<String, float[]> drawn = new LinkedHashMap<>();
            renderer.setLayoutSink(drawn::put); // what the renderer drew, not a transcription of it
            try {
                for (int i = 0; i < passes; i++) {
                    renderer.render(w, h, hover.equals("resume"), hover.equals("statistics"), hover.equals("glossary"), hover.equals("settings"),
                        online, hover.equals("resync"), hover.equals("quit"));
                }
            } finally {
                renderer.dispose();
            }
            Map<String, float[]> hits = hitRegions(raster, drawn, w, h, online);
            return new MigrationGate.Capture(raster.capture(), drawn, hits, actions(online));
        }
    }

    /**
     * What a click on each button does in the legacy path: {@code UiMouseRouter.handlePauseMenuClick}
     * calls the {@code PauseMenuActions} method that the UI host exposes under these ids, so a
     * document wired to another action (or to none) fails the gate.
     */
    public static Map<String, String> actions(boolean online) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String b : BUTTONS) {
            if (b.equals("resync")) {
                if (online) {
                    out.put(b, "stonebreak:network.resync");
                }
            } else {
                out.put(b, "stonebreak:screen.pause." + b);
            }
        }
        return out;
    }

    /**
     * Where {@link PauseMenu}'s own hit tests answer for each button, measured by probing them
     * along the drawn button's centre lines (inclusive edges, as the legacy bounds check is).
     */
    private static Map<String, float[]> hitRegions(LegacyUiRaster raster, Map<String, float[]> drawn, int w, int h,
                                                   boolean online) {
        PauseMenu menu = new PauseMenu(raster.backend());
        menu.setVisible(true);
        UiOnlineState.override(() -> online);
        try {
            Map<String, float[]> out = new LinkedHashMap<>();
            for (String b : BUTTONS) {
                float[] r = drawn.get(b);
                if (r == null) {
                    continue;
                }
                HitProbe probe = (x, y) -> hit(menu, b, x, y, w, h);
                float cx = r[0] + r[2] / 2f;
                float cy = r[1] + r[3] / 2f;
                if (!probe.at(cx, cy)) {
                    out.put(b, new float[]{cx, cy, 0, 0}); // drawn but not clickable: fails as a size mismatch
                    continue;
                }
                float x0 = edge(probe, cx, cy, -1, 0, w, h);
                float x1 = edge(probe, cx, cy, 1, 0, w, h);
                float y0 = edge(probe, cx, cy, 0, -1, w, h);
                float y1 = edge(probe, cx, cy, 0, 1, w, h);
                out.put(b, new float[]{x0, y0, x1 - x0, y1 - y0});
            }
            return out;
        } finally {
            UiOnlineState.override(null);
            menu.cleanup();
        }
    }

    @FunctionalInterface
    interface HitProbe {
        boolean at(float x, float y);
    }

    /**
     * The farthest coordinate from the centre along one axis that still hits, bisected down to
     * float resolution, so an inclusive {@code x + width} edge comes back exact (the gate compares
     * hit regions as strictly as painted rects).
     */
    static float edge(HitProbe probe, float cx, float cy, int dx, int dy, int w, int h) {
        float from = dx != 0 ? cx : cy;
        float in = from;
        float out = from + (dx + dy) * (Math.max(w, h) + 1);
        for (int i = 0; i < 64; i++) {
            float mid = (in + out) / 2f;
            if (mid == in || mid == out) {
                break;
            }
            if (dx != 0 ? probe.at(mid, cy) : probe.at(cx, mid)) {
                in = mid;
            } else {
                out = mid;
            }
        }
        return in;
    }

    private static boolean hit(PauseMenu m, String button, float x, float y, int w, int h) {
        return switch (button) {
            case "resume" -> m.isResumeButtonClicked(x, y, w, h);
            case "statistics" -> m.isStatisticsButtonClicked(x, y, w, h);
            case "glossary" -> m.isGlossaryButtonClicked(x, y, w, h);
            case "settings" -> m.isSettingsButtonClicked(x, y, w, h);
            case "resync" -> m.isResyncButtonClicked(x, y, w, h);
            case "quit" -> m.isQuitButtonClicked(x, y, w, h);
            default -> false;
        };
    }

    /**
     * The pause layout as the renderer computes it, named as in the oracle: read from
     * {@link SkijaPauseMenuRenderer#setLayoutSink} during a real render (no panel sizes copied
     * here, #296 review).
     */
    public static Map<String, float[]> rects(int w, int h, float s, boolean online) {
        try (LegacyUiRaster raster = new LegacyUiRaster(w, h, s)) {
            SkijaPauseMenuRenderer renderer = new SkijaPauseMenuRenderer(raster.backend());
            Map<String, float[]> drawn = new LinkedHashMap<>();
            renderer.setLayoutSink(drawn::put);
            try {
                renderer.render(w, h, false, false, false, false, online, false, false);
            } finally {
                renderer.dispose();
            }
            return drawn;
        }
    }
}
