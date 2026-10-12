package com.stonebreak.ui.focusBattle;

import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.openmason.engine.ui.masonry.MBadge;
import com.openmason.engine.ui.masonry.MStyle;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.stonebreak.ui.focusBattle.elements.CommandWindow;
import com.stonebreak.ui.focusBattle.elements.EnemyPlate;
import com.stonebreak.ui.focusBattle.elements.PartyStatusWindow;
import io.github.humbleui.skija.Canvas;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.List;

/**
 * The Focus battle HUD's paint pieces (#301): the HUD's {@link Windows} and the {@link Layer}s that plug
 * in around them. The {@code stonebreak:battle-hud} provider paints them, through
 * {@link FocusBattleScreen#paintPiece}, into the {@code battle_hud} document, which places, moves and
 * stacks them: underlays beneath the windows (screen FX, letterbox), overlays above them (banner,
 * rings, floaters), and the topmost group above every overlay whoever registered it (result panel,
 * encounter transition): a modal must not depend on being registered last.
 *
 * <p>Also owns the {@link MasonryUI} over the game's Skija backend that frames the encounter swirl
 * ({@link #ui()}).
 */
public final class BattleHudLayers {

    /** One extra HUD element, painted into a layer group's canvas. */
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
     * which resets them on bind and updates them once per frame; the pieces only draw them.
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

    /** Layers with windows of their own: enough to draw any view at rest. */
    public BattleHudLayers(SkijaUIBackend backend) {
        this(backend, new Windows());
    }

    public BattleHudLayers(SkijaUIBackend backend, Windows windows) {
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

    /** The MasonryUI over the game's backend (the encounter swirl's frame). */
    public MasonryUI ui() { return mui; }

    /** A group of registered layers. */
    public enum Group { UNDERLAYS, OVERLAYS, TOPMOST }

    /** Paints one layer group (the document stacks them around its windows). */
    public void paintGroup(Group group, MasonryUI ui, Canvas canvas, int w, int h, float uiScale, BattleView view,
                           BattleHudAnimState anim, Matrix4fc viewProjection) {
        if (canvas == null || view == null) return;
        BattleHudAnimState a = anim != null ? anim : BattleHudAnimState.NEUTRAL;
        float s = FocusBattleLayout.effectiveScale(w, h, uiScale);
        List<Layer> layers = switch (group) {
            case UNDERLAYS -> underlays;
            case OVERLAYS -> overlays;
            case TOPMOST -> topmost;
        };
        for (Layer layer : layers) layer.paint(ui, canvas, w, h, s, uiScale, view, a, viewProjection);
    }

    public void dispose() {
        underlays.clear();
        overlays.clear();
        topmost.clear();
        mui.dispose();
    }
}
