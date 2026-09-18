package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.CombatantView;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.BattleHudAnimState;
import com.stonebreak.ui.focusBattle.BattleMenuState;
import com.stonebreak.ui.focusBattle.FlowLayout;
import com.stonebreak.ui.focusBattle.FlowTheme;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import com.stonebreak.ui.focusBattle.FocusBattleTheme;
import com.stonebreak.ui.focusBattle.SkijaFocusBattleRenderer;
import com.stonebreak.ui.focusBattle.WorldProjection;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * E8 — the target step's pointing hand and name tag over the Archon. The anchor is a point three
 * quarters up the Archon's body, projected through the live camera every frame so the cursor
 * follows each cut; when that point is off-screen (or no camera matrix is known) the cursor pins
 * itself to the enemy plate instead, so the step is never without something to point at.
 *
 * <p>{@link #place} is the single source of the cursor's geometry: the painter draws from it and
 * the screen hit-tests mouse clicks against the very same {@link Placement}.
 */
public final class TargetCursor implements SkijaFocusBattleRenderer.Layer {

    /** Height of the body point the cursor aims at, as a fraction of the model's height. */
    public static final float ANCHOR_HEIGHT = 0.75f;

    private static final float HAND_W = 46f;
    private static final float HAND_H = 34f;
    private static final float BOB_PX = 5f;
    private static final float TAG_H = 24f;
    private static final float TAG_PAD = 10f;
    private static final float MIN_BODY_PX = 56f;

    /**
     * Where the cursor is this frame.
     *
     * @param tipX     x the finger tip points at (before the bob)
     * @param tipY     y of the finger tip
     * @param pinned   true when the anchor was off-screen and the cursor sits on the enemy plate
     * @param bodyRect the clickable area: the Archon's projected body, or the enemy plate when pinned
     * @param handRect the hand's box, finger tip on its right edge
     * @param tagRect  the name tag's box (width is a layout estimate; the painter fits text inside)
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
     * Places the cursor for the current camera.
     *
     * @param stage          the battle's staging, or null (pins to the plate)
     * @param viewProjection projection × view, or null (pins to the plate)
     * @param rawUiScale     the user's UI scale, as {@link FocusBattleLayout} takes it
     */
    public static Placement place(BattleStageLayout stage, BattleView view, Matrix4fc viewProjection,
                                  int w, int h, float rawUiScale) {
        float s = FocusBattleLayout.effectiveScale(w, h, rawUiScale);
        float handW = HAND_W * s, handH = HAND_H * s;
        float tagW = 150f * s, tagH = TAG_H * s;

        float[] screen = null;
        float bodyPx = 0f;
        CombatantView archon = view == null ? null : view.archon();
        if (stage != null && archon != null && viewProjection != null) {
            Vector3f anchor = stage.bodyPoint(CombatantId.ARCHON, archon.pose(), ANCHOR_HEIGHT);
            screen = WorldProjection.toScreen(viewProjection, anchor, w, h, 0f);
            bodyPx = WorldProjection.pixelsPerBlock(viewProjection, anchor, h) * stage.height(CombatantId.ARCHON);
        }
        if (screen != null) {
            // A cursor hidden under the top or bottom windows is as good as off-screen.
            float[] band = FlowLayout.targetBand(w, h, rawUiScale);
            if (screen[1] < band[0] || screen[1] > band[1]) screen = null;
        }

        if (screen == null) {
            float[] plate = FocusBattleLayout.enemyPlateRect(w, h, rawUiScale);
            float tipX = Math.max(handW + 4f * s, plate[0] - 6f * s);
            float tipY = plate[1] + plate[3] / 2f;
            float[] hand = handRect(tipX, tipY, handW, handH);
            return new Placement(tipX, tipY, true, plate, hand, tagRect(hand, tagW, tagH, w, s));
        }

        float bodyH = Math.max(MIN_BODY_PX * s, bodyPx);
        float bodyW = Math.max(MIN_BODY_PX * s, bodyH * 0.6f);
        float[] body = {screen[0] - bodyW / 2f, screen[1] - bodyH * (1f - ANCHOR_HEIGHT), bodyW, bodyH};
        // The hand points at the body's left flank, never from beyond the window's edge.
        float tipX = Math.max(handW + 6f * s, screen[0] - bodyW * 0.42f);
        float tipY = screen[1];
        float[] hand = handRect(tipX, tipY, handW, handH);
        return new Placement(tipX, tipY, false, body, hand, tagRect(hand, tagW, tagH, w, s));
    }

    /** The name tag hangs centred under the hand, kept inside the window. */
    private static float[] tagRect(float[] hand, float tagW, float tagH, int w, float s) {
        float x = Math.max(2f, Math.min(w - tagW - 2f, hand[0] + hand[2] / 2f - tagW / 2f));
        return new float[]{x, hand[1] + hand[3] + 6f * s, tagW, tagH};
    }

    // HandCursor puts the finger tip on the box's right edge, 44% of the way down.
    private static float[] handRect(float tipX, float tipY, float handW, float handH) {
        return new float[]{tipX - handW, tipY - handH * 0.44f, handW, handH};
    }

    @Override
    public void paint(MasonryUI ui, Canvas canvas, int windowWidth, int windowHeight, float uiScale,
                      float rawUiScale, BattleView view, BattleHudAnimState anim, Matrix4fc viewProjection) {
        if (canvas == null || view == null || menu == null || !menu.targeting() || !view.commandWindowOpen()) return;
        Placement p = place(stage, view, viewProjection, windowWidth, windowHeight, rawUiScale);
        paint(ui, canvas, p, view.archon() == null ? "" : view.archon().displayName(), uiScale, anim);
    }

    /** Draws a placed cursor (separate from the layer so raster tests can pin a placement). */
    public static void paint(MasonryUI ui, Canvas canvas, Placement p, String targetName, float uiScale,
                             BattleHudAnimState anim) {
        if (canvas == null || p == null) return;
        float bob = (anim == null ? 0f : anim.targetBob) * BOB_PX * uiScale;
        float[] hand = p.handRect();
        HandCursor.paint(canvas, hand[0] + bob - BOB_PX * uiScale, hand[1], hand[2], hand[3], 1f);

        String name = targetName == null ? "" : targetName;
        if (name.isEmpty()) return;
        float[] tag = p.tagRect();
        Font font = FocusBattleTheme.fitFont(ui, name, FlowTheme.FS_TARGET_TAG, uiScale, tag[2] - 2f * TAG_PAD * uiScale);
        if (font == null) return;
        // Shrink the plate to the name: the layout width is only the most it may take.
        float textW = MPainter.measureWidth(font, name);
        float plateW = Math.min(tag[2], textW + 2f * TAG_PAD * uiScale);
        float plateX = tag[0] + (tag[2] - plateW) / 2f;
        FocusBattleTheme.battleWindow(canvas, plateX, tag[1], plateW, tag[3]);
        FocusBattleTheme.textCentered(canvas, name, plateX + plateW / 2f,
                FocusBattleTheme.baseline(tag[1] + tag[3] / 2f, font.getSize()), font, FocusBattleTheme.TEXT);
    }
}
