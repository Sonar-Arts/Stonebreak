package com.stonebreak.ui.terrainMapper.renderers;

import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.terrainMapper.TerrainMapperLayout;
import com.stonebreak.ui.terrainMapper.components.TerrainMapViewport;
import com.stonebreak.ui.terrainMapper.managers.PreviewSnapshot;
import com.stonebreak.ui.terrainMapper.managers.SampleRequest;
import com.stonebreak.ui.terrainMapper.managers.TerrainMapperStateManager;
import com.stonebreak.ui.terrainMapper.managers.TerrainPreviewLoader;
import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ClipMode;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.types.Rect;

/**
 * Draws the preview viewport. Nothing here ever blocks on terrain: it asks
 * {@link TerrainPreviewLoader} for a sample (fire-and-forget) and draws whatever snapshot has
 * been published so far, or a blank rect with a status line when there is none yet.
 *
 * <p>The snapshot is drawn <em>reprojected</em> rather than blitted 1:1. A sample takes a
 * while to come back, so by the time it does the user has usually panned or zoomed on; the
 * snapshot knows the viewport it was sampled at, so its world extent can be projected through
 * the current viewport and the pixels land exactly where they belong. Panning therefore moves
 * the map instantly, and only the newly exposed edge is blank until the next sample lands.
 */
public final class TerrainMapRenderer {

    /** Alpha for an image left over from a visualizer the user has since switched away from. */
    private static final int STALE_MODE_ALPHA = 90;

    private final TerrainMapperStateManager state;

    public TerrainMapRenderer(TerrainMapperStateManager state) {
        this.state = state;
    }

    public void render(MasonryUI ui, TerrainMapperLayout layout) {
        Canvas canvas = ui.canvas();
        if (canvas == null) return;

        TerrainMapperLayout.Rect mapRect = layout.map();
        if (mapRect.width() <= 0 || mapRect.height() <= 0) return;

        TerrainPreviewLoader loader = state.getPreviewLoader();
        // Free images displaced since the last frame. Safe here and only here: any draw that
        // used them was issued by this thread on an earlier frame and has already completed.
        loader.retireCompleted();

        // The blank map.
        MPainter.fillRect(canvas, mapRect.x(), mapRect.y(), mapRect.width(), mapRect.height(), 0xFF0E0E0E);

        state.applyPendingSeed();
        loader.request(buildRequest(mapRect));

        // Oldest first: the last full picture, then whatever the running pass has filled in over
        // it. On a zoom that means the map the user already had stays put, at its true world
        // scale, instead of vanishing until the new pass catches up.
        PreviewSnapshot backdrop = loader.backdrop();
        if (backdrop != null) {
            drawSnapshot(canvas, mapRect, backdrop);
        }
        PreviewSnapshot snapshot = loader.snapshot();
        if (snapshot != null) {
            drawSnapshot(canvas, mapRect, snapshot);
        }
        drawStatus(canvas, ui, mapRect, loader, snapshot);

        if (state.hasSpawnPoint()) {
            drawSpawnWaypoint(canvas, mapRect);
        }

        if (state.hasHoverValue()) {
            drawHoverCrosshair(canvas, mapRect);
        }

        MPainter.strokeRect(canvas, mapRect.x(), mapRect.y(), mapRect.width(), mapRect.height(),
                MStyle.PANEL_BORDER, 2f);
    }

    private SampleRequest buildRequest(TerrainMapperLayout.Rect mapRect) {
        NoiseVisualizer visualizer = state.getVisualizers().get(state.getActiveVisualizer());
        if (visualizer == null) return null;
        TerrainMapViewport viewport = state.getViewport();
        return new SampleRequest(
                visualizer,
                state.getVisualizers(),
                (int) mapRect.width(),
                (int) mapRect.height(),
                state.effectiveSampleStep(),
                viewport.panX(),
                viewport.panZ(),
                viewport.zoom());
    }

