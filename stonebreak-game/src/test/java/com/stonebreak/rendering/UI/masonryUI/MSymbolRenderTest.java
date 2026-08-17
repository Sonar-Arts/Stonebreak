package com.stonebreak.rendering.UI.masonryUI;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pixel contract for the {@link MSymbol} vector icon library: every symbol
 * must actually mark the box it was asked to fill, stay inside it (plus the
 * 1px shadow offset and AA bleed), render deterministically, and be visually
 * distinct from its mirror — an icon that paints nothing, leaks outside its
 * bounds, or is indistinguishable from its opposite is broken in a way no
 * geometry-free test can see.
 */
class MSymbolRenderTest {

    private static final int W = 80;
    private static final int H = 80;
    // The icon box, comfortably inside the canvas.
    private static final int BX = 16, BY = 16, BS = 48;
    // Inflation that absorbs the shadow offset and anti-aliasing bleed.
    private static final int MARGIN = 4;

    @Test
    void everySymbolPaintsInsideItsBoxAndNowhereElse() {
        for (MSymbol symbol : MSymbol.values()) {
            RasterUiFixture fx = new RasterUiFixture(W, H);
            symbol.drawWithShadow(fx.canvas, BX, BY, BS, BS,
                    MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);

            assertTrue(fx.countPainted(BX, BY, BX + BS, BY + BS) > 60,
                    symbol + " must visibly paint its box");
            assertEquals(0, fx.countPainted(0, 0, BX - MARGIN, H),
                    symbol + " must not paint left of its box");
            assertEquals(0, fx.countPainted(BX + BS + MARGIN, 0, W, H),
                    symbol + " must not paint right of its box");
            assertEquals(0, fx.countPainted(0, 0, W, BY - MARGIN),
                    symbol + " must not paint above its box");
            assertEquals(0, fx.countPainted(0, BY + BS + MARGIN, W, H),
                    symbol + " must not paint below its box");
        }
    }

    @Test
    void symbolRenderingIsDeterministic() {
        for (MSymbol symbol : MSymbol.values()) {
            RasterUiFixture a = new RasterUiFixture(W, H);
            RasterUiFixture b = new RasterUiFixture(W, H);
            symbol.draw(a.canvas, BX, BY, BS, BS, MStyle.TEXT_PRIMARY);
            symbol.draw(b.canvas, BX, BY, BS, BS, MStyle.TEXT_PRIMARY);
            assertEquals(0, a.diff(b, 0, 0, W, H),
                    symbol + " must render the same pixels every time");
        }
    }

    @Test
    void mirroredSymbolsAreDistinguishable() {
        RasterUiFixture left = new RasterUiFixture(W, H);
        RasterUiFixture right = new RasterUiFixture(W, H);
        MSymbol.CHEVRON_LEFT.draw(left.canvas, BX, BY, BS, BS, MStyle.TEXT_PRIMARY);
        MSymbol.CHEVRON_RIGHT.draw(right.canvas, BX, BY, BS, BS, MStyle.TEXT_PRIMARY);

        assertTrue(left.diff(right, BX, BY, BX + BS, BY + BS) > 50,
                "a left chevron that looks like a right chevron points nowhere");
    }

    @Test
    void aFullyTransparentColorDrawsNothing() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        for (MSymbol symbol : MSymbol.values()) {
            symbol.draw(fx.canvas, BX, BY, BS, BS, 0x00FFFFFF);
        }
        assertEquals(0, fx.countPainted(0, 0, W, H),
                "alpha-0 colors must be a no-op, like the other painters");
    }

    @Test
    void degenerateBoxesAreSafeNoOps() {
        RasterUiFixture fx = new RasterUiFixture(W, H);
        for (MSymbol symbol : MSymbol.values()) {
            symbol.draw(fx.canvas, BX, BY, 0f, 48f, MStyle.TEXT_PRIMARY);
            symbol.draw(fx.canvas, BX, BY, 48f, -1f, MStyle.TEXT_PRIMARY);
            symbol.draw(null, BX, BY, 48f, 48f, MStyle.TEXT_PRIMARY);
        }
        assertEquals(0, fx.countPainted(0, 0, W, H));
    }
}
