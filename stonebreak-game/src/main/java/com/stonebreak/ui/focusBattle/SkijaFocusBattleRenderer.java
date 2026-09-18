package com.stonebreak.ui.focusBattle;

import com.stonebreak.battle.api.BattleView;
import com.stonebreak.config.Settings;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.elements.CommandWindow;
import com.stonebreak.ui.focusBattle.elements.EnemyGaugeBar;
import com.stonebreak.ui.focusBattle.elements.EnemyPlate;
import com.stonebreak.ui.focusBattle.elements.HelpStrip;
import com.stonebreak.ui.focusBattle.elements.ModeTag;
import com.stonebreak.ui.focusBattle.elements.PartyStatusWindow;
import com.stonebreak.ui.focusBattle.elements.QiArtsSubmenu;
import io.github.humbleui.skija.Canvas;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.List;

/**
 * Skija/MasonryUI renderer for the Focus battle HUD. Owns one {@link MasonryUI} and draws the whole
 * HUD inside a single {@code beginFrame}/{@code endFrame} pair — never raw GL in between (the
 * backend resets sampler state when the frame closes, and only then).
 *
 * <p>The painters are static and take their rect from {@link FocusBattleLayout}; this class only
 * decides <em>what</em> is visible and applies whole-window motion (slides, shakes) from
 * {@link BattleHudAnimState}. {@link #paintHud} is separate from {@link #render} so raster tests can
 * draw the full HUD onto a CPU canvas.
 *
 * <p>The remaining elements (E7–E12, E14–E16) plug in as {@link Layer}s: underlays draw beneath the
 * windows (screen FX, letterbox), overlays above them (banner, rings, floaters), and the topmost
 * group above every overlay whoever registered it (result panel, encounter transition) — a modal
 * must not depend on being registered last.
 */
public final class SkijaFocusBattleRenderer {

    /** One extra HUD element, painted inside the renderer's single Skija frame. */
    @FunctionalInterface
    public interface Layer {
        /**
         * @param uiScale        the effective HUD scale ({@link FocusBattleLayout#effectiveScale})
         * @param rawUiScale     the user's UI scale, as the {@code FocusBattleLayout} window functions take it
         * @param viewProjection projection × view of the live camera, or null
         */
        void paint(MasonryUI ui, Canvas canvas, int windowWidth, int windowHeight, float uiScale,
                   float rawUiScale, BattleView view, BattleHudAnimState anim, Matrix4fc viewProjection);
    }

    private final MasonryUI mui;
    private final List<Layer> underlays = new ArrayList<>();
    private final List<Layer> overlays = new ArrayList<>();
    private final List<Layer> topmost = new ArrayList<>();

    public SkijaFocusBattleRenderer(SkijaUIBackend backend) {
        this.mui = new MasonryUI(backend);
    }

    /** Draws beneath the HUD windows, in registration order. */
    public void addUnderlay(Layer layer) {
        if (layer != null) underlays.add(layer);
    }

    /** Draws above the HUD windows, in registration order. */
    public void addOverlay(Layer layer) {
        if (layer != null) overlays.add(layer);
    }

    /** Draws above every overlay, in registration order: modals and full-screen transitions. */
    public void addTopmost(Layer layer) {
        if (layer != null) topmost.add(layer);
    }

    public MasonryUI ui() { return mui; }

    /**
     * Draws the HUD for one frame. No-op when the backend is unavailable or {@code view} is null.
     *
     * @param viewProjection projection × view for world-anchored elements; may be null
     */
    public void render(int windowWidth, int windowHeight, BattleView view, BattleMenuState menu,
                       BattleHudAnimState anim, Matrix4fc viewProjection) {
        if (view == null || !mui.isAvailable() || windowWidth <= 0 || windowHeight <= 0) return;
        float uiScale = Settings.getInstance().getUiScale();
        if (!mui.beginFrame(windowWidth, windowHeight, 1.0f)) return;
        try {
            paintHud(mui, mui.canvas(), windowWidth, windowHeight, uiScale, view, menu, anim, viewProjection);
            mui.renderOverlays();
        } finally {
            mui.endFrame();
        }
    }

