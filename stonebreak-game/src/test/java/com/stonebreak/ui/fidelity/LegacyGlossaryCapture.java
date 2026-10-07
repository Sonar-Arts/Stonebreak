package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.player.EntityDiscoveries;
import com.stonebreak.player.PlayerStats;
import com.stonebreak.ui.glossaryScreen.GlossaryLayout;
import com.stonebreak.ui.glossaryScreen.GlossaryScreen;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The legacy side of the glossary's fidelity gate (#299): {@link GlossaryScreen}'s real renderer on a
 * {@link LegacyUiRaster} with pinned discoveries and kills ({@link #discoveries()}, {@link #stats()}),
 * the parts it drew (its layout sink), where its own hit tests answer, and what a click on each does.
 *
 * <p>Variants {@code <entity>[-variant2][-hover-<part>]}: {@code cow} (two of three variants seen,
 * weakness known, 1,234 defeated), {@code sheep} (seen, never defeated), {@code chicken} (complete),
 * {@code goose} (unseen); {@code -variant2} steps the cow's cycler once; parts are {@code back},
 * {@code row0..row3}, {@code left}, {@code right}. The 3D model is GL and drawn by neither side here.
 */
public final class LegacyGlossaryCapture implements MigrationGate.Renderer {

    public static final String[] ENTITIES = {"cow", "sheep", "chicken", "goose"};

    public static EntityDiscoveries discoveries() {
        EntityDiscoveries d = new EntityDiscoveries();
        d.recordVariantSeen(EntityType.COW, "Default");
        d.recordVariantSeen(EntityType.COW, "Angus");
        d.recordVariantSeen(EntityType.SHEEP, "Default");
        d.recordVariantSeen(EntityType.CHICKEN, "Default");
        d.recordWeaknessDiscovered(EntityType.COW);
        return d;
    }

    public static PlayerStats stats() {
        PlayerStats s = new PlayerStats();
        s.restoreKillsByType(Map.of(EntityType.COW, 1234L, EntityType.CHICKEN, 3L));
        return s;
    }

    /** A glossary that also logs what was asked of it (the document gate reads the effect of clicks). */
    static final class LoggingGlossary extends GlossaryScreen {
        final java.util.List<String> log = new java.util.ArrayList<>();

        LoggingGlossary(com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend backend) {
            super(backend);
        }

        @Override
        public boolean select(int index) {
            log.add("select " + index);
            return super.select(index);
        }

        @Override
        public boolean cycleVariant(int delta) {
            log.add(delta > 0 ? "cycle +1" : "cycle -1");
            return super.cycleVariant(delta);
        }
    }

    /** A glossary on the variant's selection, reading the pinned data. */
    static LoggingGlossary screen(com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend backend, String variant) {
        LoggingGlossary g = new LoggingGlossary(backend);
        EntityDiscoveries d = discoveries();
        PlayerStats s = stats();
        g.setDataSource(() -> d, () -> s);
        g.setVisible(true);
        String entity = variant.split("-")[0];
        int index = java.util.Arrays.asList(ENTITIES).indexOf(entity);
        if (index < 0) {
            throw new IllegalArgumentException("glossary variant starts with an entity: " + variant);
        }
        g.select(index);
        if (variant.contains("-variant2")) {
            g.cycleVariant(1);
        }
        g.log.clear();
        return g;
    }

    static String hoverPart(String variant) {
        int i = variant.indexOf("-hover-");
        return i < 0 ? "" : variant.substring(i + "-hover-".length());
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        int w = c.viewport().width();
        int h = c.viewport().height();
        float s = c.viewport().uiScale();
        try (LegacyUiRaster raster = new LegacyUiRaster(w, h, s)) {
            GlossaryScreen g = screen(raster.backend(), c.variant());
            Map<String, float[]> drawn = new LinkedHashMap<>();
            g.renderer().setLayoutSink(drawn::put);
            String hover = hoverPart(c.variant());
            if (!hover.isEmpty()) {
                float[] r = drawn.isEmpty() ? null : drawn.get(hover);
                if (r == null) { // learn the rects from a first, discarded render
                    try (LegacyUiRaster probe = new LegacyUiRaster(w, h, s)) {
                        GlossaryScreen p = screen(probe.backend(), c.variant());
                        p.renderer().setLayoutSink(drawn::put);
                        p.render(w, h);
                        p.cleanup();
                    }
                    r = drawn.get(hover);
                    drawn.clear();
                }
                g.updateHover(r[0] + r[2] / 2f, r[1] + r[3] / 2f, w, h);
            }
            try {
                g.render(w, h);
            } finally {
                g.cleanup();
            }
            Map<String, float[]> parts = parts(drawn);
            return new MigrationGate.Capture(raster.capture(), drawn, hits(c.variant(), parts, w, h, s), actions(parts));
        }
    }

    /** The clickable parts that were drawn. */
    static Map<String, float[]> parts(Map<String, float[]> drawn) {
        Map<String, float[]> out = new LinkedHashMap<>();
        for (String p : new String[]{"row0", "row1", "row2", "row3", "left", "right", "back"}) {
            if (drawn.containsKey(p)) {
                out.put(p, drawn.get(p));
            }
        }
        return out;
    }

    /** Where {@code GlossaryScreen.handleClick} / {@code isBackButtonClicked} answer for each part. */
    private static Map<String, float[]> hits(String variant, Map<String, float[]> parts, int w, int h, float s) {
        boolean cycler = parts.containsKey("left");
        Map<String, float[]> out = new LinkedHashMap<>();
        parts.forEach((name, r) -> {
            LegacyPauseCapture.HitProbe probe = switch (name) {
                case "back" -> (x, y) -> GlossaryLayout.contains(x, y, GlossaryLayout.backButtonRect(w, h, s));
                case "left" -> (x, y) -> cycler && GlossaryLayout.contains(x, y, GlossaryLayout.leftArrowRect(w, h, s));
                case "right" -> (x, y) -> cycler && GlossaryLayout.contains(x, y, GlossaryLayout.rightArrowRect(w, h, s));
                default -> {
                    int i = name.charAt(3) - '0';
                    yield (x, y) -> GlossaryLayout.contains(x, y, GlossaryLayout.listRowRect(i, w, h, s));
                }
            };
            out.put(name, LegacyDeathCapture.probeRegion(probe, r, w, h));
        });
        return out;
    }

    /** What a click on each part does, as {@code GlossaryScreen.handleClick} and the router run it. */
    static Map<String, String> actions(Map<String, float[]> parts) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : parts.keySet()) {
            out.put(name, switch (name) {
                case "back" -> "stonebreak:screen.glossary.back";
                case "left" -> "cycle -1";
                case "right" -> "cycle +1";
                default -> "select " + name.substring(3);
            });
        }
        return out;
    }
}
