package com.stonebreak.ui.terrainMapper.renderers;

import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.terrainMapper.TerrainMapperLayout;
import com.stonebreak.ui.terrainMapper.managers.TerrainMapperStateManager;
import io.github.humbleui.skija.Canvas;

/**
 * Per-frame orchestrator for the terrain mapper screen. Draws the dark
 * backdrop, then delegates to the three section renderers. Mirrors the
 * pattern used by {@code SkijaSettingsRenderer}.
 */
public final class SkijaTerrainMapperRenderer {

    private final MasonryUI ui;
    private final TerrainMapperStateManager state;
    private final TerrainSidebarRenderer sidebarRenderer;
    private final TerrainMapRenderer mapRenderer;
    private final TerrainFooterRenderer footerRenderer;

    public SkijaTerrainMapperRenderer(MasonryUI ui, TerrainMapperStateManager state) {
        this.ui = ui;
        this.state = state;
        this.sidebarRenderer = new TerrainSidebarRenderer(state);
        this.mapRenderer = new TerrainMapRenderer(state);
        this.footerRenderer = new TerrainFooterRenderer(state);
    }

    public void render(int windowWidth, int windowHeight) {
        if (!ui.isAvailable()) return;
        if (!ui.beginFrame(windowWidth, windowHeight, 1.0f)) return;
        try {
            TerrainMapperLayout layout = new TerrainMapperLayout(windowWidth, windowHeight);
            Canvas canvas = ui.canvas();
            if (canvas != null) {
                MPainter.fillRect(canvas, 0, 0, windowWidth, windowHeight, 0xFF151515);
            }
            mapRenderer.render(ui, layout);
            sidebarRenderer.render(ui, layout);
            footerRenderer.render(ui, layout);
            ui.renderOverlays();
        } finally {
            ui.endFrame();
        }
    }

    public void dispose() {
        state.dispose();
    }
}
