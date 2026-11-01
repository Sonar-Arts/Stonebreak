package com.openmason.engine.ui.theme;

import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.masonry.MStyle;
import com.openmason.engine.ui.masonry.MSymbol;

import java.util.Locale;

/**
 * The engine's own theme entries (#384): Masonry's default look, named generically. Surfaces
 * {@code panel}, {@code button} (+ {@code button-hover}, {@code button-disabled}: a button
 * that does not follow its pseudo-states) and {@code inset}; every {@link MSymbol} under its
 * lower-case hyphenated name. {@code auto} and {@code none} are keywords, not entries.
 */
final class NeutralTheme {

    private NeutralTheme() {
    }

    static UiTheme build() {
        UiTheme.Builder b = UiTheme.builder()
            .surface("panel", (c, r, s) -> MPainter.panel(c, r.x(), r.y(), r.width(), r.height()))
            .surface("inset", (c, r, s) -> MPainter.inset(c, r.x(), r.y(), r.width(), r.height()))
            .surface("button", (c, r, s) -> button(c, r, MStyle.BUTTON_FILL))
            .surface("button-hover", (c, r, s) -> button(c, r, MStyle.BUTTON_FILL_HI))
            .surface("button-disabled", (c, r, s) -> button(c, r, MStyle.BUTTON_FILL_DIS));
        for (MSymbol sym : MSymbol.values()) {
            b.symbol(sym.name().toLowerCase(Locale.ROOT).replace('_', '-'), new SymbolPainter() {
                @Override
                public void draw(io.github.humbleui.skija.Canvas canvas, float x, float y, float w, float h,
                                 int color) {
                    sym.draw(canvas, x, y, w, h, color);
                }

                @Override
                public void drawWithShadow(io.github.humbleui.skija.Canvas canvas, float x, float y, float w,
                                           float h, int color, int shadow) {
                    sym.drawWithShadow(canvas, x, y, w, h, color, shadow);
                }
            });
        }
        return b.build();
    }

    /** The stone button at its device-px radius, as the legacy painters drew it. */
    private static void button(io.github.humbleui.skija.Canvas canvas,
                               com.openmason.engine.ui.runtime.UiRect r, int fill) {
        MPainter.stoneSurface(canvas, r.x(), r.y(), r.width(), r.height(), MStyle.BUTTON_RADIUS,
            fill, MStyle.BUTTON_BORDER, MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW,
            MStyle.BUTTON_DROP_SHADOW, MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
    }
}
