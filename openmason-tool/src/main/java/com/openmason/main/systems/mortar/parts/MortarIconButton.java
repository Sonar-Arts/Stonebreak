package com.openmason.main.systems.mortar.parts;

import com.openmason.main.systems.mortar.core.MortarPart;
import com.openmason.main.systems.mortar.core.PartState;
import com.openmason.main.systems.mortar.paint.MortarPainter;
import com.openmason.main.systems.mortar.theme.MortarTheme;
import com.openmason.main.systems.mortar.theme.Argb;
import com.openmason.main.systems.skija.SkijaFontStore.Weight;

/**
 * A compact square button rendering a short centered glyph string (e.g. "+").
 * Visually a flat surface that tints on hover; the glyph brightens from dim to
 * full as the button is hovered. For multi-character labels prefer
 * {@link MortarButton}.
 */
public final class MortarIconButton implements MortarPart {

    private static final float RADIUS = 6f;
    private static final float GLYPH_SIZE = 16f;

    private final String glyph;

    public MortarIconButton(String glyph) {
        this.glyph = glyph;
    }

    @Override
    public void paint(MortarPainter g, float x, float y, float w, float h, PartState state) {
        MortarTheme theme = g.theme();
        float hover = state.hover();
        float press = state.press();

        int fill = Argb.withAlpha(theme.surfaceHover, 0.4f + 0.5f * hover - 0.1f * press);
        g.fillRoundRect(x, y, w, h, RADIUS, fill);
        g.strokeRoundRect(x, y, w, h, RADIUS, 1f, theme.border);

        int glyphColor = Argb.lerp(theme.textDim, theme.text, hover);
        g.text(glyph, x + w / 2f, y + h / 2f, MortarPainter.Align.CENTER,
                Weight.BOLD, GLYPH_SIZE, glyphColor);
    }
}
