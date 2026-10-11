package com.stonebreak.ui.terrainMapper;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.stonebreak.ui.terrainMapper.config.TerrainMapperConfig;
import com.stonebreak.ui.terrainMapper.managers.TerrainMapperStateManager;
import com.stonebreak.ui.terrainMapper.managers.TerrainMapperStateManager.ActiveField;
import com.stonebreak.ui.terrainMapper.visualization.VisualizerKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The terrain mapper's #301 fidelity cases: the screen's state per variant (a fixed seed, so the live map
 * is the same raster on both sides), the pinned UI clock (the caret), and the legacy layout as the geometry
 * oracle. Not a test class.
 *
 * <p>Variants: {@code default} (name focused, caret on), {@code blink-off}, {@code typed} (name and seed
 * typed, seed focused, Biome), {@code spawn} (spawn set, nothing focused, Temperature), {@code zoomed} (2x,
 * panned), {@code error}; {@code hover-<target>}: the pointer over {@code map}, {@code create},
 * {@code character}, {@code mode3}, {@code spawn} (disabled) or {@code name}.
 */
public final class TerrainMapperFixtures {

    private TerrainMapperFixtures() {
    }

    public static final String SEED = "1234567";
    public static final List<String> STATES = List.of("blink-off", "typed", "spawn", "zoomed", "error");
    public static final List<String> HOVERS = List.of("hover-map", "hover-create", "hover-character", "hover-mode3",
        "hover-spawn", "hover-name");

    public static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("terrain-mapper", List.of("default"),
            FidelityCase.STANDARD));
        for (String v : STATES) {
            out.add(new FidelityCase("terrain-mapper", v, FidelityCase.STANDARD.get(0)));
        }
        for (String h : HOVERS) {
            out.add(new FidelityCase("terrain-mapper", h, FidelityCase.STANDARD.get(0)));
            out.add(new FidelityCase("terrain-mapper", h, FidelityCase.STANDARD.get(3)));
        }
        return out;
    }

    /** The pinned UI-clock reading of a variant: the caret on, except {@code blink-off}. */
    public static double clock(String variant) {
        return "blink-off".equals(variant) ? 0.6 : 0.1;
    }

    /** Puts a fresh screen's state into {@code variant}'s. */
    public static void prepare(TerrainMapperStateManager s, String variant) {
        s.setSeedText(SEED);
        switch (variant) {
            case "typed" -> {
                s.setWorldName("Stone Vale");
                s.setSeedText("-42");
                s.setActiveField(ActiveField.SEED);
                s.setActiveVisualizer(VisualizerKind.BIOME);
            }
            case "spawn" -> {
                s.setActiveField(ActiveField.NONE);
                s.setActiveVisualizer(VisualizerKind.TEMPERATURE);
                s.setSpawnPoint(30, -20);
            }
            case "zoomed" -> {
                s.setActiveField(ActiveField.NONE);
                s.getViewport().centerOn(220, -140);
                s.getViewport().zoomAt(0, 0, 0, 0, 2f);
            }
            case "error" -> {
                s.setActiveField(ActiveField.NONE);
                s.setErrorMessage("A world with that name already exists");
            }
            default -> {
            }
        }
    }

    public static String hover(String variant) {
        return variant.startsWith("hover-") ? variant.substring("hover-".length()) : "";
    }

    /** Named rects of the legacy layout ({@link TerrainMapperLayout}, the footer's button placement). */
    public static Map<String, float[]> rects(int w, int h, TerrainMapperStateManager s) {
        TerrainMapperLayout l = new TerrainMapperLayout(w, h);
        Map<String, float[]> out = new LinkedHashMap<>();
        put(out, "sidebar", l.sidebar());
        put(out, "map", l.map());
        put(out, "footer", l.footer());
        put(out, "name", l.worldNameField());
        put(out, "seed", l.seedField());
        for (int i = 0; i < VisualizerKind.values().length; i++) {
            TerrainMapperLayout.Rect f = l.firstModeButton();
            out.put("mode" + i, new float[]{f.x(), l.modeButtonY(i), f.width(), f.height()});
        }
        put(out, "spawn", l.spawnButton());
        put(out, "center", l.centerOnSpawnButton());
        TerrainMapperLayout.Rect footer = l.footer();
        float bw = TerrainMapperConfig.FOOTER_BUTTON_WIDTH;
        float bh = TerrainMapperConfig.FOOTER_BUTTON_HEIGHT;
        float gap = TerrainMapperConfig.FOOTER_BUTTON_GAP;
        float y = footer.y() + (footer.height() - bh) / 2f;
        out.put("back", new float[]{footer.x() + gap, y, bw, bh});
        out.put("character", new float[]{footer.x() + gap + bw + gap, y, bw, bh});
        out.put("create", new float[]{footer.right() - gap - bw, y, bw, bh});
        out.put("simulate", new float[]{footer.right() - gap - bw - gap - bw, y, bw, bh});
        return out;
    }

    /** Where a hover case puts the pointer: a control's centre, or a point on the map off its centre. */
    public static float[] pointer(Map<String, float[]> rects, String target) {
        float[] r = rects.get(target);
        if (r == null) {
            throw new IllegalArgumentException("unknown terrain mapper target " + target);
        }
        if ("map".equals(target)) {
            return new float[]{r[0] + r[2] / 2f + 137f, r[1] + r[3] / 2f - 61f};
        }
        return new float[]{r[0] + r[2] / 2f, r[1] + r[3] / 2f};
    }

    private static void put(Map<String, float[]> out, String name, TerrainMapperLayout.Rect r) {
        out.put(name, new float[]{r.x(), r.y(), r.width(), r.height()});
    }
}