    /**
     * Projects the snapshot's world extent through the <em>current</em> viewport. When the
     * viewport hasn't moved since the sample, this collapses to a 1:1 blit into the map rect.
     *
     * <p>An image from a different visualizer is drawn dimmed rather than at full strength. A
     * mode switch has to wait for at least the first band of the new sampling, and at full
     * opacity the previous mode's map reads as the answer — which is indistinguishable from the
     * button having done nothing at all.
     */
    private void drawSnapshot(Canvas canvas, TerrainMapperLayout.Rect mapRect, PreviewSnapshot snapshot) {
        TerrainMapViewport viewport = state.getViewport();
        float zoom = viewport.zoom();
        float dstX = mapRect.x() + mapRect.width() * 0.5f + (snapshot.worldLeft() - viewport.panX()) * zoom;
        float dstY = mapRect.y() + mapRect.height() * 0.5f + (snapshot.worldTop() - viewport.panZ()) * zoom;
        float dstW = snapshot.worldWidth() * zoom;
        float dstH = snapshot.worldHeight() * zoom;
        if (dstW <= 0f || dstH <= 0f) return;

        int save = canvas.save();
        canvas.clipRect(Rect.makeXYWH(mapRect.x(), mapRect.y(), mapRect.width(), mapRect.height()),
                ClipMode.INTERSECT, true);
        try (Paint paint = new Paint()) {
            if (snapshot.request().visualizer() != state.getVisualizers().get(state.getActiveVisualizer())) {
                paint.setAlpha(STALE_MODE_ALPHA);
            }
            Rect src = Rect.makeWH(snapshot.image().getWidth(), snapshot.image().getHeight());
            canvas.drawImageRect(snapshot.image(), src, Rect.makeXYWH(dstX, dstY, dstW, dstH),
                    SamplingMode.DEFAULT, paint, true);
        }
        canvas.restoreToCount(save);
    }

    /**
     * Progress/error line. Centered while the map is blank — there is nothing else to look at —
     * and tucked into the top-left corner once there are pixels, so a background resample
     * doesn't paint over the terrain the user is reading.
     */
    private void drawStatus(Canvas canvas, MasonryUI ui, TerrainMapperLayout.Rect mapRect,
                            TerrainPreviewLoader loader, PreviewSnapshot snapshot) {
        String message = loader.statusMessage();
        if (message == null) return;

        int color = loader.phase() == TerrainPreviewLoader.Phase.FAILED
                ? MStyle.TEXT_ERROR
                : MStyle.TEXT_SECONDARY;
        if (snapshot == null) {
            Font font = ui.fonts().get(MStyle.FONT_ITEM);
            MPainter.drawCenteredString(canvas, message, mapRect.centerX(),
                    mapRect.y() + mapRect.height() / 2f, font, color);
        } else {
            Font font = ui.fonts().get(MStyle.FONT_META);
            MPainter.drawStringWithShadow(canvas, message, mapRect.x() + 12f, mapRect.y() + 22f,
                    font, color, MStyle.TEXT_SHADOW);
        }
    }

    private void drawSpawnWaypoint(Canvas canvas, TerrainMapperLayout.Rect map) {
        float cx = map.centerX();
        float cz = map.y() + map.height() / 2f;
        float sx = cx + (state.spawnWorldX() - state.getViewport().panX()) * state.getViewport().zoom();
        float sz = cz + (state.spawnWorldZ() - state.getViewport().panZ()) * state.getViewport().zoom();
        if (sx < map.x() || sx > map.right() || sz < map.y() || sz > map.bottom()) return;

        float r = 7f;
        MPainter.fillRoundedRect(canvas, sx - r - 1.5f, sz - r - 1.5f, (r + 1.5f) * 2f, (r + 1.5f) * 2f, r + 1.5f, 0xFF004400);
        MPainter.fillRoundedRect(canvas, sx - r, sz - r, r * 2f, r * 2f, r, 0xFF32CD32);
        MPainter.fillRoundedRect(canvas, sx - 2f, sz - 2f, 4f, 4f, 2f, 0xFFFFFFFF);
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
