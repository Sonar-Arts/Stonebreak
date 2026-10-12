package com.openmason.engine.ui.runtime.anim;

import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiValue;

/**
 * A running style transition (#295) on the lower layer of one channel: eases the cascade's
 * change from the value shown before it to the new base value, on the UI clock. The base value
 * already holds {@code to}, so when the transition ends the layer simply clears.
 */
final class Transition {

    final Channel channel;
    final UiValue from;
    final UiValue to;
    final double start;
    final double delay;
    final double duration;
    final UiStyleSheet.StyleTransition timing;

    Transition(Channel channel, UiValue from, UiValue to, double start, double delay, double duration,
               UiStyleSheet.StyleTransition timing) {
        this.channel = channel;
        this.from = from;
        this.to = to;
        this.start = start;
        this.delay = delay;
        this.duration = duration;
        this.timing = timing;
    }

    /** Value at UI time {@code now}; {@code done[0]} is set once it reached {@code to}. */
    UiValue sample(double now, boolean[] done) {
        double t = now - start - delay;
        if (t < 0) {
            done[0] = false;
            return from;
        }
        double u = duration <= 0 ? 1 : Math.min(1, t / duration);
        done[0] = u >= 1;
        return channel.property.mix(from, to, timing.ease((float) u));
    }
}
