package com.openmason.main.systems.mortar.parts;

import com.openmason.main.systems.mortar.core.MortarPart;
import com.openmason.main.systems.mortar.core.PartState;
import com.openmason.main.systems.mortar.paint.MortarPainter;
import com.openmason.main.systems.mortar.theme.MortarTheme;

/**
 * A hairline horizontal rule, vertically centered in its rect — the flat
 * replacement for the old gradient-fade separators. With {@code accentTick}
 * set, a short accent segment leads the line for a subtle bit of structure.
 */
public final class MortarSeparator implements MortarPart {

    private static final float TICK_WIDTH = 16f;

    private final boolean accentTick;

    public MortarSeparator(boolean accentTick) {
        this.accentTick = accentTick;
    }

    @Override
    public void paint(MortarPainter g, float x, float y, float w, float h, PartState state) {
        MortarTheme theme = g.theme();
        float cy = y + h / 2f;
        g.fillRect(x, cy - 0.5f, w, 1f, theme.separator);
        if (accentTick) {
            g.fillRect(x, cy - 0.5f, Math.min(TICK_WIDTH, w), 1f, theme.accent);
        }
    }
}
