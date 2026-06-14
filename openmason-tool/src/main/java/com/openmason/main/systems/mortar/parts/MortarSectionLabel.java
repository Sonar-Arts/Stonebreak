package com.openmason.main.systems.mortar.parts;

import com.openmason.main.systems.mortar.core.MortarPart;
import com.openmason.main.systems.mortar.core.PartState;
import com.openmason.main.systems.mortar.paint.MortarPainter;
import com.openmason.main.systems.mortar.theme.MortarTheme;
import com.openmason.main.systems.skija.SkijaFontStore.Weight;

/**
 * A dimmed, upper-cased section heading ("RECENT PROJECTS", "TEMPLATES") with a
 * hairline trailing rule that fills the remaining width — establishes
 * typographic hierarchy without heavy chrome.
 */
public final class MortarSectionLabel implements MortarPart {

    private static final float FONT_SIZE = 11f;
    private static final float GAP = 10f;

    private final String label;

    public MortarSectionLabel(String label) {
        this.label = label == null ? "" : label.toUpperCase();
    }

    @Override
    public void paint(MortarPainter g, float x, float y, float w, float h, PartState state) {
        MortarTheme theme = g.theme();
        float cy = y + h / 2f;
        g.text(label, x, cy, MortarPainter.Align.LEFT, Weight.BOLD, FONT_SIZE, theme.textDim);

        float labelW = g.measureWidth(label, Weight.BOLD, FONT_SIZE);
        float lineX = x + labelW + GAP;
        float lineW = (x + w) - lineX;
        if (lineW > 0f) {
            g.fillRect(lineX, cy - 0.5f, lineW, 1f, theme.separator);
        }
    }
}
