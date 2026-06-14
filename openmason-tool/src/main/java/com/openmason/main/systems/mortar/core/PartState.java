package com.openmason.main.systems.mortar.core;

import com.openmason.main.systems.mortar.anim.Smoother;

/**
 * Per-part animated interaction state, owned by a {@link MortarRegion} and
 * keyed by part id. Each value is a {@link Smoother} blend in [0,1]:
 * <ul>
 *   <li>{@code hover} — 1 while the mouse is over the part's rect</li>
 *   <li>{@code press} — 1 while the part is held down</li>
 *   <li>{@code selected} — 1 while the part is the selected one</li>
 * </ul>
 *
 * <p>The region reads these at paint time (driving subtle one-off motion),
 * retargets them after hit-testing the previous frame's image, and advances
 * them in {@link #update(float)}. This is the feedback channel that lets a
 * pure {@link MortarPart} animate without holding state itself.</p>
 */
public final class PartState {

    private final Smoother hover = new Smoother(18.0f);
    private final Smoother press = new Smoother(26.0f);
    private final Smoother selected = new Smoother(14.0f);

    /** Frame-liveness marker so the region can evict states for parts no longer drawn. */
    boolean touchedThisFrame;

    public float hover() {
        return hover.getValue();
    }

    public float press() {
        return press.getValue();
    }

    public float selected() {
        return selected.getValue();
    }

    void setTargets(boolean hovered, boolean pressed, boolean isSelected) {
        hover.setTarget(hovered ? 1.0f : 0.0f);
        press.setTarget(pressed ? 1.0f : 0.0f);
        selected.setTarget(isSelected ? 1.0f : 0.0f);
    }

    void update(float dt) {
        hover.update(dt);
        press.update(dt);
        selected.update(dt);
    }
}
