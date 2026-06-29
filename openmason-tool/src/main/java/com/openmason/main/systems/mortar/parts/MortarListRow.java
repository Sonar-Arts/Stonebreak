package com.openmason.main.systems.mortar.parts;

import com.openmason.main.systems.mortar.core.MortarPart;
import com.openmason.main.systems.mortar.core.PartState;
import com.openmason.main.systems.mortar.paint.MortarPainter;
import com.openmason.main.systems.mortar.theme.MortarTheme;
import com.openmason.main.systems.mortar.theme.Argb;
import com.openmason.main.systems.skija.SkijaFontStore.Weight;

/**
 * A compact, informative list row: a primary title with a dimmed subtitle line
 * beneath it on the left, and a faint trailing label (e.g. relative time) pinned
 * to the right. Sibling to {@link MortarCard} for the same data shown in a dense
 * single-column list instead of a grid of tiles. Restrained styling mirrors the
 * card — a rounded surface, a hairline border, a soft hover fill, and on
 * selection a tinted border plus a small accent bar at the leading edge. Both
 * text lines are ellipsized so a row never bleeds into its neighbour and the
 * trailing label always stays visible.
 */
public final class MortarListRow implements MortarPart {

    private static final float RADIUS = 6f;
    private static final float PAD = 14f;
    private static final float TITLE_SIZE = 14f;
    private static final float SUB_SIZE = 12f;
    private static final float TRAIL_SIZE = 12f;
    private static final float TRAIL_GAP = 16f;

    private final String title;
    private final String subtitle;
    private final String trailing;

    public MortarListRow(String title, String subtitle, String trailing) {
        this.title = title;
        this.subtitle = subtitle;
        this.trailing = trailing;
    }

    @Override
    public void paint(MortarPainter g, float x, float y, float w, float h, PartState state) {
        MortarTheme theme = g.theme();
        float hover = state.hover();
        float press = state.press();
        float sel = state.selected();

        int fill = Argb.lerp(theme.surface, theme.surfaceHover, hover);
        if (press > 0f) {
            fill = Argb.shade(fill, -0.05f * press);
        }
        g.fillRoundRect(x, y, w, h, RADIUS, fill);

        int border = Argb.lerp(theme.border, theme.borderStrong, sel);
        g.strokeRoundRect(x, y, w, h, RADIUS, 1f, border);

        g.pushClip(x, y, w, h, RADIUS);

        // A small accent bar at the leading edge marks the selected row.
        if (sel > 0.01f) {
            float barH = h - 16f;
            g.fillRoundRect(x + 4f, y + (h - barH) / 2f, 3f, barH, 1.5f,
                    Argb.withAlpha(theme.accent, sel));
        }

        float contentX = x + PAD;

        // Trailing label is measured first so the left text never overlaps it.
        float trailW = 0f;
        if (trailing != null && !trailing.isEmpty()) {
            trailW = g.measureWidth(trailing, Weight.REGULAR, TRAIL_SIZE);
            g.text(trailing, x + w - PAD, y + h / 2f, MortarPainter.Align.RIGHT,
                    Weight.REGULAR, TRAIL_SIZE, theme.textDim);
        }
        float textMaxW = w - PAD * 2f - (trailW > 0f ? trailW + TRAIL_GAP : 0f);

        if (subtitle != null && !subtitle.isEmpty()) {
            g.textEllipsized(title, contentX, y + h * 0.36f, textMaxW,
                    Weight.MEDIUM, TITLE_SIZE, theme.text);
            g.textEllipsized(subtitle, contentX, y + h * 0.68f, textMaxW,
                    Weight.REGULAR, SUB_SIZE, theme.textFaint);
        } else {
            g.textEllipsized(title, contentX, y + h / 2f, textMaxW,
                    Weight.MEDIUM, TITLE_SIZE, theme.text);
        }

        g.popClip();
    }
}
