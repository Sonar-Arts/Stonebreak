package com.openmason.engine.ui.runtime.anim;

import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiAnimationClip.AnimEvent;
import com.openmason.engine.format.omui.UiAnimationClip.AnimKey;
import com.openmason.engine.format.omui.UiAnimationClip.AnimTrack;
import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiEasing;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.style.StyleValues;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The host animation sampler of one document instance: script tweens ({@code ui.tween}) and
 * timeline clips ({@code ui.play}) write the elements' animation channels
 * ({@link UiElement#setAnimatedStyle}), which win over every other style layer. Sampling runs in
 * {@link #tick} on the host's clock and never waits on scripts; completions and clip events are
 * reported to a {@link Listener} for the script runtime to deliver at its next frame point.
 *
 * <p>Numbers and colours interpolate; any other value switches at the end of its segment. A
 * finished tween or one-shot clip <b>holds</b> its final values until {@link #release} or
 * {@link #stop}, so a faded-out panel stays faded; releasing hands the property back to the
 * cascade (bindings, classes, inline styles). Under reduced motion every animation jumps to its
 * end state on its first tick.
 *
 * <p>This is the shared seam #295 grows (style transitions, precedence rules, timelines): only
 * {@code style:} tracks are sampled here, and per-property interruption is "last writer wins".
 */
public final class UiAnimator {

    /** Receives completions and clip events; called from {@link #tick} or {@link #stop}. */
    public interface Listener {
        void finished(long token, boolean stopped);

        default void event(long token, String name) {
        }
    }

    private abstract static class Anim {
        final long token;
        final Listener listener;
        double time;

        Anim(long token, Listener listener) {
            this.token = token;
            this.listener = listener;
        }

        /** Samples at {@code time + dt}; @return true when finished. */
        abstract boolean advance(double dt, boolean reduced);

        /** Every (element key, property) this animation writes. */
        abstract void channels(List<String[]> out);
    }

    private final UiDocumentInstance ui;
    private final List<Anim> running = new ArrayList<>();
    private final List<Anim> done = new ArrayList<>();
    private long nextToken = 1;

    public UiAnimator(UiDocumentInstance ui) {
        this.ui = ui;
    }

    public int active() {
        return running.size();
    }

    /**
     * Animates {@code targets} (style property → end value) on the element with {@code key} from
     * their current values over {@code duration} seconds, after {@code delay}.
     */
    public long tween(String key, Map<String, UiValue> targets, double duration, UiEasing easing, double delay,
                      Listener listener) {
        UiElement el = ui.find(key);
        if (el == null) {
            throw new IllegalArgumentException("no element " + key);
        }
        ui.resolveStyles(); // start from what the element shows now, including writes made this frame
        Map<String, UiValue[]> props = new LinkedHashMap<>();
        for (Map.Entry<String, UiValue> e : targets.entrySet()) {
            UiValue from = el.animatedStyle(e.getKey());
            if (from == null) {
                from = el.computedStyle().get(e.getKey());
            }
            if (from == null) {
                from = neutral(e.getKey(), e.getValue());
            }
            props.put(e.getKey(), new UiValue[]{from, e.getValue()});
        }
        // A new tween takes over the properties it animates (last writer wins).
        for (String p : props.keySet()) {
            stopChannel(key, p);
        }
        Tween t = new Tween(nextToken++, listener, key, props, Math.max(0, duration), Math.max(0, delay),
            easing == null ? UiEasing.LINEAR : easing);
        running.add(t);
        return t.token;
    }

    /**
     * Plays {@code clip}; {@code keyOf} maps a track's node id to an element key (the clip's
     * document scope). {@code loop} overrides the clip's mode when non-null.
     */
    public long play(UiAnimationClip clip, UnaryOperator<String> keyOf, double speed, LoopMode loop,
                     Listener listener) {
        Clip c = new Clip(nextToken++, listener, clip, keyOf, speed <= 0 ? 1 : speed, loop == null ? clip.loop() : loop);
        List<String[]> mine = new ArrayList<>();
        c.channels(mine);
        for (String[] ch : mine) {
            stopChannel(ch[0], ch[1]);
        }
        running.add(c);
        return c.token;
    }

    /** Stops the animation (its values stay held); its listener sees {@code stopped}. */
    public boolean stop(long token) {
        for (int i = 0; i < running.size(); i++) {
            Anim a = running.get(i);
            if (a.token == token) {
                running.remove(i);
                a.listener.finished(token, true);
                return true;
            }
        }
        return false;
    }

    /** Stops whatever animates {@code property} on {@code key} and hands it back to the cascade. */
    public void release(String key, String property) {
        stopChannel(key, property);
        UiElement el = ui.find(key);
        if (el != null) {
            el.clearAnimatedStyle(property);
        }
    }

    /** Stops everything without notifying (screen close). */
    public void clear() {
        running.clear();
    }

    /** Advances every animation by {@code dt} seconds of UI time. */
    public void tick(double dt) {
        if (running.isEmpty()) {
            return;
        }
        boolean reduced = ui.preferences().reducedMotion();
        for (int i = 0; i < running.size(); i++) {
            Anim a = running.get(i);
            if (a.advance(dt, reduced)) {
                done.add(a);
            }
        }
        if (!done.isEmpty()) {
            running.removeAll(done);
            for (Anim a : done) {
                a.listener.finished(a.token, false);
            }
            done.clear();
        }
    }

    private void stopChannel(String key, String property) {
        List<String[]> chans = new ArrayList<>();
        for (int i = running.size() - 1; i >= 0; i--) {
            Anim a = running.get(i);
            chans.clear();
            a.channels(chans);
            for (String[] ch : chans) {
                if (ch[0].equals(key) && ch[1].equals(property)) {
                    running.remove(i);
                    a.listener.finished(a.token, true);
                    break;
                }
            }
        }
    }

    // ── tweens ──────────────────────────────────────────────────────────────

    private final class Tween extends Anim {
        final String key;
        final Map<String, UiValue[]> props;
        final double duration;
        final double delay;
        final UiEasing easing;

        Tween(long token, Listener l, String key, Map<String, UiValue[]> props, double duration, double delay,
              UiEasing easing) {
            super(token, l);
            this.key = key;
            this.props = props;
            this.duration = duration;
            this.delay = delay;
            this.easing = easing;
        }

        @Override
        boolean advance(double dt, boolean reduced) {
            time += dt;
            UiElement el = ui.find(key);
            if (el == null) {
                return true;
            }
            if (!reduced && time < delay) {
                return false;
            }
            double t = reduced || duration == 0 ? 1 : Math.clamp((time - delay) / duration, 0, 1);
            float f = easing.curve().apply((float) t);
            for (Map.Entry<String, UiValue[]> e : props.entrySet()) {
                el.setAnimatedStyle(e.getKey(), mix(e.getValue()[0], e.getValue()[1], f, t >= 1));
            }
            return t >= 1;
        }

        @Override
        void channels(List<String[]> out) {
            for (String p : props.keySet()) {
                out.add(new String[]{key, p});
            }
        }
    }

    // ── clips ───────────────────────────────────────────────────────────────

    private final class Clip extends Anim {
        final UiAnimationClip clip;
        final UnaryOperator<String> keyOf;
        final double speed;
        final LoopMode loop;
        double lastLocal = -1;

        Clip(long token, Listener l, UiAnimationClip clip, UnaryOperator<String> keyOf, double speed, LoopMode loop) {
            super(token, l);
            this.clip = clip;
            this.keyOf = keyOf;
            this.speed = speed;
            this.loop = loop;
        }

        @Override
        boolean advance(double dt, boolean reduced) {
            double d = clip.duration();
            time += dt * speed;
            boolean finished = loop == LoopMode.ONCE && (time >= d || reduced);
            double local;
            if (reduced) {
                local = loop == LoopMode.ONCE ? d : 0;
            } else if (d <= 0) {
                local = 0;
            } else if (loop == LoopMode.LOOP) {
                local = time % d;
            } else if (loop == LoopMode.PING_PONG) {
                double m = time % (2 * d);
                local = m <= d ? m : 2 * d - m;
            } else {
                local = Math.min(time, d);
            }
            sample(local);
            events(local, finished);
            lastLocal = local;
            return finished;
        }

        private void sample(double t) {
            for (AnimTrack track : clip.tracks()) {
                if (!track.property().startsWith("style:") || track.keys().isEmpty()) {
                    continue;
                }
                UiElement el = ui.find(keyOf.apply(track.target()));
                if (el == null) {
                    continue;
                }
                el.setAnimatedStyle(track.property().substring(6), sampleTrack(track.keys(), t));
            }
        }

        private void events(double local, boolean finished) {
            for (AnimEvent ev : clip.events()) {
                boolean crossed = lastLocal < 0
                    ? ev.time() <= local
                    : local >= lastLocal ? ev.time() > lastLocal && ev.time() <= local
                    : ev.time() > lastLocal || ev.time() <= local; // wrapped around a loop
                if (crossed || (finished && ev.time() >= clip.duration() && lastLocal < ev.time())) {
                    listener.event(token, ev.name());
                }
            }
        }

        @Override
        void channels(List<String[]> out) {
            for (AnimTrack track : clip.tracks()) {
                if (track.property().startsWith("style:")) {
                    out.add(new String[]{keyOf.apply(track.target()), track.property().substring(6)});
                }
            }
        }
    }

    /** Value of a key track at {@code t}: held before the first and after the last key. */
    static UiValue sampleTrack(List<AnimKey> keys, double t) {
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
                return mix(a.value(), b.value(), a.easing().curve().apply((float) u), false);
            }
        }
        return keys.getLast().value();
    }

    // ── interpolation ───────────────────────────────────────────────────────

    /** {@code from → to} at eased fraction {@code f}; non-numeric values switch at the end. */
    static UiValue mix(UiValue from, UiValue to, float f, boolean end) {
        if (end || f >= 1f) {
            return to;
        }
        if (from instanceof UiValue.Num a && to instanceof UiValue.Num b) {
            return UiValue.of(a.value() + (b.value() - a.value()) * f);
        }
        if (isColor(from) && isColor(to)) {
            int ca = StyleValues.color(from, 0);
            int cb = StyleValues.color(to, 0);
            int argb = 0;
            for (int shift = 0; shift < 32; shift += 8) {
                int x = (ca >>> shift) & 0xFF;
                int y = (cb >>> shift) & 0xFF;
                argb |= (Math.round(x + (y - x) * f) & 0xFF) << shift;
            }
            return UiValue.of(String.format("#%02X%02X%02X%02X", (argb >>> 16) & 0xFF, (argb >>> 8) & 0xFF,
                argb & 0xFF, argb >>> 24));
        }
        return f < 1f ? from : to;
    }

    private static boolean isColor(UiValue v) {
        return v instanceof UiValue.Str s && s.value().startsWith("#");
    }

    /** Start value of a property nothing has set yet. */
    private static UiValue neutral(String property, UiValue target) {
        if (target instanceof UiValue.Num) {
            return UiValue.of("opacity".equals(property) || "scale".equals(property) ? 1 : 0);
        }
        if (isColor(target)) {
            return UiValue.of("#00000000");
        }
        return target;
    }
}
