package com.openmason.engine.ui.runtime.anim;

import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiAnimationClip.AnimEvent;
import com.openmason.engine.format.omui.UiAnimationClip.AnimKey;
import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiValue;

import java.util.List;

/**
 * A timeline clip ({@code ui.play}, a state machine, the editor's scrubber). The clip-local time
 * of an elapsed time follows the loop mode; tracks sample keys at that time and events fire when
 * playback crosses them. Sampling never waits on a callback: events and completion are reported
 * to the listener, which queues them for scripts.
 */
final class ClipPlayback extends Playback {

    static final class TrackSlot extends Slot {
        final List<AnimKey> keys;

        TrackSlot(Channel channel, List<AnimKey> keys) {
            super(channel);
            this.keys = keys;
        }
    }

    final UiAnimationClip clip;
    final LoopMode loop;
    /** Clip-local time of the previous sample; -1 before the first (events at 0 then fire). */
    double lastLocal = -1;

    ClipPlayback(long token, Object owner, UiAnimator.Listener listener, String clock, UiAnimator.Fill fill,
                 double blend, UiAnimationClip clip, LoopMode loop) {
        super(token, owner, listener, clock, fill, blend);
        this.clip = clip;
        this.loop = loop == null ? clip.loop() : loop;
    }

    /** Clip-local time at {@code elapsed}: clamped (once), wrapped (loop) or reflected (ping-pong). */
    double local(double elapsed) {
        double d = clip.duration();
        if (jumpToEnd) {
            return loop == LoopMode.ONCE ? d : 0;
        }
        if (d <= 0) {
            return 0;
        }
        double e = Math.max(0, elapsed);
        return switch (loop) {
            case ONCE -> Math.min(e, d);
            case LOOP -> e % d;
            case PING_PONG -> {
                double m = e % (2 * d);
                yield m <= d ? m : 2 * d - m;
            }
        };
    }

    boolean finishedAt(double elapsed) {
        return loop == LoopMode.ONCE && (jumpToEnd || elapsed >= clip.duration());
    }

    @Override
    boolean sample(double elapsed, UiAnimator animator) {
        double local = local(elapsed);
        boolean finished = finishedAt(elapsed);
        for (Slot s : slots) {
            write(s, sampleTrack(s.channel.property, ((TrackSlot) s).keys, local), elapsed, animator);
        }
        events(local, finished);
        lastLocal = local;
        return finished;
    }

    @Override
    void sampleEnd(UiAnimator animator) {
        if (loop != LoopMode.ONCE) {
            return; // a loop has no end: it holds the frame it showed
        }
        for (Slot s : slots) {
            write(s, sampleTrack(s.channel.property, ((TrackSlot) s).keys, clip.duration()), Double.MAX_VALUE,
                animator);
        }
    }

    @Override
    double length() {
        return loop == LoopMode.ONCE ? clip.duration() : Double.POSITIVE_INFINITY;
    }

    /** Seeking moves the event cursor without firing what lies between. */
    void seeked(double elapsed) {
        lastLocal = local(elapsed);
    }

    private void events(double local, boolean finished) {
        for (AnimEvent ev : clip.events()) {
            double t = ev.time();
            boolean crossed = lastLocal < 0
                ? t <= local
                : local >= lastLocal ? t > lastLocal && t <= local
                : t > lastLocal || t <= local; // wrapped around a loop
            if (crossed || (finished && t >= clip.duration() && lastLocal < t)) {
                listener.event(token, ev.name());
            }
        }
    }

    /**
     * Value of a key track at clip-local time {@code t}: held before the first key and after the
     * last; each segment eased by its first key's easing.
     */
    static UiValue sampleTrack(AnimProperty property, List<AnimKey> keys, double t) {
        AnimKey first = keys.getFirst();
        if (t <= first.time() || keys.size() == 1) {
            return first.value();
        }
        for (int i = 0; i < keys.size() - 1; i++) {
            AnimKey a = keys.get(i);
            AnimKey b = keys.get(i + 1);
            if (t < b.time()) {
                double span = b.time() - a.time();
                double u = span <= 0 ? 1 : (t - a.time()) / span;
                return property.mix(a.value(), b.value(), a.ease((float) u));
            }
        }
        return keys.getLast().value();
    }
}
