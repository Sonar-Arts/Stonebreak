package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.ui.pauseMenu.SkijaPauseMenuRenderer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The legacy side of the pause menu's fidelity gate (#296, consumed by #297): renders
 * {@link SkijaPauseMenuRenderer} on a {@link LegacyUiRaster} for one {@link FidelityCase} and
 * reports the rects of its parts under the names of {@code ui/fixtures/legacy-geometry.json}.
 *
 * <p>Variants are {@code <where>-<session>[-hover-<button>]}:
 * <ul>
 *   <li>{@code field}: the menu over the world, which the frame renderer draws <b>twice</b>
 *       (once in the in-game UI pass, once in the modal pass), so two 0x78 scrims stack to about
 *       72 % darkness; {@code battle}: over a Focus battle, drawn once (about 47 %). The ledger's
 *       hard visual 13: a migration must reproduce the darker field pause on purpose.</li>
 *   <li>{@code offline} (five buttons) or {@code online} (six, with Resync World).</li>
 *   <li>{@code hover-<button>}: that button highlighted. Resume never highlights (ledger).</li>
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
            try {
                for (int i = 0; i < passes; i++) {
                    renderer.render(w, h, hover.equals("statistics"), hover.equals("glossary"), hover.equals("settings"),
                        online, hover.equals("resync"), hover.equals("quit"));
                }
            } finally {
                renderer.dispose();
            }
            return new MigrationGate.Capture(raster.capture(), rects(w, h, s, online));
        }
    }

    /** The renderer's own layout maths ({@code SkijaPauseMenuRenderer.render}), named as in the oracle. */
    public static Map<String, float[]> rects(int w, int h, float s, boolean online) {
        Map<String, float[]> out = new LinkedHashMap<>();
        float cx = w / 2f;
        float cy = h / 2f;
        float pw = 520f * s;
        float ph = 560f * s;
        out.put("panel", new float[]{cx - pw / 2f, cy - ph / 2f, pw, ph});
        float bw = SkijaPauseMenuRenderer.BUTTON_WIDTH * s;
        float bh = SkijaPauseMenuRenderer.BUTTON_HEIGHT * s;
        int count = online ? 6 : 5;
        int slot = 0;
        for (String b : BUTTONS) {
            if (b.equals("resync") && !online) {
                continue;
            }
            out.put(b, new float[]{cx - bw / 2f, cy + SkijaPauseMenuRenderer.buttonOffset(slot++, count) * s, bw, bh});
        }
        return out;
    }
}
