package com.stonebreak.ui.focusBattle.timed;

import com.stonebreak.battle.api.ComboDirection;
import com.stonebreak.battle.api.TimedGrade;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import com.stonebreak.ui.focusBattle.FocusBattleTheme;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * E10 — the Focus Combo strip. One battle window holding the whole direction string: answered
 * prompts are <b>stamped</b> (gold = PERFECT, white = GOOD, cracked red = MISS), the prompt awaiting
 * input is enlarged, pulsing and underlined by a draining timer, and the ones still to come are
 * dimmed. Arrows are vector glyphs (the UI font has none) with the matching key letter in the
 * cell's corner. The strip slides up when the string opens, stays through the finisher, flashes
 * "FLAWLESS!" on a clean string and slides away.
 *
 * <p>Painted entirely from {@link TimedInputState}'s snapshot rather than the live prompt: the last
 * grade arrives in the same frame the prompt disappears.
 */
public final class ComboStripOverlay {

    private ComboStripOverlay() {}

    public static void paint(MasonryUI ui, Canvas canvas, int w, int h, float scale, float rawUiScale,
                             TimedInputState state) {
        if (canvas == null || state == null || state.comboSlide <= 0f) return;
        float[] rest = FocusBattleLayout.comboStripRect(w, h, rawUiScale);
        float[] strip = FocusBattleLayout.offset(rest, 0f, TimedLayout.comboSlideOffset(rest, h, state.comboSlide, scale));
        paintStrip(ui, canvas, strip, scale, state);
    }

    /** The strip at an explicit rect (raster tests, and the slide above). */
    public static void paintStrip(MasonryUI ui, Canvas canvas, float[] strip, float s, TimedInputState state) {
        FocusBattleTheme.battleWindow(canvas, strip);
        float flawless = state.flawlessAge < 0f ? 0f
                : TimedTheme.flashEnvelope(state.flawlessAge / TimedInputState.FLAWLESS_SECONDS);
        if (flawless > 0f) {
            FocusBattleTheme.roundedStroke(canvas, strip[0] - 2f * s, strip[1] - 2f * s, strip[2] + 4f * s,
                    strip[3] + 4f * s, 7f * s, FocusBattleTheme.fade(TimedTheme.PERFECT, flawless), Math.max(2f, 3f * s));
        }

        int count = state.comboSequence.size();
        for (int i = 0; i < count; i++) {
            if (i != state.comboIndex) paintCell(ui, canvas, strip, i, count, s, state);
        }
        // Current prompt last: it is enlarged and must overlap its neighbours, not the reverse.
        if (state.comboIndex >= 0 && state.comboIndex < count) {
            paintCell(ui, canvas, strip, state.comboIndex, count, s, state);
        }
        paintCaptions(ui, canvas, strip, s, state);

        if (flawless > 0f) {
            FocusBattleTheme.roundedFill(canvas, strip[0], strip[1], strip[2], strip[3], FocusBattleTheme.WINDOW_RADIUS,
                    FocusBattleTheme.fade(TimedTheme.PERFECT_HOT, 0.26f * flawless));
            paintFlawlessWord(ui, canvas, strip, s, state.flawlessAge);
        }
    }

    // ─────────────────────────────────────────────── Cells

