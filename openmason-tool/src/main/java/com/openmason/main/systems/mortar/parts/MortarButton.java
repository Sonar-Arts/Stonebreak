package com.openmason.main.systems.mortar.parts;

import com.openmason.main.systems.mortar.core.MortarPart;
import com.openmason.main.systems.mortar.core.PartState;
import com.openmason.main.systems.mortar.paint.MortarPainter;
import com.openmason.main.systems.mortar.theme.MortarTheme;
import com.openmason.main.systems.mortar.theme.Argb;
import com.openmason.main.systems.skija.SkijaFontStore.Weight;

/**
 * A text button in one of two weights:
 * <ul>
 *   <li>{@link Variant#PRIMARY} — accent fill, light label; the single
 *       high-emphasis action (e.g. "New Project"). Brightens on hover, settles
 *       darker on press.</li>
 *   <li>{@link Variant#SECONDARY} — flat surface with a hairline border.</li>
 * </ul>
 * Motion is a one-off hover brighten + a 1px press settle — no glow.
 */
public final class MortarButton implements MortarPart {

    public enum Variant {
        PRIMARY, SECONDARY
    }

    private static final float RADIUS = 6f;
    private static final float FONT_SIZE = 13f;
    private static final int PRIMARY_LABEL = 0xFFFFFFFF;

    private final String label;
    private final Variant variant;
    private final boolean enabled;

    public MortarButton(String label, Variant variant) {
        this(label, variant, true);
    }

    /** A disabled button paints washed out and ignores hover/press; the caller must drop its clicks. */
    public MortarButton(String label, Variant variant, boolean enabled) {
        this.label = label;
        this.variant = variant;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void paint(MortarPainter g, float x, float y, float w, float h, PartState state) {
        MortarTheme theme = g.theme();
        if (!enabled) {
            g.fillRoundRect(x, y, w, h, RADIUS, Argb.withAlpha(theme.surface, 0.5f));
            g.strokeRoundRect(x, y, w, h, RADIUS, 1f, Argb.withAlpha(theme.border, 0.5f));
            g.text(label, x + w / 2f, y + h / 2f, MortarPainter.Align.CENTER,
                    Weight.MEDIUM, FONT_SIZE, Argb.withAlpha(theme.text, 0.35f));
            return;
        }
        float hover = state.hover();
        float press = state.press();
        float inset = press; // 1px settle when held

        float bx = x + inset;
        float by = y + inset;
        float bw = w - inset * 2f;
        float bh = h - inset * 2f;

        if (variant == Variant.PRIMARY) {
            int fill = Argb.lerp(theme.accent, theme.accentHover, hover);
            fill = Argb.shade(fill, -0.10f * press);
            g.fillRoundRect(bx, by, bw, bh, RADIUS, fill);
            g.text(label, bx + bw / 2f, by + bh / 2f, MortarPainter.Align.CENTER,
                    Weight.MEDIUM, FONT_SIZE, PRIMARY_LABEL);
        } else {
            int fill = Argb.lerp(theme.surface, theme.surfaceHover, hover);
            fill = Argb.shade(fill, -0.05f * press);
            g.fillRoundRect(bx, by, bw, bh, RADIUS, fill);
            g.strokeRoundRect(bx, by, bw, bh, RADIUS, 1f, theme.border);
            g.text(label, bx + bw / 2f, by + bh / 2f, MortarPainter.Align.CENTER,
                    Weight.MEDIUM, FONT_SIZE, theme.text);
        }
    }
}
