package com.openmason.main.systems.mortar.core;

import com.openmason.main.systems.mortar.paint.MortarPainter;

/**
 * A reusable, paint-only visual element (card, nav row, button, badge, ...).
 * A part draws itself into the region's Skija surface at the rect the region
 * assigns it, blending its appearance by the animated {@link PartState}. It
 * holds only <em>content</em> (labels, badge text) — never animation or
 * interaction state — so it is a cheap, stateless flyweight that the region
 * hit-tests and animates on its behalf.
 */
@FunctionalInterface
public interface MortarPart {

    /**
     * Paint this part at ({@code x},{@code y}) with size {@code w}×{@code h} in
     * the surface's logical pixel space, using {@code state} (animated
     * hover/press/selected in [0,1]) to drive subtle one-off motion.
     */
    void paint(MortarPainter g, float x, float y, float w, float h, PartState state);
}
