package com.openmason.engine.ui.runtime.anim;

import com.openmason.engine.format.omui.UiBezier;
import com.openmason.engine.format.omui.UiEasing;
import com.openmason.engine.format.omui.UiValue;

/**
 * A script tween ({@code ui.tween}): each property eases from the value shown when it started
 * (or an explicit {@code from}) to its target. Under reduced motion every slot has its own
 * shortened duration (see {@link UiAnimator#REDUCED_FADE_SECONDS}).
 */
final class TweenPlayback extends Playback {

    static final class TweenSlot extends Slot {
        final UiValue from;
        final UiValue to;
        final double delay;
        final double duration;

        TweenSlot(Channel channel, UiValue from, UiValue to, double delay, double duration) {
            super(channel);
            this.from = from;
            this.to = to;
            this.delay = delay;
            this.duration = duration;
        }
    }

    final UiEasing easing;
    final UiBezier bezier;

    TweenPlayback(long token, Object owner, UiAnimator.Listener listener, String clock, UiAnimator.Fill fill,
                  UiEasing easing, UiBezier bezier) {
        super(token, owner, listener, clock, fill, 0);
        this.easing = easing == null ? UiEasing.LINEAR : easing;
        this.bezier = bezier;
    }

    @Override
    boolean sample(double elapsed, UiAnimator animator) {
        boolean done = true;
        for (Slot s : slots) {
            TweenSlot t = (TweenSlot) s;
            double local = elapsed - t.delay;
            double u = local < 0 ? 0 : t.duration <= 0 ? 1 : Math.min(1, local / t.duration);
            done &= u >= 1 || t.lost;
            write(t, t.channel.property.mix(t.from, t.to, UiBezier.ease(easing, bezier, (float) u)), elapsed, animator);
        }
        return done;
    }

    @Override
    void sampleEnd(UiAnimator animator) {
        for (Slot s : slots) {
            write(s, ((TweenSlot) s).to, Double.MAX_VALUE, animator);
        }
    }

    @Override
    double length() {
        double max = 0;
        for (Slot s : slots) {
            TweenSlot t = (TweenSlot) s;
            max = Math.max(max, t.delay + t.duration);
        }
        return max;
    }
}
