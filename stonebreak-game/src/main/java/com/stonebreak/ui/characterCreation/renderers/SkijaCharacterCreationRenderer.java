package com.stonebreak.ui.characterCreation.renderers;

import com.stonebreak.player.CharacterStats;
import com.stonebreak.rendering.UI.masonryUI.MButton;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.characterCreation.CharacterCreationActionHandler;
import com.stonebreak.ui.characterCreation.CharacterCreationLayout;
import com.stonebreak.ui.characterCreation.CharacterCreationLayout.Rect;
import com.stonebreak.ui.characterCreation.CharacterCreationStateManager;
import com.stonebreak.ui.characterCreation.CharacterCreationTab;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.RRect;

/**
 * Per-frame orchestrator for the character creation screen. Draws the dark
 * background, delegates to the panel sub-renderers, then handles tab-bar
 * drawing and click routing.
 */
public final class SkijaCharacterCreationRenderer {

    private static final float TAB_W   = 150f;
    private static final float TAB_H   = 36f;
    private static final float TAB_GAP = 4f;

    private static final int ACTIVE_TAB_FILL = 0xFF7A7A7A;

    private final MasonryUI ui;
    private final CharacterCreationStateManager state;
    private final CharacterCreationLayout layout;

    // Sub-renderers
    private final CharacterCylinderRenderer  cylinderRenderer;
    private final BackgroundTabRenderer      backgroundTab;
    private final AbilityScoreTabRenderer    abilityScoreTab;
    private final ClassAbilitiesTabRenderer  classAbilitiesTab;
    private final SkillsTabRenderer          skillsTab;
    private final FeatsTabRenderer           featsTab;

    // One MButton per tab for hover tracking and click detection
    private final MButton[] tabButtons = new MButton[CharacterCreationTab.values().length];

    // Dirt texture shader — lazily built, null until the backend is ready
    private Shader dirtShader;

    public SkijaCharacterCreationRenderer(MasonryUI ui,
                                          CharacterCreationStateManager state,
                                          CharacterCreationLayout layout) {
        this.ui     = ui;
        this.state  = state;
        this.layout = layout;

        this.cylinderRenderer   = new CharacterCylinderRenderer();
        this.backgroundTab      = new BackgroundTabRenderer();
        this.abilityScoreTab    = new AbilityScoreTabRenderer();
        this.classAbilitiesTab  = new ClassAbilitiesTabRenderer();
        this.skillsTab          = new SkillsTabRenderer();
        this.featsTab           = new FeatsTabRenderer();

        CharacterCreationTab[] tabs = CharacterCreationTab.values();
        for (int i = 0; i < tabs.length; i++) {
            tabButtons[i] = new MButton(tabs[i].displayName()).fontSize(MStyle.FONT_META);
        }
    }

    // ─────────────────────────────────────────────── Render

    public void render(int w, int h) {
        if (!ui.isAvailable()) return;
        if (!ui.beginFrame(w, h, 1.0f)) return;

        try {
            Canvas canvas = ui.canvas();
            if (canvas == null) return;

            Rect leftPanel  = layout.leftPanel(w, h);
            Rect rightPanel = layout.rightPanel(w, h);
            Rect footer     = layout.footer(w, h);
            Rect tabBar     = layout.tabBar(rightPanel);
            Rect tabContent = layout.tabContent(rightPanel, tabBar);

            float mx = state.getMouseX();
            float my = state.getMouseY();

            // Dark full-screen background
            MPainter.fillRect(canvas, 0, 0, w, h, 0xFF1A1A1A);

            // Left panel
            MPainter.panel(canvas, leftPanel.x(), leftPanel.y(),
                leftPanel.width(), leftPanel.height());
            drawLeftPanelContent(canvas, leftPanel, mx, my);

            // Right panel
            MPainter.panel(canvas, rightPanel.x(), rightPanel.y(),
                rightPanel.width(), rightPanel.height());
            drawTabBar(canvas, tabBar, mx, my);
            drawActiveTabContent(canvas, tabContent, mx, my);

            // Footer
            MPainter.panel(canvas, footer.x(), footer.y(), footer.width(), footer.height());
            drawFooter(footer);

            ui.renderOverlays();
        } finally {
            ui.endFrame();
        }
    }

    // ─────────────────────────────────────────────── Left panel

    private void drawLeftPanelContent(Canvas canvas, Rect leftPanel, float mx, float my) {
        // Tile dirt texture inside the panel, clipped to its rounded rect
        ensureDirtShader();
        if (dirtShader != null) {
            int save = canvas.save();
            canvas.clipRRect(RRect.makeXYWH(
                leftPanel.x(), leftPanel.y(), leftPanel.width(), leftPanel.height(),
                MStyle.PANEL_RADIUS), true);
            try (Paint p = new Paint().setShader(dirtShader)) {
                canvas.save();
                canvas.translate(leftPanel.x(), leftPanel.y());
                canvas.scale(4f, 4f);
                canvas.drawRect(io.github.humbleui.types.Rect.makeXYWH(
                    0, 0, leftPanel.width() / 4f, leftPanel.height() / 4f), p);
                canvas.restore();
            }
            // Dark tint so the cylinder and text remain legible
            try (Paint tint = new Paint().setColor(0xAA000000)) {
                canvas.drawRect(io.github.humbleui.types.Rect.makeXYWH(
                    leftPanel.x(), leftPanel.y(), leftPanel.width(), leftPanel.height()), tint);
            }
            canvas.restoreToCount(save);
        }

        Font titleFont = ui.fonts().get(MStyle.FONT_ITEM);
        float titleX   = leftPanel.centerX();
        float titleY   = leftPanel.y() + 26f;

        MPainter.drawCenteredStringWithShadow(canvas, "Character",
            titleX, titleY, titleFont, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);

        cylinderRenderer.render(canvas, ui,
            leftPanel.x(), leftPanel.y(), leftPanel.width(), leftPanel.height(),
            state.getCharacterStats());
    }

