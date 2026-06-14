package com.openmason.main.systems.mortar.parts;

import com.openmason.main.systems.mortar.core.MortarPart;
import com.openmason.main.systems.mortar.core.PartState;
import com.openmason.main.systems.mortar.paint.MortarPainter;
import com.openmason.main.systems.mortar.theme.MortarTheme;
import com.openmason.main.systems.mortar.theme.Argb;
import com.openmason.main.systems.skija.SkijaFontStore.Weight;

/**
 * A flat content card: a wrapped title, a simple wrapped description, and a
 * faint footer meta line. Nothing is ellipsized — the title wraps to two lines
 * and the description shows a "simple" couple of lines (the full, expanded
 * description lives in the contextual preview). Restrained styling: a crisp
 * rounded surface, a hairline border, a subtle one-off lift + soft shadow on
 * hover, and an accent-tinted border when selected. Content is clipped to the
 * card so nothing bleeds into neighbours.
 */
public final class MortarCard implements MortarPart {

    private static final float RADIUS = 8f;
    private static final float PAD = 14f;
    private static final float TITLE_SIZE = 14f;
    private static final float DESC_SIZE = 12f;
    private static final float FOOTER_SIZE = 11f;

    private final String title;
    private final String description;
    private final String footer;

    public MortarCard(String title, String description, String footer) {
        this.title = title;
        this.description = description;
        this.footer = footer;
    }

    @Override
    public void paint(MortarPainter g, float x, float y, float w, float h, PartState state) {
        MortarTheme theme = g.theme();
        float hover = state.hover();
        float press = state.press();
        float sel = state.selected();

        // Subtle one-off lift on hover, settling slightly when pressed.
        float lift = 2.0f * hover - 1.5f * press;
        float cardY = y - lift;

        if (hover > 0.01f) {
            g.dropShadow(x + 2, cardY, w - 4, h, RADIUS,
                    3f, 5f + 6f * hover, Argb.withAlpha(theme.shadow, 0.28f * hover));
        }

        int fill = Argb.lerp(theme.surface, theme.surfaceHover, hover);
        if (press > 0f) {
            fill = Argb.shade(fill, -0.05f * press);
        }
        g.fillRoundRect(x, cardY, w, h, RADIUS, fill);

        int border = Argb.lerp(theme.border, theme.borderStrong, sel);
        g.strokeRoundRect(x, cardY, w, h, RADIUS, 1f, border);

        // Clip content so long footers/descriptions never bleed past the card.
        g.pushClip(x, cardY, w, h, RADIUS);
        float contentX = x + PAD;
        float contentW = w - PAD * 2f;

        float ty = cardY + PAD;
        ty += g.textWrapped(title, contentX, ty, contentW, Weight.MEDIUM, TITLE_SIZE, theme.text, 2);

        if (description != null && !description.isEmpty()) {
            g.textWrapped(description, contentX, ty + 4f, contentW,
                    Weight.REGULAR, DESC_SIZE, theme.textDim, 2);
        }

        if (footer != null && !footer.isEmpty()) {
            g.text(footer, contentX, cardY + h - PAD - 2f, MortarPainter.Align.LEFT,
                    Weight.REGULAR, FOOTER_SIZE, theme.textFaint);
        }
        g.popClip();
    }
}
