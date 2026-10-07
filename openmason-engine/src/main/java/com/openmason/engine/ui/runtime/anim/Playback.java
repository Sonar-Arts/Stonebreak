package com.openmason.engine.ui.runtime.anim;

import com.openmason.engine.format.omui.UiEasing;
import com.openmason.engine.format.omui.UiValue;

import java.util.ArrayList;
import java.util.List;

/**
 * An explicit animation (clip or tween) on the upper layer of its channels (#295). Its time is
 * {@code elapsed = baseElapsed + (clock − since) × speed}, read from one {@link UiClocks} clock, so
 * sampling is a pure function of that clock's reading; speed changes and seeks rebase without a
 * jump.
 */
abstract class Playback {

    /** One claimed channel. {@code lost} once a newer animation took it over. */
    static class Slot {
        final Channel channel;
        UiValue blendFrom;
        boolean lost;

        Slot(Channel channel) {
            this.channel = channel;
        }
    }

    final long token;
    final Object owner;
    final UiAnimator.Listener listener;
    final String clock;
    final UiAnimator.Fill fill;
    final double blend;
    final List<Slot> slots = new ArrayList<>();
    double baseElapsed;
    double since;
    double speed = 1;
    /** Set when the reduced-motion rule makes this animation end on its first sample. */
    boolean jumpToEnd;
    /** Removed from the animator (finished, stopped, interrupted or cleared). */
    boolean ended;

    Playback(long token, Object owner, UiAnimator.Listener listener, String clock, UiAnimator.Fill fill, double blend) {
        this.token = token;
        this.owner = owner;
        this.listener = listener;
        this.clock = clock;
        this.fill = fill;
        this.blend = Math.max(0, blend);
    }

    double elapsed(double now) {
        return baseElapsed + (now - since) * speed;
    }

    void rebase(double now, double elapsed, double newSpeed) {
        baseElapsed = elapsed;
        since = now;
        speed = newSpeed;
    }

    boolean alive() {
        for (Slot s : slots) {
            if (!s.lost) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes every live slot at {@code elapsed} seconds.
     *
     * @return true when the animation reached its end
     */
    abstract boolean sample(double elapsed, UiAnimator animator);

    /** Writes the final values (stop at end). */
    abstract void sampleEnd(UiAnimator animator);

    /** Total length in this animation's own time, or infinity for loops. */
    abstract double length();

    /** Writes {@code value} to a live slot, cross-faded from what was shown during the blend-in. */
    void write(Slot slot, UiValue value, double elapsed, UiAnimator animator) {
        if (slot.lost || slot.channel.owner != this) {
            return;
        }
        if (blend > 0 && elapsed < blend && !jumpToEnd) {
            float f = UiEasing.EASE_IN_OUT.curve().apply((float) Math.max(0, elapsed / blend));
            value = slot.channel.property.mix(slot.blendFrom, value, f);
        }
        animator.writeExplicit(slot.channel, value);
    }
}
