package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.TelegraphView;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.BattleHudAnimState;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import com.stonebreak.ui.focusBattle.FocusBattleTheme;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;

/**
 * E6 — the enemy's gauge. Normally a thin cyan ATB bar. While an attack is telegraphed it becomes a
 * labelled cast bar that fills to the impact and shifts blue → red, with the <b>parry window</b>
 * marked on the same timeline: the bar is {@code 0..impactTime} seconds wide, so the marker is
 * simply {@code parryWindowStart..parryWindowEnd} mapped onto it. A telegraph cancelled by a stun
 * turns grey and cracks.
 */
public final class EnemyGaugeBar {

    private EnemyGaugeBar() {}

    public static void paint(MasonryUI ui, Canvas canvas, float[] rect, float atb, TelegraphView telegraph,
                             float uiScale) {
        paint(ui, canvas, rect, atb, telegraph, uiScale, BattleHudAnimState.NEUTRAL);
    }

    /** {@code anim.parryMarkerPulse} brightens the marker (pulsed while the monk guards). */
    public static void paint(MasonryUI ui, Canvas canvas, float[] rect, float atb, TelegraphView telegraph,
                             float uiScale, BattleHudAnimState anim) {
        if (canvas == null) return;
        if (telegraph == null) {
            float[] bar = FocusBattleLayout.gaugeBarRect(rect, false);
            FocusBattleTheme.bar(canvas, bar[0], bar[1], bar[2], bar[3], atb, FocusBattleTheme.ATB);
            return;
        }
        paintTelegraph(ui, canvas, rect, telegraph, uiScale, anim);
    }

    /**
     * Horizontal extent {@code {x0, x1}} of the parry window on {@code bar}, clamped to the bar.
     * Public so tests (and the Wave-2 marker pulse) can locate it without re-deriving the mapping.
     */
    public static float[] parryMarkerSpan(float[] bar, TelegraphView telegraph) {
        float total = telegraph.impactTime();
        float a = total <= 0f ? 1f : FocusBattleTheme.clamp01(telegraph.parryWindowStart() / total);
        float b = total <= 0f ? 1f : FocusBattleTheme.clamp01(telegraph.parryWindowEnd() / total);
        return new float[]{bar[0] + bar[2] * Math.min(a, b), bar[0] + bar[2] * Math.max(a, b)};
    }

    private static void paintTelegraph(MasonryUI ui, Canvas canvas, float[] rect, TelegraphView t,
                                       float uiScale, BattleHudAnimState anim) {
        float[] bar = FocusBattleLayout.gaugeBarRect(rect, true);
        float[] labelRow = FocusBattleLayout.gaugeLabelRect(rect);
        boolean cancelled = t.cancelled();

        int fill = cancelled ? FocusBattleTheme.TELEGRAPH_CANCELLED : FocusBattleTheme.telegraphColor(t.progress());
        FocusBattleTheme.bar(canvas, bar[0], bar[1], bar[2], bar[3], t.progress(), fill);

        float[] span = parryMarkerSpan(bar, t);
        paintParryMarker(canvas, bar, span, cancelled, anim.parryMarkerPulse, uiScale);
        if (cancelled) paintCracks(canvas, bar, uiScale);

        Font font = FocusBattleTheme.font(ui,
                Math.min(FocusBattleTheme.FS_LABEL, labelRow[3] / Math.max(0.01f, uiScale) * 0.95f), uiScale);
        if (font == null) return;
        float baseline = FocusBattleTheme.baseline(labelRow[1] + labelRow[3] / 2f, font.getSize());
        String name = t.action() == null ? "" : t.action().displayName();
        int nameColor = cancelled ? FocusBattleTheme.TEXT_DISABLED : FocusBattleTheme.lerpColor(
                FocusBattleTheme.TEXT, FocusBattleTheme.TELEGRAPH_END, t.progress() * 0.6f);
        FocusBattleTheme.text(canvas, name, labelRow[0], baseline, font, nameColor);

        // Caption sits over the marker, pushed right of the name and kept inside the gauge.
        String caption = cancelled ? "CANCELLED" : "PARRY";
        float captionW = MPainter.measureWidth(font, caption);
        float minX = labelRow[0] + MPainter.measureWidth(font, name) + 8f * uiScale;
        float maxX = labelRow[0] + labelRow[2] - captionW;
        float x = Math.max(minX, Math.min(maxX, (span[0] + span[1]) / 2f - captionW / 2f));
        if (x <= maxX) {
            FocusBattleTheme.text(canvas, caption, x, baseline, font,
                    cancelled ? FocusBattleTheme.TEXT_DISABLED : FocusBattleTheme.PARRY_MARKER);
        }
    }

    private static void paintParryMarker(Canvas canvas, float[] bar, float[] span, boolean cancelled,
                                         float pulse, float uiScale) {
        // Never thinner than a few pixels: a short window must still be findable at a glance.
        float minW = 4f * uiScale;
        float x0 = span[0], x1 = span[1];
        if (x1 - x0 < minW) {
            x0 = Math.max(bar[0], x1 - minW);
            x1 = Math.min(bar[0] + bar[2], x0 + minW);
        }
        int edge = cancelled ? FocusBattleTheme.TELEGRAPH_CANCELLED : FocusBattleTheme.PARRY_MARKER;
        int fill = cancelled ? FocusBattleTheme.fade(FocusBattleTheme.TELEGRAPH_CANCELLED, 0.35f)
                : FocusBattleTheme.lerpColor(FocusBattleTheme.PARRY_MARKER_FILL, FocusBattleTheme.PARRY_MARKER,
                        FocusBattleTheme.clamp01(pulse) * 0.6f);
        float over = Math.max(2f, 2f * uiScale);   // the bracket overshoots the bar top and bottom
        float t = Math.max(1.5f, 1.5f * uiScale);

        FocusBattleTheme.fillRect(canvas, x0, bar[1], x1 - x0, bar[3], fill);
        FocusBattleTheme.fillRect(canvas, x0, bar[1] - over, t, bar[3] + 2f * over, edge);
        FocusBattleTheme.fillRect(canvas, x1 - t, bar[1] - over, t, bar[3] + 2f * over, edge);
        FocusBattleTheme.fillRect(canvas, x0, bar[1] - over, x1 - x0, t, edge);
        FocusBattleTheme.fillRect(canvas, x0, bar[1] + bar[3] + over - t, x1 - x0, t, edge);
    }

    /** Fixed zig-zag fractures across a cancelled cast bar (deterministic: no randomness). */
    private static void paintCracks(Canvas canvas, float[] bar, float uiScale) {
        float[] at = {0.22f, 0.51f, 0.78f};
        try (Paint p = new Paint().setColor(FocusBattleTheme.BAR_OUTLINE).setAntiAlias(true)
                .setMode(PaintMode.STROKE).setStrokeWidth(Math.max(1f, 1.2f * uiScale))) {
            for (int i = 0; i < at.length; i++) {
                float x = bar[0] + bar[2] * at[i];
                float j = bar[3] * 0.35f * ((i & 1) == 0 ? 1f : -1f);
                try (PathBuilder pb = new PathBuilder()) {
                    pb.moveTo(x, bar[1]);
                    pb.lineTo(x + j, bar[1] + bar[3] * 0.4f);
                    pb.lineTo(x - j * 0.6f, bar[1] + bar[3] * 0.65f);
                    pb.lineTo(x + j * 0.4f, bar[1] + bar[3]);
                    try (Path path = pb.build()) {
                        canvas.drawPath(path, p);
                    }
                }
            }
        }
    }
}
