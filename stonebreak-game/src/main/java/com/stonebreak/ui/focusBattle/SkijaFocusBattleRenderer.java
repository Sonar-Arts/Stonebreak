package com.stonebreak.ui.focusBattle;

import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleOutcome;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.config.Settings;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.MBadge;
import com.stonebreak.rendering.UI.masonryUI.MColor;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.elements.CommandWindow;
import com.stonebreak.ui.focusBattle.elements.EnemyPlate;
import com.stonebreak.ui.focusBattle.elements.HelpStrip;
import com.stonebreak.ui.focusBattle.elements.PartyStatusWindow;
import io.github.humbleui.skija.Canvas;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.List;

/**
 * Skija/MasonryUI renderer for the Focus battle HUD. Owns one {@link MasonryUI} and draws the whole
 * HUD inside a single {@code beginFrame}/{@code endFrame} pair, never raw GL in between (the
 * backend resets sampler state when the frame closes, and only then).
 *
 * <p>The HUD's windows are stateful compositions over MasonryUI widgets ({@link Windows}); this
 * class only decides <em>what</em> is visible, hands each window its rect from
 * {@link FocusBattleLayout} and applies whole-window motion (slides, shakes) from
 * {@link BattleHudAnimState}. {@link #paintHud} is separate from {@link #render} so raster tests
 * can draw the full HUD onto a CPU canvas.
 *
 * <p>The remaining elements plug in as {@link Layer}s: underlays draw beneath the windows (screen
 * FX, letterbox), overlays above them (banner, rings, floaters), and the topmost group above every
 * overlay whoever registered it (result panel, encounter transition): a modal must not depend on
 * being registered last.
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

    /**
     * The HUD's windows: one stateful instance each, owned by whoever drives the HUD (the screen),
     * which resets them on bind and updates them once per frame; the renderer only draws them.
     */
    public static final class Windows {
        public static final String MODE_ACTIVE = "ACTIVE";

        public final CommandWindow command = new CommandWindow();
        public final PartyStatusWindow party = new PartyStatusWindow();
        public final EnemyPlate enemy = new EnemyPlate();
        /** The ATB mode chip; the text leaves room for a future Wait mode. */
        public final MBadge modeTag = new MBadge(MODE_ACTIVE).fillColor(MStyle.DROPDOWN_FILL)
                .textColor(MStyle.TEXT_PRIMARY).fontSize(MStyle.FONT_CAPTION).dot(BattlePalette.ATB);

        public void reset() {
            command.reset();
            party.reset();
            enemy.reset();
        }

        public void update(float dt, BattleView view, BattleMenuState menu, List<BattleEvent> events) {
            command.update(dt, view, menu);
            party.update(dt, view, events);
            enemy.update(dt, view, events);
        }
    }

    private final MasonryUI mui;
    private final Windows windows;
    private final List<Layer> underlays = new ArrayList<>();
    private final List<Layer> overlays = new ArrayList<>();
    private final List<Layer> topmost = new ArrayList<>();

    /** A renderer with windows of its own: enough to draw any view at rest. */
    public SkijaFocusBattleRenderer(SkijaUIBackend backend) {
        this(backend, new Windows());
    }

    public SkijaFocusBattleRenderer(SkijaUIBackend backend, Windows windows) {
        this.mui = new MasonryUI(backend);
        this.windows = windows != null ? windows : new Windows();
    }

    public Windows windows() { return windows; }

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

        // Top: mode tag and the enemy plate (which recoils when the Archon is hit).
        float[] tag = FocusBattleLayout.modeTagRect(w, h, uiScale);
        windows.modeTag.scale(s).bounds(tag[0], tag[1], tag[2], tag[3]).render(ui);
        windows.enemy.render(ui, FocusBattleLayout.offset(FocusBattleLayout.enemyPlateRect(w, h, uiScale),
                a.enemyShakeX, a.enemyShakeY), view, s);

        // Bottom: everything here slides out together for the cinematic moments, far enough that
        // the topmost of them (the help strip) clears the bottom edge.
        float[] help = FocusBattleLayout.helpStripRect(w, h, uiScale);
        float out = MColor.clamp01(a.bottomHudSlideOut) * (h - help[1] + 8f * s);
        // While the help text cross-fades the animator names the line on screen (the outgoing one).
        BattleHelpText.Line line = a.helpLine != null ? a.helpLine : BattleHelpText.lineFor(view, m);
        HelpStrip.render(ui, FocusBattleLayout.offset(help, 0f, out), line, s, a.helpFade);
        windows.party.render(ui, FocusBattleLayout.offset(FocusBattleLayout.partyWindowRect(w, h, uiScale),
                a.partyShakeX, a.partyShakeY + out), view, s);

        // The command window never leaves the screen while the fight runs: between turns it rests in
        // its static state and wakes over a short fade (BattleHudRules.menuLive decides which). Once
        // the fight is decided there is nothing left to choose, and it never sits under the defeat
        // fade or the result panel.
        if (view.outcome() == BattleOutcome.NONE) {
            windows.command.render(ui, FocusBattleLayout.offset(FocusBattleLayout.commandWindowRect(w, h, uiScale), 0f, out),
                    FocusBattleLayout.offset(FocusBattleLayout.submenuRect(w, h, uiScale), 0f, out),
                    view, m, s, a.commandWake, a.submenuSlideIn);
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
