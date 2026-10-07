package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.player.PlayerStats;
import com.stonebreak.ui.statisticsScreen.SkijaStatisticsRenderer;
import com.stonebreak.ui.statisticsScreen.StatisticsScreen;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The legacy side of the statistics screen's fidelity gate (#299): {@link SkijaStatisticsRenderer}
 * on a {@link LegacyUiRaster} with pinned statistics, the panel and Back rects from its layout
 * sink, Back's hit region from {@link StatisticsScreen}'s own test, and its action.
 *
 * <p>Variants: {@code played} ({@link #sample()}: grouping, kilometres, hours), {@code fresh} (no
 * player: every value zero), each optionally {@code -hover-back}. Not a test class.
 */
public final class LegacyStatisticsCapture implements MigrationGate.Renderer {

    public static final String BACK_ACTION = "stonebreak:screen.statistics.back";

    /** A played world: four-digit counts (grouping), kilometres and metres, hours of air time. */
    public static PlayerStats sample() {
        PlayerStats s = new PlayerStats();
        s.restore(1234, 4567.25, 15432.5, 9876.4, 4321.0, 512.75, 3725.0);
        s.restoreKillsByType(Map.of(EntityType.COW, 12L, EntityType.SHEEP, 7L, EntityType.CHICKEN, 1203L));
        return s;
    }

    /** The stats a variant shows, or null for no player. */
    static PlayerStats stats(String variant) {
        return variant.startsWith("played") ? sample() : null;
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        String v = c.variant();
        if (!v.startsWith("played") && !v.startsWith("fresh")) {
            throw new IllegalArgumentException("statistics variant: played|fresh[-hover-back], not " + v);
        }
        boolean hover = v.endsWith("-hover-back");
        PlayerStats stats = stats(v);
        int w = c.viewport().width();
        int h = c.viewport().height();
        try (LegacyUiRaster raster = new LegacyUiRaster(w, h, c.viewport().uiScale())) {
            SkijaStatisticsRenderer renderer = new SkijaStatisticsRenderer(raster.backend());
            renderer.setStatsSource(() -> stats);
            Map<String, float[]> drawn = new LinkedHashMap<>();
            renderer.setLayoutSink(drawn::put);
            try {
                renderer.render(w, h, hover);
            } finally {
                renderer.dispose();
            }
            StatisticsScreen screen = new StatisticsScreen(raster.backend());
            screen.setVisible(true);
            Map<String, float[]> hits;
            try {
                hits = Map.of("back", LegacyDeathCapture.probeRegion(
                    (x, y) -> screen.isBackButtonClicked(x, y, w, h), drawn.get("back"), w, h));
            } finally {
                screen.cleanup();
            }
            return new MigrationGate.Capture(raster.capture(), drawn, hits, Map.of("back", BACK_ACTION));
        }
    }
}