    private void ensureDirtShader() {
        if (dirtShader != null) return;
        if (ui.backend() == null) return;
        Image dirt = ui.backend().getDirtTexture();
        if (dirt == null) return;
        dirtShader = dirt.makeShader(FilterTileMode.REPEAT, FilterTileMode.REPEAT, SamplingMode.DEFAULT, null);
    }

    // ─────────────────────────────────────────────── Tab bar

    private void drawTabBar(Canvas canvas, Rect tabBar, float mx, float my) {
        CharacterCreationTab[] tabs = CharacterCreationTab.values();
        float startX = tabBar.x() + 8f;

        for (int i = 0; i < tabs.length; i++) {
            CharacterCreationTab tab = tabs[i];
            float tx = startX + i * (TAB_W + TAB_GAP);
            float ty = tabBar.y();

            tabButtons[i].bounds(tx, ty, TAB_W, TAB_H);
            tabButtons[i].updateHover(mx, my);

            boolean active  = state.getActiveTab() == tab;
            boolean hovered = tabButtons[i].isHovered();

            int fill = active  ? ACTIVE_TAB_FILL
                : hovered ? MStyle.BUTTON_FILL_HI
                : MStyle.BUTTON_FILL;

            MPainter.stoneSurface(canvas, tx, ty, TAB_W, TAB_H, MStyle.BUTTON_RADIUS,
                fill, MStyle.BUTTON_BORDER,
                MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
                MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);

            Font font    = ui.fonts().get(MStyle.FONT_META);
            int tabColor = active ? MStyle.TEXT_ACCENT : MStyle.TEXT_PRIMARY;
            float textY  = ty + TAB_H * 0.5f + MStyle.FONT_META * 0.38f;
            MPainter.drawCenteredStringWithShadow(canvas, tab.displayName(),
                tx + TAB_W / 2f, textY, font, tabColor, MStyle.TEXT_SHADOW);
        }
    }

    // ─────────────────────────────────────────────── Tab content

    private void drawActiveTabContent(Canvas canvas, Rect tabContent, float mx, float my) {
        CharacterStats stats = state.getCharacterStats();
        switch (state.getActiveTab()) {
            case BACKGROUND      -> backgroundTab.render(canvas, ui, stats, tabContent, mx, my);
            case ABILITY_SCORE   -> abilityScoreTab.render(canvas, ui, stats, tabContent, mx, my);
            case CLASS_ABILITIES -> classAbilitiesTab.render(canvas, ui, stats, state, tabContent, mx, my);
            case SKILLS          -> skillsTab.render(canvas, ui, stats, state, tabContent, mx, my);
            case FEATS           -> featsTab.render(canvas, ui, stats, state, tabContent, mx, my);
        }
    }

    // ─────────────────────────────────────────────── Footer

    private void drawFooter(Rect footer) {
        float btnY = footer.y() + (footer.height() - 44f) / 2f;

        state.getBackToWorldSelectButton()
            .position(footer.x() + 16f, btnY);
        state.getBackToWorldSelectButton().render(ui);

        state.getTerrainMapperButton()
            .position(footer.right() - 16f - 180f, btnY);
        state.getTerrainMapperButton().render(ui);
    }

    // ─────────────────────────────────────────────── Click routing

    /**
     * Called by the mouse handler on every left-press event.
     * Returns true if any widget consumed the click.
     */
    public boolean handleClick(float mx, float my, CharacterCreationActionHandler actions) {
        // Tab bar clicks
        CharacterCreationTab[] tabs = CharacterCreationTab.values();
        for (int i = 0; i < tabs.length; i++) {
            if (tabButtons[i].contains(mx, my)) {
                state.setActiveTab(tabs[i]);
                return true;
            }
        }

        // Active tab content clicks — layout must be re-computed from state mouse pos
        int w = com.stonebreak.core.Game.getWindowWidth();
        int h = com.stonebreak.core.Game.getWindowHeight();
        Rect rightPanel = layout.rightPanel(w, h);
        Rect tabBar     = layout.tabBar(rightPanel);
        Rect tabContent = layout.tabContent(rightPanel, tabBar);

        CharacterStats stats = state.getCharacterStats();
        return switch (state.getActiveTab()) {
            case BACKGROUND      -> backgroundTab.handleClick(mx, my, tabContent, stats, actions);
            case ABILITY_SCORE   -> abilityScoreTab.handleClick(mx, my, stats, actions);
            case CLASS_ABILITIES -> classAbilitiesTab.handleClick(mx, my, stats, state, actions, tabContent);
            case SKILLS          -> skillsTab.handleClick(mx, my, stats, state, actions);
            case FEATS           -> featsTab.handleClick(mx, my, stats, state, actions);
        };
    }

    public void dispose() {
        if (dirtShader != null) { dirtShader.close(); dirtShader = null; }
    }
}
