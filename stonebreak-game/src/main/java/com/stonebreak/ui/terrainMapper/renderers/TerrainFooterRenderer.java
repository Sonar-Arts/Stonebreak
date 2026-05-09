package com.stonebreak.ui.terrainMapper.renderers;

import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.terrainMapper.TerrainMapperLayout;
import com.stonebreak.ui.terrainMapper.TerrainMapperLayout.Rect;
import com.stonebreak.ui.terrainMapper.config.TerrainMapperConfig;
import com.stonebreak.ui.terrainMapper.managers.TerrainMapperStateManager;
import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * Draws the footer panel: Back, Simulate Seed, Create buttons and a hover
 * tooltip showing the world coordinate + sampled value under the mouse.
 */
public final class TerrainFooterRenderer {

    private final TerrainMapperStateManager state;

    public TerrainFooterRenderer(TerrainMapperStateManager state) {
        this.state = state;
    }

    public void render(MasonryUI ui, TerrainMapperLayout layout) {
        Canvas canvas = ui.canvas();
        if (canvas == null) return;

        Rect footer = layout.footer();
        MPainter.panel(canvas, footer.x(), footer.y(), footer.width(), footer.height());

        positionButtons(footer);
        state.getBackButton().render(ui);
        state.getCharacterButton().render(ui);
        state.getSimulateSeedButton().render(ui);
        state.getCreateButton().render(ui);

        drawStatusText(canvas, ui, footer);
    }

    private void positionButtons(Rect footer) {
        float y = footer.y() + (footer.height() - TerrainMapperConfig.FOOTER_BUTTON_HEIGHT) / 2f;
        float right = footer.right() - TerrainMapperConfig.FOOTER_BUTTON_GAP;
        state.getCreateButton()
                .position(right - TerrainMapperConfig.FOOTER_BUTTON_WIDTH, y);
        right -= TerrainMapperConfig.FOOTER_BUTTON_WIDTH + TerrainMapperConfig.FOOTER_BUTTON_GAP;
        state.getSimulateSeedButton()
                .position(right - TerrainMapperConfig.FOOTER_BUTTON_WIDTH, y);
        float left = footer.x() + TerrainMapperConfig.FOOTER_BUTTON_GAP;
        state.getBackButton().position(left, y);
        left += TerrainMapperConfig.FOOTER_BUTTON_WIDTH + TerrainMapperConfig.FOOTER_BUTTON_GAP;
        state.getCharacterButton().position(left, y);
    }

    private void drawStatusText(Canvas canvas, MasonryUI ui, Rect footer) {
        Font meta = ui.fonts().get(MStyle.FONT_META);
        float textY = footer.y() + footer.height() / 2f + 4f;

        String modeLabel = "Mode: " + state.getActiveVisualizer().displayName();
        float modeX = footer.x() + TerrainMapperConfig.FOOTER_BUTTON_WIDTH
                + TerrainMapperConfig.FOOTER_BUTTON_GAP * 2f;
        MPainter.drawString(canvas, modeLabel, modeX, textY - 10f, meta, MStyle.TEXT_SECONDARY);

        if (state.hasHoverValue()) {
            NoiseVisualizer visualizer = state.getVisualizers().get(state.getActiveVisualizer());
            String formatted = visualizer != null
                    ? visualizer.formatValue(state.hoverRawValue())
                    : String.format("%.3f", state.hoverRawValue());
            String hover = String.format("(%d, %d) = %s",
                    state.hoverWorldX(), state.hoverWorldZ(), formatted);
            MPainter.drawString(canvas, hover, modeX, textY + 10f, meta, MStyle.TEXT_PRIMARY);
        }
    }
}