    private static void paintCell(MasonryUI ui, Canvas canvas, float[] strip, int i, int count, float s,
                                  TimedInputState state) {
        float[] base = TimedLayout.comboCellRect(strip, i, count, s);
        TimedGrade grade = i < state.comboResults.length ? state.comboResults[i] : null;
        boolean current = grade == null && i == state.comboIndex;
        // Between prompts nothing is awaiting input; the one about to open is lit (not enlarged,
        // no timer) so the eye is already on it when it does.
        boolean upNext = grade == null && state.comboIndex < 0 && !state.comboFinished
                && i == state.comboNextIndex();
        ComboDirection dir = state.comboSequence.get(i);
        float pulse = 0.5f + 0.5f * (float) Math.sin(state.time * 9.0);

        float k = 1f;
        if (current) {
            k = TimedLayout.COMBO_CURRENT_SCALE + 0.04f * pulse;
        } else if (grade != null && i < state.comboStampAge.length && state.comboStampAge[i] >= 0f) {
            // The stamp lands big and settles.
            float t = FocusBattleTheme.clamp01(state.comboStampAge[i] / TimedInputState.COMBO_STAMP_SECONDS);
            k = 1f + 0.3f * (1f - TimedTheme.easeOutCubic(t));
        }
        float[] c = TimedLayout.scaled(base, k);
        float r = 5f * s;
        float border = Math.max(1.5f, 2f * s);

        int fill, edge, arrow, letter;
        if (grade == TimedGrade.PERFECT) {
            fill = TimedTheme.PERFECT; edge = TimedTheme.PERFECT_HOT; arrow = TimedTheme.CELL_STAMP_INK; letter = arrow;
        } else if (grade == TimedGrade.GOOD) {
            fill = 0xFFF2F6FA; edge = 0xFFFFFFFF; arrow = TimedTheme.CELL_STAMP_INK; letter = arrow;
        } else if (grade == TimedGrade.MISS) {
            fill = TimedTheme.MISS_DARK; edge = TimedTheme.MISS; arrow = TimedTheme.MISS; letter = TimedTheme.MISS;
        } else if (current) {
            fill = TimedTheme.CELL_FILL;
            edge = FocusBattleTheme.lerpColor(TimedTheme.CELL_BORDER, 0xFFFFFFFF, pulse);
            arrow = TimedTheme.CELL_ARROW; letter = FocusBattleTheme.TEXT_LABEL;
        } else if (upNext) {
            fill = TimedTheme.CELL_FILL; edge = TimedTheme.CELL_BORDER;
            arrow = 0xD9FFFFFF; letter = FocusBattleTheme.TEXT_LABEL;
        } else {
            fill = TimedTheme.CELL_FILL_DIM; edge = TimedTheme.CELL_BORDER_DIM;
            arrow = TimedTheme.CELL_ARROW_DIM; letter = TimedTheme.CELL_ARROW_DIM;
        }

        if (current) {
            FocusBattleTheme.roundedFill(canvas, c[0] - 3f * s, c[1] - 3f * s, c[2] + 6f * s, c[3] + 6f * s, r + 3f * s,
                    FocusBattleTheme.fade(TimedTheme.CELL_BORDER, 0.22f + 0.2f * pulse));
        }
        FocusBattleTheme.roundedFill(canvas, c[0], c[1], c[2], c[3], r, fill);
        FocusBattleTheme.roundedStroke(canvas, c[0], c[1], c[2], c[3], r, edge, current ? border * 1.4f : border);

        float glyph = c[2] * 0.56f;
        arrow(canvas, dir, c[0] + c[2] * 0.44f, c[1] + c[3] * 0.46f, glyph, arrow);
        if (grade == TimedGrade.MISS) crack(canvas, c, s);

        if (ui != null) {
            Font font = FocusBattleTheme.font(ui, TimedTheme.FS_KEY * k, s);
            if (font != null) {
                String key = keyFor(dir);
                float tw = MPainter.measureWidth(font, key);
                MPainter.drawString(canvas, key, c[0] + c[2] - tw - 4f * s, c[1] + c[3] - 4f * s, font, letter);
            }
        }

        if (current) {
            float[] track = TimedLayout.comboTimerTrack(base, s);
            float left = TimedLayout.comboTimeLeft(state.comboStepElapsed, state.comboStepDuration);
            float[] bar = TimedLayout.comboTimerFill(track, left);
            FocusBattleTheme.fillRect(canvas, track[0], track[1], track[2], track[3], FocusBattleTheme.BAR_TRACK);
            FocusBattleTheme.fillRect(canvas, bar[0], bar[1], bar[2], bar[3],
                    timerColor(left));
            MPainter.strokeRect(canvas, track[0] + 0.5f, track[1] + 0.5f, track[2] - 1f, track[3] - 1f,
                    FocusBattleTheme.BAR_OUTLINE, 1f);
        }
    }

    /** Calm cyan while there is time, through amber, to alarm red as the step runs out. */
    static int timerColor(float timeLeft) {
        float k = FocusBattleTheme.clamp01(timeLeft);
        return k >= 0.5f ? FocusBattleTheme.lerpColor(TimedTheme.TIMER_MID, TimedTheme.TIMER_FULL, (k - 0.5f) * 2f)
                : FocusBattleTheme.lerpColor(TimedTheme.TIMER_EMPTY, TimedTheme.TIMER_MID, k * 2f);
    }

    public static String keyFor(ComboDirection dir) {
        return switch (dir) {
            case UP -> "W";
            case LEFT -> "A";
            case DOWN -> "S";
            case RIGHT -> "D";
        };
    }

