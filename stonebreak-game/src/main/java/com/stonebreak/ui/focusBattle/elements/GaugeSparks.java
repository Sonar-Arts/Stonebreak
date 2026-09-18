package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.BattleView;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.BattleHudAnimState;
import com.stonebreak.ui.focusBattle.FlowLayout;
import com.stonebreak.ui.focusBattle.FlowTheme;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import com.stonebreak.ui.focusBattle.FocusBattleTheme;
import com.stonebreak.ui.focusBattle.SkijaFocusBattleRenderer;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import org.joml.Matrix4fc;

/**
 * One-shot sparks drawn <em>over</em> the party window's gauges: the shatter of Qi pips just spent
 * and the burst when Focus reaches its maximum. They decorate rows another painter owns, so they
 * take their geometry from {@link FlowLayout}, which mirrors that painter's slot math, and follow
 * the window's shake and cinematic slide so a spark never detaches from its pip.
 *
 * <p>Stateless: everything comes from {@link BattleHudAnimState}.
 */
public final class GaugeSparks implements SkijaFocusBattleRenderer.Layer {

    @Override
    public void paint(MasonryUI ui, Canvas canvas, int windowWidth, int windowHeight, float uiScale,
                      float rawUiScale, BattleView view, BattleHudAnimState anim, Matrix4fc viewProjection) {
        if (canvas == null || view == null || anim == null) return;
        if (anim.qiSpendFlash <= 0f && anim.focusFullPulse <= 0f) return;
        float[] party = partyRect(windowWidth, windowHeight, uiScale, rawUiScale, anim);
        if (anim.qiSpendFlash > 0f) paintQiShatter(canvas, party, view.maxQi(), uiScale, anim);
        if (anim.focusFullPulse > 0f) paintFocusBurst(canvas, FlowLayout.focusBarRect(party, uiScale),
                anim.focusFullPulse, uiScale);
    }

    /** The party window where it is actually drawn this frame: slid and shaken. */
    static float[] partyRect(int w, int h, float uiScale, float rawUiScale, BattleHudAnimState anim) {
        float[] help = FocusBattleLayout.helpStripRect(w, h, rawUiScale);
        float out = FocusBattleTheme.clamp01(anim.bottomHudSlideOut) * (h - help[1] + 8f * uiScale);
        return FocusBattleLayout.offset(FocusBattleLayout.partyWindowRect(w, h, rawUiScale),
                anim.partyShakeX, anim.partyShakeY + out);
    }

    /** Each emptied pip flashes white, then four shards fly out along the diamond's edges. */
    private static void paintQiShatter(Canvas canvas, float[] party, int slots, float uiScale,
                                       BattleHudAnimState anim) {
        float k = FocusBattleTheme.clamp01(anim.qiSpendFlash);   // 1 at the spend, 0 when done
        float spread = 1f - k;
        for (int i = 0; i < anim.qiSpendCount; i++) {
            int index = anim.qiSpendFirstPip + i;
            if (index < 0 || index >= slots) continue;
            float[] pip = FlowLayout.qiPipRect(party, index, slots, uiScale);
            float cx = pip[0] + pip[2] / 2f, cy = pip[1] + pip[3] / 2f, r = pip[2] / 2f;
            try (Paint flash = new Paint().setColor(FocusBattleTheme.fade(0xFFFFFFFF, k * k)).setAntiAlias(true)) {
                canvas.drawCircle(cx, cy, r * (0.6f + 0.5f * spread), flash);
            }
            float shard = Math.max(1.5f, r * 0.34f * k);
            float reach = r * (0.5f + 1.6f * spread);
            int color = FocusBattleTheme.fade(FocusBattleTheme.QI, k);
            for (int d = 0; d < 4; d++) {
                float dx = (d == 0 || d == 3) ? 1f : -1f;
                float dy = d < 2 ? -1f : 1f;
                FocusBattleTheme.fillRect(canvas, cx + dx * reach * 0.72f - shard / 2f,
                        cy + dy * reach * 0.72f - shard / 2f, shard, shard, color);
            }
        }
    }

    /** A gold frame that leaps off the Focus bar and thins out. */
    private static void paintFocusBurst(Canvas canvas, float[] bar, float pulse, float uiScale) {
        float k = FocusBattleTheme.clamp01(pulse);
        float grow = (1f - k) * 9f * uiScale;
        FocusBattleTheme.roundedFill(canvas, bar[0], bar[1], bar[2], bar[3], 2f,
                FocusBattleTheme.fade(0xFFFFFFFF, k * 0.55f));
        try (Paint p = new Paint().setColor(FocusBattleTheme.fade(FlowTheme.GOLD, k)).setAntiAlias(true)
                .setMode(PaintMode.STROKE).setStrokeWidth(Math.max(1f, 2.5f * uiScale * k))) {
            canvas.drawRRect(io.github.humbleui.types.RRect.makeXYWH(bar[0] - grow, bar[1] - grow,
                    bar[2] + 2f * grow, bar[3] + 2f * grow, 3f * uiScale + grow), p);
        }
    }
}