    /**
     * Paints the whole HUD onto {@code canvas}. Frame bracketing is the caller's job.
     *
     * @param uiScale the user's UI scale (the layout caps it to the window itself)
     */
    public void paintHud(MasonryUI ui, Canvas canvas, int windowWidth, int windowHeight, float uiScale,
                         BattleView view, BattleMenuState menu, BattleHudAnimState anim,
                         Matrix4fc viewProjection) {
        if (canvas == null || view == null) return;
        BattleHudAnimState a = anim != null ? anim : BattleHudAnimState.NEUTRAL;
        BattleMenuState m = menu != null ? menu : new BattleMenuState();
        int w = windowWidth, h = windowHeight;
        float s = FocusBattleLayout.effectiveScale(w, h, uiScale);

        for (Layer layer : underlays) layer.paint(ui, canvas, w, h, s, uiScale, view, a, viewProjection);

        // Top: mode tag, enemy plate + its gauge (shaken together).
        ModeTag.paint(ui, canvas, FocusBattleLayout.modeTagRect(w, h, uiScale), ModeTag.ACTIVE, s);
        float[] plate = FocusBattleLayout.offset(FocusBattleLayout.enemyPlateRect(w, h, uiScale),
                a.enemyShakeX, a.enemyShakeY);
        EnemyPlate.paint(ui, canvas, plate, view.archon(), s, a);
        if (view.archon() != null) {
            EnemyGaugeBar.paint(ui, canvas, FocusBattleLayout.enemyGaugeRect(plate, s), view.archon().atb(),
                    view.telegraph(), s, a);
        }

        // Bottom: everything here slides out together for the cinematic moments.
        float[] cmd = FocusBattleLayout.commandWindowRect(w, h, uiScale);
        float[] help = FocusBattleLayout.helpStripRect(w, h, uiScale);
        // Travel far enough that the topmost of them (the help strip) clears the bottom edge.
        float out = FocusBattleTheme.clamp01(a.bottomHudSlideOut) * (h - help[1] + 8f * s);
        // While the help text cross-fades the animator names the line on screen (the outgoing one).
        BattleHelpText.Line line = a.helpLine != null ? a.helpLine : BattleHelpText.lineFor(view, m);
        HelpStrip.paint(ui, canvas, FocusBattleLayout.offset(help, 0f, out), line, s, a.helpFade);
        PartyStatusWindow.paint(ui, canvas,
                FocusBattleLayout.offset(FocusBattleLayout.partyWindowRect(w, h, uiScale), 0f, out), view, s, a);

        if (view.commandWindowOpen()) {
            float slide = (1f - FocusBattleTheme.clamp01(a.commandSlideIn)) * -(cmd[0] + cmd[2]);
            // Submenu first: it emerges from behind the command window.
            if (m.submenuOpen()) {
                float[] sub = FocusBattleLayout.submenuRect(w, h, uiScale);
                float tuck = (1f - FocusBattleTheme.clamp01(a.submenuSlideIn)) * -(sub[2] * 0.5f);
                canvas.save();
                try {
                    // Clip at E1's right edge so a half-slid submenu never shows through the glass.
                    canvas.clipRect(io.github.humbleui.types.Rect.makeLTRB(cmd[0] + cmd[2] + slide, 0f, w, h));
                    QiArtsSubmenu.paint(ui, canvas, FocusBattleLayout.offset(sub, slide + tuck, out), view, m, s, a);
                } finally {
                    canvas.restore();
                }
            }
            CommandWindow.paint(ui, canvas, FocusBattleLayout.offset(cmd, slide, out), view, m, s, a);
        }

        for (Layer layer : overlays) layer.paint(ui, canvas, w, h, s, uiScale, view, a, viewProjection);
        for (Layer layer : topmost) layer.paint(ui, canvas, w, h, s, uiScale, view, a, viewProjection);
    }

    public void dispose() {
        underlays.clear();
        overlays.clear();
        topmost.clear();
        mui.dispose();
    }
}
