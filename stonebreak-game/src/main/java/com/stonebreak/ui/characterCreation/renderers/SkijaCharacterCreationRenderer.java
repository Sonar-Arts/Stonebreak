package com.stonebreak.ui.characterCreation.renderers;

import com.stonebreak.player.CharacterStats;
import com.stonebreak.rendering.UI.masonryUI.MButton;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.rendering.UI.masonryUI.textures.MTexture;
import com.stonebreak.rendering.UI.masonryUI.textures.MTextureRegistry;
import com.stonebreak.ui.characterCreation.CharacterCreationActionHandler;
import com.stonebreak.ui.characterCreation.CharacterCreationLayout;
import com.stonebreak.ui.characterCreation.CharacterCreationLayout.Rect;
import com.stonebreak.ui.characterCreation.CharacterCreationStateManager;
import com.stonebreak.ui.characterCreation.CharacterCreationTab;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.RRect;
import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.sbe.SbeEntityAsset;
import com.stonebreak.mobs.sbe.SbeEntityRegistry;
import com.stonebreak.mobs.sbe.SbeModelGeometry;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.models.entities.EntityRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

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

    // Wood-panel fill reuses the recipe-screen Elm UI texture.
    private static final String WOOD_PANEL_RESOURCE = "/ui/recipeScreen/Elm_UI.sbt";

    private final MasonryUI ui;
    private final CharacterCreationStateManager state;
    private final CharacterCreationLayout layout;

    // Sub-renderers
    private final CharacterModelPreviewRenderer modelPreviewRenderer;
    private final BackgroundTabRenderer      backgroundTab;
    private final AbilityScoreTabRenderer    abilityScoreTab;
    private final ClassAbilitiesTabRenderer  classAbilitiesTab;
    private final SkillsTabRenderer          skillsTab;
    private final FeatsTabRenderer           featsTab;
    private final LooksTabRenderer           looksTab;

    // One MButton per tab for hover tracking and click detection
    private final MButton[] tabButtons = new MButton[CharacterCreationTab.values().length];

    // Wood planks texture shader — lazily built, null until the backend is ready
    private Shader woodPlanksShader;

    // Preview rect (window pixels) captured during the Skija frame, then drawn
    // into with raw GL after the frame closes. Null when no preview this frame.
    private CharacterModelPreviewRenderer.PreviewRect previewRect;

    // Cached player model AABB {minX,minY,minZ,maxX,maxY,maxZ}, lazily computed.
    private float[] playerBounds;

    public SkijaCharacterCreationRenderer(MasonryUI ui,
                                          CharacterCreationStateManager state,
                                          CharacterCreationLayout layout) {
        this.ui     = ui;
        this.state  = state;
        this.layout = layout;

        this.modelPreviewRenderer = new CharacterModelPreviewRenderer();
        this.backgroundTab      = new BackgroundTabRenderer();
        this.abilityScoreTab    = new AbilityScoreTabRenderer();
        this.classAbilitiesTab  = new ClassAbilitiesTabRenderer();
        this.skillsTab          = new SkillsTabRenderer();
        this.featsTab           = new FeatsTabRenderer();
        this.looksTab           = new LooksTabRenderer();

        CharacterCreationTab[] tabs = CharacterCreationTab.values();
        for (int i = 0; i < tabs.length; i++) {
            tabButtons[i] = new MButton(tabs[i].displayName()).fontSize(MStyle.FONT_META);
        }
    }

    // ─────────────────────────────────────────────── Render

    public void render(int w, int h) {
        if (!ui.isAvailable()) return;
        if (!ui.beginFrame(w, h, 1.0f)) return;

        previewRect = null;
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

        // 3D player model: drawn with raw GL on top of the just-composited panel.
        // The Skija surface wraps the default framebuffer, so a scissored viewport
        // draw lands on top of the inset in the same presented frame.
        drawPlayerPreview(w, h);
    }

    // ─────────────────────────────────────────────── Left panel

    private void drawLeftPanelContent(Canvas canvas, Rect leftPanel, float mx, float my) {
        // Tile wood planks texture inside the panel, clipped to its rounded rect
        ensureWoodPlanksShader();
        if (woodPlanksShader != null) {
            int save = canvas.save();
            canvas.clipRRect(RRect.makeXYWH(
                leftPanel.x(), leftPanel.y(), leftPanel.width(), leftPanel.height(),
                MStyle.PANEL_RADIUS), true);
            try (Paint p = new Paint().setShader(woodPlanksShader)) {
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

        previewRect = modelPreviewRenderer.render(canvas, ui,
            leftPanel.x(), leftPanel.y(), leftPanel.width(), leftPanel.height(),
            state.getCharacterStats());
    }

    // ─────────────────────────────────────────────── 3D player preview

    /**
     * Renders the player SBE model into the inset rect captured during the Skija
     * frame. Mirrors the Entity Glossary's preview pass: scissor + depth to the
     * rect, orbit camera framed on the model AABB, then restore a clean GL
     * baseline matching SkiaContext.restoreGLDefaults().
     */
    private void drawPlayerPreview(int windowWidth, int windowHeight) {
        if (previewRect == null) return;
        Renderer renderer = Game.getRenderer();
        if (renderer == null) return;
        EntityRenderer entityRenderer = renderer.getEntityRenderer();
        if (entityRenderer == null) return;

        float[] b = playerBounds();
        if (b == null) return;

        int vx = Math.round(previewRect.x());
        int vy = Math.round(windowHeight - (previewRect.y() + previewRect.h())); // GL origin bottom-left
        int vw = Math.round(previewRect.w());
        int vh = Math.round(previewRect.h());
        if (vw <= 0 || vh <= 0) return;

        float time = Game.getInstance().getTotalTimeElapsed();
        float az = time * 0.6f;                       // slow orbit (rad/s)
        float el = (float) Math.toRadians(12.0);      // camera elevation
        float fov = (float) Math.toRadians(35.0);
        float halfFovTan = (float) Math.tan(fov / 2f);

        float ctrX = (b[0] + b[3]) / 2f, ctrY = (b[1] + b[4]) / 2f, ctrZ = (b[2] + b[5]) / 2f;
        float ex = b[3] - b[0], ey = b[4] - b[1], ez = b[5] - b[2];
        float radius = 0.5f * (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
        if (radius <= 0f) radius = 1f;

        // Frame to the model's height (portrait viewport), so the full body fits.
        // The AABB is the bare player's — zoom out a little extra when a hat
        // (Looks tab) is mounted so it doesn't poke out of frame.
        float margin = com.stonebreak.mobs.sbe.EntityAttachments.get(
                com.stonebreak.mobs.sbe.EntityAttachments.LOCAL_PLAYER).isEmpty()
                ? 1.15f : 1.3f;
        float dist = radius / halfFovTan * margin;
        float horiz = dist * (float) Math.cos(el);
        float eyeX = ctrX + horiz * (float) Math.sin(az);
        float eyeZ = ctrZ + horiz * (float) Math.cos(az);
        float eyeY = ctrY + dist * (float) Math.sin(el);

        Matrix4f view = new Matrix4f().setLookAt(eyeX, eyeY, eyeZ, ctrX, ctrY, ctrZ, 0f, 1f, 0f);
        Matrix4f proj = new Matrix4f().setPerspective(fov, (float) vw / vh, 0.05f, dist + radius * 4f);

        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        GL11.glViewport(vx, vy, vw, vh);
        GL11.glScissor(vx, vy, vw, vh);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);

        // LOCAL_PLAYER as the attachment key so the equipped hat (Looks tab)
        // shows on the preview model exactly as it will in-game.
        entityRenderer.renderPlayerPreview(null, time, new Vector3f(0f, 0f, 0f), 0f,
                new Vector3f(1f, 1f, 1f), view, proj,
                com.stonebreak.mobs.sbe.EntityAttachments.LOCAL_PLAYER);

        // Restore a clean GL baseline matching SkiaContext.restoreGLDefaults().
        GL11.glScissor(0, 0, windowWidth, windowHeight);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glViewport(0, 0, windowWidth, windowHeight);
        GL20.glUseProgram(0);
        GL30.glBindVertexArray(0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    /** Player model AABB {minX,minY,minZ,maxX,maxY,maxZ}, cached; null if unavailable. */
    private float[] playerBounds() {
        if (playerBounds != null) return playerBounds;
        SbeEntityAsset asset = SbeEntityRegistry.get(EntityType.REMOTE_PLAYER.getSbeObjectId());
        if (asset == null) return null;
        SbeModelGeometry geo = asset.geometryFor(SbeEntityAsset.DEFAULT_VARIANT);
        if (geo == null) return null;
        float[] v = geo.vertices();
        if (v == null || v.length < 3) return null;

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i + 2 < v.length; i += 3) {
            minX = Math.min(minX, v[i]);     maxX = Math.max(maxX, v[i]);
            minY = Math.min(minY, v[i + 1]); maxY = Math.max(maxY, v[i + 1]);
            minZ = Math.min(minZ, v[i + 2]); maxZ = Math.max(maxZ, v[i + 2]);
        }
        playerBounds = new float[]{minX, minY, minZ, maxX, maxY, maxZ};
        return playerBounds;
    }

    private void ensureWoodPlanksShader() {
        if (woodPlanksShader != null) return;
        // Reuse the recipe-screen Elm UI texture (registry-cached, shared).
        MTexture planks = MTextureRegistry.get(WOOD_PANEL_RESOURCE);
        if (planks == null || planks.image() == null) return;
        woodPlanksShader = planks.image().makeShader(
            FilterTileMode.REPEAT, FilterTileMode.REPEAT, SamplingMode.DEFAULT, null);
    }

    // ─────────────────────────────────────────────── Tab bar

    private void drawTabBar(Canvas canvas, Rect tabBar, float mx, float my) {
        CharacterCreationTab[] tabs = CharacterCreationTab.values();
        float startX = tabBar.x() + 8f;
        // Shrink below the preferred width when the bar can't fit every tab.
        float avail = tabBar.width() - 16f;
        float tabW = Math.min(TAB_W,
            (avail - (tabs.length - 1) * TAB_GAP) / tabs.length);

        for (int i = 0; i < tabs.length; i++) {
            CharacterCreationTab tab = tabs[i];
            float tx = startX + i * (tabW + TAB_GAP);
            float ty = tabBar.y();

            tabButtons[i].bounds(tx, ty, tabW, TAB_H);
            tabButtons[i].updateHover(mx, my);

            boolean active  = state.getActiveTab() == tab;
            boolean hovered = tabButtons[i].isHovered();

            int fill = active  ? ACTIVE_TAB_FILL
                : hovered ? MStyle.BUTTON_FILL_HI
                : MStyle.BUTTON_FILL;

            MPainter.stoneSurface(canvas, tx, ty, tabW, TAB_H, MStyle.BUTTON_RADIUS,
                fill, MStyle.BUTTON_BORDER,
                MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
                MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);

            Font font    = ui.fonts().get(MStyle.FONT_META);
            int tabColor = active ? MStyle.TEXT_ACCENT : MStyle.TEXT_PRIMARY;
            float textY  = ty + TAB_H * 0.5f + MStyle.FONT_META * 0.38f;
            MPainter.drawCenteredStringWithShadow(canvas, tab.displayName(),
                tx + tabW / 2f, textY, font, tabColor, MStyle.TEXT_SHADOW);
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
            case LOOKS           -> looksTab.render(canvas, ui, tabContent, mx, my);
        }
    }

    // ─────────────────────────────────────────────── Footer

    private void drawFooter(Rect footer) {
        float btnY = footer.y() + (footer.height() - 44f) / 2f;

        MButton back = state.getBackToWorldSelectButton();
        back.size(back.preferredWidth(ui), back.height())
            .position(footer.x() + 16f, btnY);
        back.render(ui);

        MButton next = state.getTerrainMapperButton();
        float nextW = next.preferredWidth(ui);
        next.size(nextW, next.height())
            .position(footer.right() - 16f - nextW, btnY);
        next.render(ui);
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
            case LOOKS           -> looksTab.handleClick(mx, my, actions);
        };
    }

    public void dispose() {
        if (woodPlanksShader != null) { woodPlanksShader.close(); woodPlanksShader = null; }
    }
}
