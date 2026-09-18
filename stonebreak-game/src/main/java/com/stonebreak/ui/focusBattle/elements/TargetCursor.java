package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.CombatantView;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MSymbol;
import com.stonebreak.rendering.UI.masonryUI.MWorldMarker;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.BattleHudAnimState;
import com.stonebreak.ui.focusBattle.BattleHudRules;
import com.stonebreak.ui.focusBattle.BattleMenuState;
import com.stonebreak.ui.focusBattle.BattlePalette;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import com.stonebreak.ui.focusBattle.SkijaFocusBattleRenderer;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import org.joml.Matrix4fc;

/**
 * E8: the target step's pointing hand and name tag over the Archon. {@link MWorldMarker} projects a
 * point three quarters up the Archon's body through the live camera every frame, so the cursor
 * follows each cut; an anchor that is off-screen, hidden under the HUD's own windows, or without a
 * camera matrix pins the cursor to the enemy plate instead, so the step always has something to
 * point at. The hand is {@link MSymbol#HAND_POINT}, the tag a small {@link MPainter#hudFrame HUD frame}.
 *
 * <p>{@link #place} is the single source of the cursor's geometry: the painter draws from it and
 * the screen hit-tests mouse clicks against the very same {@link Placement}.
 */
public final class TargetCursor implements SkijaFocusBattleRenderer.Layer {

    /** Height of the body point the cursor aims at, as a fraction of the model's height. */
    public static final float ANCHOR_HEIGHT = 0.75f;

    private static final float HAND_SIZE = 44f;
    /** {@link MSymbol#HAND_POINT} ends its finger this far short of its box's right edge, and this far above centre. */
    private static final float HAND_TIP_INSET = 0.08f;
    private static final float HAND_TIP_RISE = 0.135f;
    private static final float BOB_PX = 5f;
    private static final float TAG_W = 150f;
    private static final float TAG_H = 26f;
    private static final float TAG_PAD = 10f;
    private static final float MIN_BODY_PX = 56f;

    /**
     * Where the cursor is this frame.
     *
     * @param tipX     x the finger tip points at (before the bob)
     * @param tipY     y of the finger tip
     * @param pinned   true when the anchor was off-screen and the cursor sits on the enemy plate
     * @param bodyRect the clickable area: the Archon's projected body, or the enemy plate when pinned
     * @param handRect the hand's box
     * @param tagRect  the most room the name tag may take (the frame shrinks to the name inside it)
     */
    public record Placement(float tipX, float tipY, boolean pinned, float[] bodyRect, float[] handRect,
                            float[] tagRect) {
        /** True when a click at {@code (px, py)} means "this target". */
        public boolean contains(float px, float py) {
            return FocusBattleLayout.contains(px, py, bodyRect) || FocusBattleLayout.contains(px, py, handRect)
                    || FocusBattleLayout.contains(px, py, tagRect);
        }
    }

    private final BattleMenuState menu;
    private BattleStageLayout stage;

    public TargetCursor(BattleMenuState menu) {
        this.menu = menu;
    }

    public void setStage(BattleStageLayout stage) {
        this.stage = stage;
    }

    /**
     * Vertical span {@code {top, bottom}} in which a world-anchored cursor is usable: clear of the
     * enemy plate and banner above, and of the help strip and everything under it below.
     */
    public static float[] band(int w, int h, float rawUiScale, float scale) {
        float[] banner = FocusBattleLayout.actionBannerRect(w, h, rawUiScale);
        float[] help = FocusBattleLayout.helpStripRect(w, h, rawUiScale);
        float top = banner[1] + banner[3] - 14f * scale;
        return new float[]{top, Math.max(top, help[1] - 18f * scale)};
    }

