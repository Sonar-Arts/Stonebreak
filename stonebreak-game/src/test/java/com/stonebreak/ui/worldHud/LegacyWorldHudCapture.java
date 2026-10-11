package com.stonebreak.ui.worldHud;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.rendering.UI.components.DamageNumberRenderer;
import com.stonebreak.rendering.UI.components.DoubtMarkerRenderer;
import com.stonebreak.rendering.UI.components.EnemyAwarenessRenderer;
import com.stonebreak.rendering.UI.components.PlayerNameTagRenderer;
import com.stonebreak.rendering.UI.components.QuarryMarkerRenderer;
import com.stonebreak.rendering.UI.components.StealthHudRenderer;
import com.stonebreak.ui.fidelity.LegacyUiRaster;

/**
 * The legacy side of the world HUD's fidelity gate (#301): each legacy world-space renderer paints its
 * kinds of a {@link WorldHudFixtures} frame, in the frame renderer's order, then the stealth HUD, on a
 * {@link LegacyUiRaster}. The renderers are singletons: their backend is the raster's for the capture
 * and none afterwards. Not a test class.
 */
public final class LegacyWorldHudCapture implements MigrationGate.Renderer {

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        int w = c.viewport().width();
        int h = c.viewport().height();
        WorldHudFixtures.Frame f = WorldHudFixtures.frame(c.variant(), w, h);
        try (LegacyUiRaster raster = new LegacyUiRaster(w, h, c.viewport().uiScale())) {
            var backend = raster.backend();
            try {
                DamageNumberRenderer.getInstance().setBackend(backend);
                QuarryMarkerRenderer.getInstance().setBackend(backend);
                DoubtMarkerRenderer.getInstance().setBackend(backend);
                EnemyAwarenessRenderer.getInstance().setBackend(backend);
                PlayerNameTagRenderer.getInstance().setBackend(backend);
                StealthHudRenderer.getInstance().setBackend(backend);
                DamageNumberRenderer.getInstance().paint(f.markers(), w, h);
                QuarryMarkerRenderer.getInstance().paint(f.markers(), w, h);
                DoubtMarkerRenderer.getInstance().paint(f.markers(), w, h);
                EnemyAwarenessRenderer.getInstance().paint(f.markers(), w, h);
                PlayerNameTagRenderer.getInstance().paint(f.markers(), w, h);
                if (f.stealth().shown()) {
                    StealthHudRenderer.getInstance().paint(f.stealth(), w, h);
                }
            } finally {
                DamageNumberRenderer.getInstance().setBackend(null);
                QuarryMarkerRenderer.getInstance().setBackend(null);
                DoubtMarkerRenderer.getInstance().setBackend(null);
                EnemyAwarenessRenderer.getInstance().setBackend(null);
                PlayerNameTagRenderer.getInstance().setBackend(null);
                StealthHudRenderer.getInstance().setBackend(null);
            }
            return new MigrationGate.Capture(raster.capture(), WorldHudFixtures.anchors(f, w));
        }
    }
}
