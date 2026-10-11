package com.stonebreak.ui.focusBattle;

import com.openmason.engine.ui.rendering.MasonryBackend;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Typeface;

import java.util.function.Supplier;

/**
 * A {@link MasonryBackend} over a canvas someone else is already painting (a document's frame): the battle HUD's
 * Masonry painters draw through it from inside draw providers (#301). Frames are the document's, so
 * {@link #beginFrame}/{@link #endFrame} do nothing; the typeface is the game's.
 */
public final class BattleCanvasBackend extends MasonryBackend {

    private final Supplier<Typeface> typeface;
    private Canvas canvas;

    public BattleCanvasBackend(Supplier<Typeface> typeface) {
        this.typeface = typeface;
    }

    /** The canvas the next painter draws on (the provider's). */
    public void canvas(Canvas canvas) {
        this.canvas = canvas;
    }

    @Override public Canvas getCanvas() { return canvas; }
    @Override public boolean isAvailable() { return canvas != null; }
    @Override public Typeface typeface() { return typeface == null ? null : typeface.get(); }
    @Override public void beginFrame(int width, int height, float pixelRatio) { }
    @Override public void endFrame() { }
    @Override public void dispose() { }
}