    /** Block arrow (head + shaft) centred on {@code (cx, cy)} inside a {@code size} box. */
    static void arrow(Canvas canvas, ComboDirection dir, float cx, float cy, float size, int color) {
        // Authored pointing up in a unit box, then turned in quarter steps.
        float[] unit = {0f, -0.5f, 0.48f, 0.02f, 0.18f, 0.02f, 0.18f, 0.5f, -0.18f, 0.5f, -0.18f, 0.02f, -0.48f, 0.02f};
        float[] xy = new float[unit.length];
        for (int i = 0; i < unit.length; i += 2) {
            float x = unit[i], y = unit[i + 1], rx, ry;
            switch (dir) {
                case RIGHT -> { rx = -y; ry = x; }
                case DOWN -> { rx = -x; ry = -y; }
                case LEFT -> { rx = y; ry = -x; }
                default -> { rx = x; ry = y; }
            }
            xy[i] = cx + rx * size;
            xy[i + 1] = cy + ry * size;
        }
        TimedTheme.fillPolygon(canvas, xy, color);
    }

    /** A fixed fracture across a missed cell (deterministic, like the cancelled cast bar's). */
    private static void crack(Canvas canvas, float[] c, float s) {
        float[] xy = {
                c[0] + c[2] * 0.62f, c[1],
                c[0] + c[2] * 0.42f, c[1] + c[3] * 0.34f,
                c[0] + c[2] * 0.60f, c[1] + c[3] * 0.52f,
                c[0] + c[2] * 0.36f, c[1] + c[3] * 0.78f,
                c[0] + c[2] * 0.44f, c[1] + c[3]};
        TimedTheme.haloPolyline(canvas, xy, Math.max(1f, 1.5f * s), 0xFFFFB0A8, 1f, s * 0.5f);
    }

    // ─────────────────────────────────────────────── Captions

    private static void paintCaptions(MasonryUI ui, Canvas canvas, float[] strip, float s, TimedInputState state) {
        if (ui == null) return;
        float[] label = TimedLayout.comboLabelRect(strip, s);
        Font small = FocusBattleTheme.font(ui, FocusBattleTheme.FS_LABEL, s);
        if (small != null) {
            float cy = label[1] + label[3] / 2f;
            FocusBattleTheme.textCentered(canvas, "FOCUS", label[0] + label[2] / 2f,
                    FocusBattleTheme.baseline(cy - small.getSize() * 0.62f, small.getSize()), small, FocusBattleTheme.FOCUS);
            FocusBattleTheme.textCentered(canvas, "COMBO", label[0] + label[2] / 2f,
                    FocusBattleTheme.baseline(cy + small.getSize() * 0.62f, small.getSize()), small, FocusBattleTheme.FOCUS);
        }
        float[] counter = TimedLayout.comboCounterRect(strip, s);
        int hits = state.comboLanded();
        Font big = FocusBattleTheme.font(ui, TimedTheme.FS_COUNTER, s);
        if (big != null && small != null) {
            float cx = counter[0] + counter[2] / 2f, cy = counter[1] + counter[3] / 2f;
            int color = hits > 0 ? FocusBattleTheme.TEXT : FocusBattleTheme.TEXT_DISABLED;
            FocusBattleTheme.textCentered(canvas, Integer.toString(hits), cx,
                    FocusBattleTheme.baseline(cy - small.getSize() * 0.55f, big.getSize()), big, color);
            FocusBattleTheme.textCentered(canvas, hits == 1 ? "HIT" : "HITS", cx,
                    FocusBattleTheme.baseline(cy + big.getSize() * 0.55f, small.getSize()), small,
                    FocusBattleTheme.TEXT_LABEL);
        }
    }

    /** Where the FLAWLESS word is centred; public so tests can look for it. */
    public static float[] flawlessWordRect(float[] strip, float s) {
        float h = TimedTheme.FS_BIG * s * 1.5f;
        return new float[]{strip[0], strip[1] - h - 6f * s, strip[2], h};
    }

    private static void paintFlawlessWord(MasonryUI ui, Canvas canvas, float[] strip, float s, float age) {
        if (ui == null) return;
        float t = FocusBattleTheme.clamp01(age / TimedInputState.FLAWLESS_SECONDS);
        float pop = 1f + 0.4f * (1f - TimedTheme.easeOutCubic(FocusBattleTheme.clamp01(t / 0.18f)));
        float fade = 1f - TimedTheme.smoothstep((t - 0.7f) / 0.3f);
        Font font = FocusBattleTheme.font(ui, TimedTheme.FS_BIG * pop, s);
        if (font == null) return;
        float[] rect = flawlessWordRect(strip, s);
        TimedTheme.outlinedTextCentered(canvas, "FLAWLESS!", rect[0] + rect[2] / 2f,
                FocusBattleTheme.baseline(rect[1] + rect[3] / 2f, font.getSize()), font, TimedTheme.PERFECT, fade, s);
    }
}
