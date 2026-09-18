package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.StatusView;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.FocusBattleTheme;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

import java.util.ArrayList;
import java.util.List;

/**
 * Status chips shared by the party window (E3) and the enemy plate (E5): a dark pill with a
 * coloured border, the status label and, for timed statuses, the whole seconds remaining.
 */
public final class StatusChips {

    /** One chip to draw. {@code timer} is empty for statuses that last until consumed. */
    public record Chip(String label, String timer, int color) {}

    private StatusChips() {}

    /** Chips for a combatant's statuses, in model order. */
    public static List<Chip> of(List<StatusView> statuses) {
        List<Chip> chips = new ArrayList<>();
        if (statuses == null) return chips;
        for (StatusView s : statuses) chips.add(of(s));
        return chips;
    }

    public static Chip of(StatusView s) {
        String label = s.status().label();
        if (s.stacks() > 1) label = label + " x" + s.stacks();
        // Ceil so a chip never reads "0s" while the status is still active.
        String timer = s.timed() ? (int) Math.ceil(s.remainingSeconds()) + "s" : "";
        return new Chip(label, timer, colorFor(s.status()));
    }

    /** The queued Martial Surge chip shown while bonus hits are waiting. */
    public static Chip surge(int queuedHits) {
        // No hit count: a queued surge adds a different number of blows to a Strike than to a Flurry.
        return new Chip(BattleStatus.SURGE.label() + " ready", "", FocusBattleTheme.FOCUS);
    }

    public static int colorFor(BattleStatus status) {
        return switch (status) {
            case CHILLED -> FocusBattleTheme.FROST;
            case SURGE -> FocusBattleTheme.FOCUS;
            default -> status.beneficial() ? FocusBattleTheme.CHIP_GOOD : FocusBattleTheme.CHIP_BAD;
        };
    }

    /**
     * Lays the chips out right-to-left from the right edge of {@code area} and stops before one
     * would cross {@code minX} (the text they share the line with).
     *
     * @return the x of the leftmost chip drawn, or the area's right edge when none fit
     */
    public static float paintRightAligned(MasonryUI ui, Canvas canvas, float[] area, float minX,
                                          List<Chip> chips, float uiScale) {
        float right = area[0] + area[2];
        if (canvas == null || chips == null || chips.isEmpty()) return right;
        Font font = FocusBattleTheme.font(ui, FocusBattleTheme.FS_CHIP, uiScale);
        if (font == null) return right;
        float h = Math.min(area[3], 20f * uiScale);
        float y = area[1] + (area[3] - h) / 2f;
        float gap = 5f * uiScale;
        float x = right;
        for (Chip chip : chips) {
            float w = width(font, chip, h, uiScale);
            if (x - w < minX) break;
            x -= w;
            paintChip(canvas, font, chip, x, y, w, h, uiScale);
            x -= gap;
        }
        return Math.min(right, x + gap);
    }

    private static float width(Font font, Chip chip, float h, float uiScale) {
        float w = h * 0.7f + MPainter.measureWidth(font, chip.label());
        if (!chip.timer().isEmpty()) w += 5f * uiScale + MPainter.measureWidth(font, chip.timer());
        return (float) Math.ceil(w);
    }

    private static void paintChip(Canvas canvas, Font font, Chip chip, float x, float y, float w, float h,
                                  float uiScale) {
        float r = h / 2f;
        FocusBattleTheme.roundedFill(canvas, x, y, w, h, r, FocusBattleTheme.CHIP_FILL);
        FocusBattleTheme.roundedStroke(canvas, x, y, w, h, r, chip.color(), Math.max(1f, uiScale));
        float baseline = FocusBattleTheme.baseline(y + h / 2f, font.getSize());
        float tx = x + h * 0.35f;
        FocusBattleTheme.text(canvas, chip.label(), tx, baseline, font, chip.color());
        if (!chip.timer().isEmpty()) {
            tx += MPainter.measureWidth(font, chip.label()) + 5f * uiScale;
            FocusBattleTheme.text(canvas, chip.timer(), tx, baseline, font, FocusBattleTheme.TEXT);
        }
    }
}