    /**
     * Places the cursor for the current camera.
     *
     * @param stage          the battle's staging, or null (pins to the plate)
     * @param viewProjection projection × view, or null (pins to the plate)
     * @param rawUiScale     the user's UI scale, as {@link FocusBattleLayout} takes it
     */
    public static Placement place(BattleStageLayout stage, BattleView view, Matrix4fc viewProjection,
                                  int w, int h, float rawUiScale) {
        float s = FocusBattleLayout.effectiveScale(w, h, rawUiScale);
        float[] plate = FocusBattleLayout.enemyPlateRect(w, h, rawUiScale);
        float hand = HAND_SIZE * s;

        MWorldMarker.Anchor anchor = MWorldMarker.Anchor.NONE;
        CombatantView archon = view == null ? null : view.archon();
        if (stage != null && archon != null) {
            anchor = MWorldMarker.project(viewProjection,
                    stage.bodyPoint(CombatantId.ARCHON, archon.pose(), ANCHOR_HEIGHT), w, h);
        }
        // A cursor hidden under the top or bottom windows is as good as off-screen.
        float[] band = band(w, h, rawUiScale, s);
        anchor = MWorldMarker.withinBand(anchor, 0f, band[0], w, band[1]);
        boolean pinned = !anchor.onScreen();
        // Pinned, the finger rests just outside the plate's left edge, never beyond the window's.
        anchor = MWorldMarker.orFallback(anchor, Math.max(hand + 4f * s, plate[0] - 6f * s), plate[1] + plate[3] / 2f);

        float[] body = plate;
        float tipX = anchor.x();
        if (!pinned) {
            float bodyH = MWorldMarker.radiusFor(anchor, stage.height(CombatantId.ARCHON), MIN_BODY_PX * s, Float.MAX_VALUE);
            float bodyW = Math.max(MIN_BODY_PX * s, bodyH * 0.6f);
            body = new float[]{anchor.x() - bodyW / 2f, anchor.y() - bodyH * (1f - ANCHOR_HEIGHT), bodyW, bodyH};
            // The hand points at the body's left flank.
            tipX = Math.max(hand + 6f * s, anchor.x() - bodyW * 0.42f);
        }
        float[] handRect = {tipX - hand * (1f - HAND_TIP_INSET), anchor.y() - hand * (0.5f - HAND_TIP_RISE), hand, hand};
        // The name tag hangs centred under the hand, kept inside the window.
        float tagW = TAG_W * s;
        float tagX = Math.max(2f, Math.min(w - tagW - 2f, handRect[0] + hand / 2f - tagW / 2f));
        float[] tagRect = {tagX, handRect[1] + hand * 0.84f + 6f * s, tagW, TAG_H * s};
        return new Placement(tipX, anchor.y(), pinned, body, handRect, tagRect);
    }

    @Override
    public void paint(MasonryUI ui, Canvas canvas, int windowWidth, int windowHeight, float uiScale,
                      float rawUiScale, BattleView view, BattleHudAnimState anim, Matrix4fc viewProjection) {
        if (canvas == null || view == null || menu == null || !menu.targeting() || !BattleHudRules.menuLive(view)) return;
        Placement p = place(stage, view, viewProjection, windowWidth, windowHeight, rawUiScale);
        paint(ui, canvas, p, view.archon() == null ? "" : view.archon().displayName(), uiScale,
                anim == null ? 0f : anim.targetBob);
    }

    /**
     * Draws a placed cursor (separate from the layer so raster tests can pin a placement).
     *
     * @param bob −1..1 phase of the hand's horizontal bob
     */
    public static void paint(MasonryUI ui, Canvas canvas, Placement p, String targetName, float uiScale, float bob) {
        if (ui == null || canvas == null || p == null) return;
        float[] hand = p.handRect();
        // The bob only ever pulls the finger back from the target, never pushes it into the body.
        float dx = (bob - 1f) * BOB_PX * uiScale;
        // No frame behind it: the hand wears the same dark rim as words drawn over the scene.
        float rim = Math.max(1f, 1.5f * uiScale);
        for (int i = 0; i < 4; i++) {
            float ox = (i < 2 ? -rim : rim), oy = (i % 2 == 0 ? -rim : rim);
            MSymbol.HAND_POINT.draw(canvas, hand[0] + dx + ox, hand[1] + oy, hand[2], hand[3], MStyle.OUTLINE_DARK);
        }
        MSymbol.HAND_POINT.draw(canvas, hand[0] + dx, hand[1], hand[2], hand[3], MStyle.TEXT_PRIMARY);

        if (targetName == null || targetName.isEmpty()) return;
        float[] slot = p.tagRect();
        float pad = TAG_PAD * uiScale;
        Font font = ui.fonts().fit(targetName, MStyle.FONT_META * uiScale, slot[2] - 2f * pad, 0.6f);
        if (font == null) return;
        // The frame shrinks to the name: the slot is only the most it may take.
        float w = Math.min(slot[2], MPainter.measureWidth(font, targetName) + 2f * pad);
        float x = slot[0] + (slot[2] - w) / 2f;
        MPainter.hudFrame(canvas, x, slot[1], w, slot[3], BattlePalette.ACCENT_ARCHON, 1f);
        MPainter.drawText(canvas, targetName, x + w / 2f,
                MPainter.baselineFor(slot[1] + slot[3] / 2f + uiScale, font.getSize()), font, MStyle.TEXT_PRIMARY,
                MPainter.Align.CENTER);
    }
}
