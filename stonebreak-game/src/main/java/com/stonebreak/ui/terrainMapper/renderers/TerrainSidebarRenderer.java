package com.stonebreak.ui.terrainMapper.renderers;

import com.stonebreak.rendering.UI.masonryUI.MButton;
import com.stonebreak.rendering.UI.masonryUI.MCategoryButton;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.terrainMapper.TerrainMapperLayout;
import com.stonebreak.ui.terrainMapper.TerrainMapperLayout.Rect;
import com.stonebreak.ui.terrainMapper.config.TerrainMapperConfig;
import com.stonebreak.ui.terrainMapper.managers.TerrainMapperStateManager;
import com.stonebreak.ui.terrainMapper.managers.TerrainMapperStateManager.ActiveField;
import com.stonebreak.ui.terrainMapper.visualization.VisualizerKind;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * Draws the sidebar: title, world-name input, seed input, visualization
 * mode list. All geometry comes from {@link TerrainMapperLayout} so mouse
 * hit-testing and rendering stay in lockstep.
 */
public final class TerrainSidebarRenderer {

    private final TerrainMapperStateManager state;

    public TerrainSidebarRenderer(TerrainMapperStateManager state) {
        this.state = state;
    }

    public void render(MasonryUI ui, TerrainMapperLayout layout) {
        Canvas canvas = ui.canvas();
        if (canvas == null) return;

        Rect sidebar = layout.sidebar();
        MPainter.panel(canvas, sidebar.x(), sidebar.y(), sidebar.width(), sidebar.height());

        drawTitle(canvas, ui, layout.title());
        drawField(canvas, ui, "World Name", layout.worldNameField(),
                state.getWorldName(), "Enter a name...",
                state.getActiveField() == ActiveField.WORLD_NAME);
        drawField(canvas, ui, "Seed", layout.seedField(),
                state.getSeedText(), "Random if blank",
                state.getActiveField() == ActiveField.SEED);

        drawModeButtons(ui, layout);
        drawSpawnSection(ui, layout);
        drawError(canvas, ui, sidebar);
    }

    private void drawTitle(Canvas canvas, MasonryUI ui, Rect title) {
        Font font = ui.fonts().get(24f);
        MPainter.drawStringWithShadow(canvas, "Terrain Mapper", title.x(), title.y() + 22f,
                font, MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);
    }

    private void drawField(Canvas canvas, MasonryUI ui, String label, Rect rect,
                           String value, String placeholder, boolean focused) {
        Font labelFont = ui.fonts().get(MStyle.FONT_META);
        MPainter.drawString(canvas, label, rect.x(), rect.y() - 6f,
                labelFont, MStyle.TEXT_SECONDARY);

        int fill = focused ? 0xFF2E2E2E : 0xFF1F1F1F;
        int border = focused ? MStyle.TEXT_ACCENT : MStyle.PANEL_BORDER;
        MPainter.fillRoundedRect(canvas, rect.x(), rect.y(), rect.width(), rect.height(), 3f, fill);
        MPainter.strokeRect(canvas, rect.x(), rect.y(), rect.width(), rect.height(), border, 1.5f);

        Font valueFont = ui.fonts().get(MStyle.FONT_ITEM);
        boolean empty = value == null || value.isEmpty();
        String shown = empty ? placeholder : value;
        int textColor = empty ? MStyle.TEXT_SECONDARY : MStyle.TEXT_PRIMARY;
        float textY = rect.y() + rect.height() / 2f + 6f;
        MPainter.drawString(canvas, shown, rect.x() + 10f, textY, valueFont, textColor);

        if (focused && cursorVisible()) {
            float measured = empty ? 0f : MPainter.measureWidth(valueFont, value);
            float cursorX = rect.x() + 10f + measured + 2f;
            MPainter.fillRect(canvas, cursorX, rect.y() + 8f, 2f, rect.height() - 16f,
                    MStyle.TEXT_PRIMARY);
        }
    }

    private void drawModeButtons(MasonryUI ui, TerrainMapperLayout layout) {
        Rect first = layout.firstModeButton();
        int i = 0;
        for (MCategoryButton<VisualizerKind> button : state.getModeButtons()) {
            button.setSelected(button.tag() == state.getActiveVisualizer());
            button.position(first.x(), layout.modeButtonY(i++));
            button.render(ui);
        }
    }

    private void drawSpawnSection(MasonryUI ui, TerrainMapperLayout layout) {
        Canvas canvas = ui.canvas();
        Rect btn = layout.spawnButton();
        Font labelFont = ui.fonts().get(MStyle.FONT_META);
        MPainter.drawString(canvas, "Player Spawn", btn.x(), btn.y() - 6f, labelFont, MStyle.TEXT_SECONDARY);

        MButton setSpawnBtn = state.getSetSpawnButton();
        setSpawnBtn.position(btn.x(), btn.y());
        setSpawnBtn.render(ui);

        Rect cBtn = layout.centerOnSpawnButton();
        MButton centerBtn = state.getCenterOnSpawnButton();
        centerBtn.position(cBtn.x(), cBtn.y());
        centerBtn.render(ui);
    }

    private void drawError(Canvas canvas, MasonryUI ui, Rect sidebar) {
        String error = state.getErrorMessage();
        if (error == null || error.isEmpty()) return;
        Font font = ui.fonts().get(MStyle.FONT_META);
        float y = sidebar.bottom() - TerrainMapperConfig.SIDEBAR_PADDING;
        MPainter.drawString(canvas, error,
                sidebar.x() + TerrainMapperConfig.SIDEBAR_PADDING, y, font, MStyle.TEXT_ERROR);
    }

    /** Simple 500ms cursor blink tied to wall clock. */
    private static boolean cursorVisible() {
        return (System.currentTimeMillis() / 500L) % 2L == 0L;
    }
}
