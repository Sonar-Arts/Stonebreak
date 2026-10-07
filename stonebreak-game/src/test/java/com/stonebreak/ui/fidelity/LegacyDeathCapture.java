package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.ui.DeathMenu;
import com.stonebreak.ui.deathMenu.SkijaDeathMenuRenderer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The legacy side of the death menu's fidelity gate (#299): renders {@link SkijaDeathMenuRenderer}
 * on a {@link LegacyUiRaster} and reports the Respawn button as drawn (the renderer's layout sink),
 * where {@link DeathMenu}'s own hit test answers for it, and the action a click fires.
 *
 * <p>Variants: {@code dead} or {@code dead-hover-respawn} (the button highlighted). Not a test class.
 */
public final class LegacyDeathCapture implements MigrationGate.Renderer {

    public static final String RESPAWN_ACTION = "stonebreak:screen.death.respawn";

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        boolean hover = switch (c.variant()) {
            case "dead" -> false;
            case "dead-hover-respawn" -> true;
            default -> throw new IllegalArgumentException("death variant: dead or dead-hover-respawn, not " + c.variant());
        };
        int w = c.viewport().width();
        int h = c.viewport().height();
        try (LegacyUiRaster raster = new LegacyUiRaster(w, h, c.viewport().uiScale())) {
            SkijaDeathMenuRenderer renderer = new SkijaDeathMenuRenderer(raster.backend());
            Map<String, float[]> drawn = new LinkedHashMap<>();
            renderer.setLayoutSink(drawn::put);
            try {
                renderer.render(w, h, hover);
            } finally {
                renderer.dispose();
            }
            return new MigrationGate.Capture(raster.capture(), drawn, hitRegions(raster, drawn, w, h),
                Map.of("respawn", RESPAWN_ACTION));
        }
    }

    /** Where {@link DeathMenu#isRespawnButtonClicked} answers, probed along the drawn button's centre lines. */
    private static Map<String, float[]> hitRegions(LegacyUiRaster raster, Map<String, float[]> drawn, int w, int h) {
        DeathMenu menu = new DeathMenu(raster.backend());
        menu.setVisible(true);
        try {
            float[] r = drawn.get("respawn");
            LegacyPauseCapture.HitProbe probe = (x, y) -> menu.isRespawnButtonClicked(x, y, w, h);
            return Map.of("respawn", probeRegion(probe, r, w, h));
        } finally {
            menu.cleanup();
        }
    }

    /**
     * The region a probe answers for around a drawn rect: bisected out from its centre along both
     * axes ({@link LegacyPauseCapture#edge}); a centre that misses reports a zero-sized region.
     */
    static float[] probeRegion(LegacyPauseCapture.HitProbe probe, float[] r, int w, int h) {
        float cx = r[0] + r[2] / 2f;
        float cy = r[1] + r[3] / 2f;
        if (!probe.at(cx, cy)) {
            return new float[]{cx, cy, 0, 0};
        }
        float x0 = LegacyPauseCapture.edge(probe, cx, cy, -1, 0, w, h);
        float x1 = LegacyPauseCapture.edge(probe, cx, cy, 1, 0, w, h);
        float y0 = LegacyPauseCapture.edge(probe, cx, cy, 0, -1, w, h);
        float y1 = LegacyPauseCapture.edge(probe, cx, cy, 0, 1, w, h);
        return new float[]{x0, y0, x1 - x0, y1 - y0};
    }
}
