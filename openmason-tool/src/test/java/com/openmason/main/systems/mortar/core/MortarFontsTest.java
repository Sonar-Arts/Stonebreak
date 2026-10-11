package com.openmason.main.systems.mortar.core;

import com.openmason.main.systems.skija.SkijaFontStore.Weight;
import io.github.humbleui.skija.Font;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-(weight, size) Font cache paint relies on to avoid a native Font per frame. */
class MortarFontsTest {

    @Test
    void sizesAreBucketedToHalfPixels() {
        try (MortarFonts fonts = new MortarFonts()) {
            Font f13 = fonts.get(Weight.REGULAR, 13.0f);
            assertSame(f13, fonts.get(Weight.REGULAR, 13.2f), "13.2 rounds to the 13.0 bucket");
            assertSame(f13, fonts.get(Weight.REGULAR, 12.8f));
            Font f135 = fonts.get(Weight.REGULAR, 13.3f);
            assertNotSame(f13, f135);
            assertEquals(13.5f, f135.getSize());
            assertEquals(13.0f, f13.getSize());
        }
    }

    @Test
    void weightsAreCachedSeparately() {
        try (MortarFonts fonts = new MortarFonts()) {
            Font regular = fonts.get(Weight.REGULAR, 12f);
            Font bold = fonts.get(Weight.BOLD, 12f);
            assertNotSame(regular, bold);
            assertSame(bold, fonts.get(Weight.BOLD, 12f));
        }
    }

    @Test
    void closeReleasesCachedFonts() {
        MortarFonts fonts = new MortarFonts();
        Font before = fonts.get(Weight.MEDIUM, 14f);
        fonts.close();
        assertTrue(before.isClosed(), "close frees the native font");
        Font after = fonts.get(Weight.MEDIUM, 14f);
        assertNotSame(before, after, "the cache starts over after close");
        fonts.close();
    }
}
