package com.stonebreak.rendering.UI.masonryUI;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The slider's whole interaction contract, headless: its position is the track CENTER (unlike
 * every other widget), its hit band is three track-heights tall so near-misses still grab it,
 * a grab jumps the value to the click point, drags clamp at the rails, and the change callback
 * fires once per real change — settings write straight through it, so a spurious fire is a
 * spurious config write.
 */
class MSliderInteractionTest {

    /** Track center at (100, 50), 200 wide: rail spans x ∈ [0, 200]. */
    private MSlider slider() {
        return new MSlider("Volume", 0f, 100f, 50f).position(100, 50).size(200, 20);
    }

    @Test
    void theHitBandIsThreeTrackHeightsTallAroundTheCenter() {
        MSlider slider = slider();

        assertTrue(slider.contains(100, 50), "on the track");
        assertTrue(slider.contains(100, 25), "well above it — the expanded grab band");
        assertTrue(slider.contains(100, 75), "and below");
        assertFalse(slider.contains(100, 15), "but not arbitrarily far");
        assertFalse(slider.contains(-5, 50), "the band does not widen the rails");
    }

    @Test
    void aGrabJumpsTheValueToTheClickPoint() {
        MSlider slider = slider();

        assertTrue(slider.handleClick(150, 50));
        assertTrue(slider.isDragging());
        assertEquals(75f, slider.value(), 1e-4f, "three quarters along the rail");
    }

    @Test
    void aMissNeitherGrabsNorMoves() {
        MSlider slider = slider();

        assertFalse(slider.handleClick(300, 50));
        assertFalse(slider.isDragging());
        assertEquals(50f, slider.value(), 1e-4f);
    }

    @Test
    void dragsTrackTheMouseAndClampAtTheRails() {
        MSlider slider = slider();
        slider.handleClick(100, 50);

        slider.handleDrag(0);
        assertEquals(0f, slider.value(), 1e-4f);
        slider.handleDrag(500); // way past the right rail
        assertEquals(100f, slider.value(), 1e-4f, "the knob never leaves the track");
        slider.handleDrag(-500);
        assertEquals(0f, slider.value(), 1e-4f);
    }

    @Test
    void aReleasedSliderIgnoresTheMouse() {
        MSlider slider = slider();
        slider.handleClick(100, 50);
        slider.stopDragging();

        slider.handleDrag(200);

        assertEquals(50f, slider.value(), 1e-4f, "drag after release must not move the value");
    }

    @Test
    void theCallbackFiresOncePerRealChangeOnly() {
        List<Float> changes = new ArrayList<>();
        MSlider slider = slider();
        slider.onChange(changes::add);

        slider.setValue(75f);
        slider.setValue(75f);     // no-op — settings must not be rewritten
        slider.setValue(200f);    // clamps to 100
        slider.setValue(150f);    // clamps to 100 again — still no change

        assertEquals(List.of(75f, 100f), changes);
    }

    @Test
    void keyboardNudgesClampLikeDrags() {
        MSlider slider = slider();

        slider.adjustValue(60f);
        assertEquals(100f, slider.value(), 1e-4f);
        slider.adjustValue(-250f);
        assertEquals(0f, slider.value(), 1e-4f);
    }

    @Test
    void narrowingTheRangeReClampsTheValue() {
        MSlider slider = slider();

        slider.setRange(0f, 30f);

        assertEquals(30f, slider.value(), 1e-4f);
        assertEquals(1f, slider.normalized(), 1e-4f);
    }

    @Test
    void normalizedIsSafeOnADegenerateRange() {
        MSlider slider = new MSlider("x", 5f, 5f, 5f);

        assertEquals(0f, slider.normalized(), 1e-4f, "a zero-width range must not divide by zero");
    }
}
