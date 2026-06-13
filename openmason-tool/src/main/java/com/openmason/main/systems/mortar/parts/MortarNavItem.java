package com.openmason.main.systems.mortar.parts;

import com.openmason.main.systems.mortar.core.MortarPart;
import com.openmason.main.systems.mortar.core.PartState;
import com.openmason.main.systems.mortar.paint.MortarPainter;
import com.openmason.main.systems.mortar.theme.MortarTheme;
import com.openmason.main.systems.mortar.theme.Argb;
import com.openmason.main.systems.skija.SkijaFontStore.Weight;

/**
 * A selectable sidebar row: a label with a left accent bar that grows in when
 * the row is selected or hovered. The accent bar is MortarUI's one signature
 * flourish; everything else stays flat. Background tints faintly on hover.
 */
public final class MortarNavItem implements MortarPart {

    private static final float RADIUS = 6f;
    private static final float FONT_SIZE = 14f;
    private static final float BAR_WIDTH = 3f;
    private static final float TEXT_INSET = 16f;

    private final String label;

    public MortarNavItem(String label) {
        this.label = label;
    }

    @Override
    public void paint(MortarPainter g, float x, float y, float w, float h, PartState state) {
        MortarTheme theme = g.theme();
        float hover = state.hover();
        float press = state.press();
        float sel = state.selected();
        float active = Math.max(hover * 0.65f, sel);

        if (active > 0.01f) {
            int bg = Argb.withAlpha(theme.surfaceHover, (0.55f * hover + 0.5f * sel) - 0.1f * press);
            g.fillRoundRect(x, y, w, h, RADIUS, bg);
        }

        // Accent bar height eases in with selection/hover.
        float barH = (h - 14f) * active;
        if (barH > 0.5f) {
            int barColor = Argb.withAlpha(theme.accent, Math.min(1f, 0.5f + 0.5f * sel));
            g.fillRoundRect(x + 4f, y + (h - barH) / 2f, BAR_WIDTH, barH, BAR_WIDTH / 2f, barColor);
        }

        int textColor = Argb.lerp(theme.textDim, theme.text, active);
        g.text(label, x + TEXT_INSET, y + h / 2f, MortarPainter.Align.LEFT,
                sel > 0.5f ? Weight.MEDIUM : Weight.REGULAR, FONT_SIZE, textColor);
    }
}
