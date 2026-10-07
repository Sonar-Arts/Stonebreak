package com.openmason.engine.ui.runtime.anim;

import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiAnimationClip.AnimEvent;
import com.openmason.engine.format.omui.UiAnimationClip.AnimKey;
import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiValue;

import java.util.ArrayList;
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

    /**
     * Most event firings one sample reports per event (a frame step spanning thousands of loop
     * cycles, e.g. after a long pause on an external clock, must not flood the listener).
     */
    static final int MAX_FIRINGS_PER_EVENT = 64;

    final UiAnimationClip clip;
    final LoopMode loop;
    /**
     * Own (unwrapped) time of the previous sample; -1 before the first (events at 0 then fire).
     * Events are found by the occurrences of their clip time in own time, so ping-pong's backward
     * half and frame steps longer than the clip fire exactly what was crossed.
     */
    double lastElapsed = -1;
    private final List<Firing> firings = new ArrayList<>();

    private record Firing(double at, int order, String name) {
    }

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
        double now = eventTime(elapsed);
        events(now, finished);
        lastElapsed = now;
        return finished;
    }

    /** Own time for event crossing: a reduced-motion loop holds its first frame, so its time stands still. */
    private double eventTime(double elapsed) {
        return jumpToEnd && loop != LoopMode.ONCE ? 0 : Math.max(0, elapsed);
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
        lastElapsed = eventTime(elapsed);
    }

    /**
     * Fires every event occurrence in own time {@code (lastElapsed, now]} ({@code [0, now]} on the
     * first sample), in time order. An event at clip time {@code t} occurs at {@code t} once; at
     * {@code t + k·d} in a loop; and at {@code t + 2k·d} and {@code 2d − t + 2k·d} (forward and
     * backward passes) in ping-pong. A one-shot that finishes also fires events placed at or past
     * its end. A clock that went backwards fires nothing.
     */
    private void events(double now, boolean finished) {
        List<AnimEvent> events = clip.events();
        if (events.isEmpty()) {
            return;
        }
        double lo = lastElapsed;
        boolean first = lo < 0;
        if (!first && now <= lo && !(finished && loop == LoopMode.ONCE)) {
            return;
        }
        double d = clip.duration();
        double hi = finished && loop == LoopMode.ONCE ? Double.POSITIVE_INFINITY : now;
        firings.clear();
        for (int i = 0; i < events.size(); i++) {
            AnimEvent ev = events.get(i);
            double t = ev.time();
            if (loop == LoopMode.ONCE || d <= 0) {
                if ((first ? t >= 0 : t > lo) && t <= hi) {
                    firings.add(new Firing(t, i, ev.name()));
                }
                continue;
            }
            double period = loop == LoopMode.LOOP ? d : 2 * d;
            occurrences(t, period, lo, first, hi, i, ev.name());
            if (loop == LoopMode.PING_PONG && t > 0 && t < d) {
                occurrences(2 * d - t, period, lo, first, hi, i, ev.name());
            }
        }
        if (firings.size() > 1) {
            firings.sort((a, b) -> a.at != b.at ? Double.compare(a.at, b.at) : Integer.compare(a.order, b.order));
        }
        for (Firing f : firings) {
            listener.event(token, f.name);
        }
        firings.clear();
    }

    /** Adds the occurrences {@code phase + k·period} (k ≥ 0) inside the window. */
    private void occurrences(double phase, double period, double lo, boolean first, double hi, int order,
                             String name) {
        long k = first || lo < phase ? 0 : (long) Math.floor((lo - phase) / period) + 1;
        double at = phase + k * period;
        if (!first && at <= lo) { // float noise at an exact boundary
            at = phase + ++k * period;
        }
        for (int n = 0; at <= hi && n < MAX_FIRINGS_PER_EVENT; n++) {
            firings.add(new Firing(at, order, name));
            at = phase + ++k * period;
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
