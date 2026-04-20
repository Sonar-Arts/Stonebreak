package com.stonebreak.ui.terrainMapper.renderers;

import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.terrainMapper.TerrainMapperLayout;
import com.stonebreak.ui.terrainMapper.managers.TerrainMapperStateManager;
import com.stonebreak.ui.terrainMapper.managers.TerrainPreviewCache;
import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ClipMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.types.Rect;

/**
 * Draws the preview viewport: a cached noise image blitted into the map
 * rect, clipped to the rect, with a 1px border. The cache owns sampling;
 * the renderer is a pure blit + frame.
 */
public final class TerrainMapRenderer {

    private final TerrainMapperStateManager state;

    public TerrainMapRenderer(TerrainMapperStateManager state) {
        this.state = state;
    }

    public void render(MasonryUI ui, TerrainMapperLayout layout) {
        Canvas canvas = ui.canvas();
        if (canvas == null) return;

        TerrainMapperLayout.Rect mapRect = layout.map();
        if (mapRect.width() <= 0 || mapRect.height() <= 0) return;

        MPainter.fillRect(canvas, mapRect.x(), mapRect.y(), mapRect.width(), mapRect.height(), 0xFF0E0E0E);

        NoiseVisualizer visualizer = state.getVisualizers().get(state.getActiveVisualizer());
        TerrainPreviewCache cache = state.getPreviewCache();
        cache.ensure((int) mapRect.width(), (int) mapRect.height(),
                state.effectiveSampleStep(),
                state.getActiveVisualizer(),
                state.getResolvedSeed(),
                visualizer,
                state.getViewport());

        Image image = cache.image();
        if (image != null) {
            int save = canvas.save();
            Rect dst = Rect.makeXYWH(mapRect.x(), mapRect.y(), mapRect.width(), mapRect.height());
            canvas.clipRect(dst, ClipMode.INTERSECT, true);
            try (Paint paint = new Paint()) {
                Rect src = Rect.makeWH(image.getWidth(), image.getHeight());
                canvas.drawImageRect(image, src, dst, SamplingMode.DEFAULT, paint, true);
            }
            canvas.restoreToCount(save);
        }

        if (state.hasHoverValue()) {
            drawHoverCrosshair(canvas, mapRect);
        }

        MPainter.strokeRect(canvas, mapRect.x(), mapRect.y(), mapRect.width(), mapRect.height(),
                MStyle.PANEL_BORDER, 2f);
    }

    private void drawHoverCrosshair(Canvas canvas, TerrainMapperLayout.Rect map) {
        int color = 0xAAFFFFFF;
        int edge = 0x55000000;
        float size = 10f;
        float cx = map.centerX();
        float cz = map.y() + map.height() / 2f;
        // Anchor crosshair at the hovered world point projected back to screen.
        float screenX = cx + (state.hoverWorldX() - state.getViewport().panX()) * state.getViewport().zoom();
        float screenY = cz + (state.hoverWorldZ() - state.getViewport().panZ()) * state.getViewport().zoom();
        if (screenX < map.x() || screenX > map.right() || screenY < map.y() || screenY > map.bottom()) return;

        MPainter.fillRect(canvas, screenX - size, screenY - 1f, size * 2f, 2f, edge);
        MPainter.fillRect(canvas, screenX - 1f, screenY - size, 2f, size * 2f, edge);
        MPainter.fillRect(canvas, screenX - size, screenY, size * 2f, 1f, color);
        MPainter.fillRect(canvas, screenX, screenY - size, 1f, size * 2f, color);
    }
}
