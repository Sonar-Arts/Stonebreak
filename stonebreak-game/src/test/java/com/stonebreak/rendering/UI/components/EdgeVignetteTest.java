package com.stonebreak.rendering.UI.components;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The screen-edge cue fades toward its peak while active and away again when not (#321). */
class EdgeVignetteTest {

    @Test
    void fadesInToItsPeakAndOutToNothingAtItsOwnSpeeds() {
        EdgeVignette vignette = new EdgeVignette(0x000000, 0.6f, 0.22f, 2f, 4f);
        assertFalse(vignette.isVisible());

        vignette.update(true, 0.1f);
        assertEquals(0.2f, vignette.getCurrentOpacity(), 1e-5f);
        vignette.update(true, 1f);
        assertEquals(0.6f, vignette.getCurrentOpacity(), 1e-5f, "capped at the peak");

        vignette.update(false, 0.1f);
        assertEquals(0.2f, vignette.getCurrentOpacity(), 1e-5f, "fades out at its own (faster) speed");
        vignette.update(false, 1f);
        assertEquals(0f, vignette.getCurrentOpacity());
        assertFalse(vignette.isVisible());
    }
}
