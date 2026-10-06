package com.openmason.engine.ui.runtime.anim;

import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiAnimationClip.AnimKey;
import com.openmason.engine.format.omui.UiAnimationClip.AnimTrack;
import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiBezier;
import com.openmason.engine.format.omui.UiEasing;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.WireEnum;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import com.openmason.engine.ui.runtime.style.ComputedStyle;
import com.openmason.engine.ui.runtime.style.StyleValues;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * The one animation sampler of a document instance (#295). Style transitions, timeline clips,
 * script tweens and state machines all write through it, into each element's animation channel
 * ({@link UiElement#setAnimatedStyle}, {@link UiElement#setAnimatedProp}).
 *
 * <p><b>Precedence per channel</b> (element key, property), lowest first:
 * <ol>
 *   <li>the cascade: sheet rules (pseudo-states included), inline, overrides, binding, local;</li>
 *   <li>a style <b>transition</b> easing the cascade's own change (UI clock);</li>
 *   <li>the <b>explicit</b> value of the clip or tween that most recently claimed the channel,
 *       held after it ends until released.</li>
 * </ol>
 * A newer clip or tween takes over only the channels it animates; an animation left with none is
 * interrupted (its listener sees {@code stopped}). Releasing a channel hands it back to the
 * cascade through the property's declared transition, if any, so a binding's latest value
 * resumes smoothly. Transitions keep tracking the cascade underneath a held value.
 *
 * <p><b>Time.</b> Every animation reads one {@link UiClocks} clock and samples at an absolute
 * elapsed time, so the same clock reading gives the same frame in the preview and the game.
 * {@link #sample()} runs once per host frame (via {@code UiDocumentView.frame}) and never waits
 * on scripts; completions and clip events go to {@link Listener}s, which queue them.
 *
 * <p><b>Reduced motion</b> (decided when an animation starts): transitions and tweens of motion
 * properties ({@link AnimProperty#motion()}) jump; other properties keep a fade of at most
 * {@link #REDUCED_FADE_SECONDS} with no delay. One-shot clips jump to their end (events still
 * fire) and loops hold their first frame, unless the caller plays a declared reduced alternate.
 */
public final class UiAnimator {

    /** Longest fade a non-motion transition or tween keeps under reduced motion. */
    public static final double REDUCED_FADE_SECONDS = 0.15;

    /** What an animation leaves behind when it ends on its own. */
    public enum Fill implements WireEnum {
        /** Keep showing the final values until released (the default). */
        HOLD("hold"),
        /** Hand the channels back to the cascade (through declared transitions). */
        RELEASE("release");

        private final String wire;

        Fill(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    /** How {@link #stop} leaves the channels. */
    public enum StopMode implements WireEnum {
        /** Freeze on the value shown now. */
        HOLD("hold"),
        /** Jump to the final values and hold them (loops hold the current frame). */
        END("end"),
        /** Hand the channels back to the cascade. */
        RELEASE("release");

        private final String wire;

        StopMode(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    /** Receives completions and clip events; called from {@link #sample}, {@link #play} or {@link #stop}. */
    public interface Listener {
        Listener NONE = (token, stopped) -> {
        };

        void finished(long token, boolean stopped);

        default void event(long token, String name) {
        }
    }

    /**
     * @param clock   time source ({@link UiClocks#UI} by default)
     * @param speed   playback rate; 0 pauses
     * @param loop    overrides the clip's mode when non-null
     * @param blend   seconds to cross-fade from what each channel shows
     * @param fill    what the end leaves
     * @param at      clip time to start from
     * @param restart false: a running playback of the same clip by the same owner keeps going
     */
    public record PlayOptions(String clock, double speed, LoopMode loop, double blend, Fill fill, double at,
                              boolean restart) {
        public static final PlayOptions DEFAULTS = new PlayOptions(UiClocks.UI, 1, null, 0, Fill.HOLD, 0, true);

        public PlayOptions {
            clock = clock == null ? UiClocks.UI : clock;
            fill = fill == null ? Fill.HOLD : fill;
            if (!(speed >= 0) || !Double.isFinite(speed)) {
                throw new IllegalArgumentException("speed must be a finite number >= 0");
            }
            blend = Math.max(0, blend);
            at = Math.max(0, at);
        }

        public PlayOptions withLoop(LoopMode l) {
            return new PlayOptions(clock, speed, l, blend, fill, at, restart);
        }

        public PlayOptions withBlend(double b) {
            return new PlayOptions(clock, speed, loop, b, fill, at, restart);
        }
    }

    /**
     * @param from   start values by property (else what each channel shows)
     * @param bezier a custom timing curve overriding the tween's easing, or null
     */
    public record TweenOptions(double delay, String clock, Fill fill, Map<String, UiValue> from, UiBezier bezier) {
        public static final TweenOptions DEFAULTS = new TweenOptions(0, UiClocks.UI, Fill.HOLD, Map.of());

        public TweenOptions {
            delay = Math.max(0, delay);
            clock = clock == null ? UiClocks.UI : clock;
            fill = fill == null ? Fill.HOLD : fill;
            from = from == null ? Map.of() : Map.copyOf(from);
        }

        public TweenOptions(double delay, String clock, Fill fill, Map<String, UiValue> from) {
            this(delay, clock, fill, from, null);
        }
    }

    private final UiDocumentInstance ui;
    private final UiClocks clocks;
    private final Map<String, Map<String, Channel>> channels = new HashMap<>();
    private final List<Playback> running = new ArrayList<>();
    private final List<Transition> transitions = new ArrayList<>();
    private final List<Playback> scratch = new ArrayList<>();
    private final boolean[] done = new boolean[1];
    /**
     * Animation tokens live above 2^40 so they never collide with the action and timer tokens a
     * script runtime counts from 1 (both key the same Lua handle table).
     */
    private long nextToken = 1L << 40;

    public UiAnimator(UiDocumentInstance ui, UiClocks clocks) {
        this.ui = Objects.requireNonNull(ui, "ui");
        this.clocks = Objects.requireNonNull(clocks, "clocks");
    }

    public UiClocks clocks() {
        return clocks;
    }

    /** Running clips and tweens. */
    public int active() {
        return running.size();
    }

    /** Running style transitions. */
    public int activeTransitions() {
        return transitions.size();
    }

    /** True while something will change on its own (hosts keep repainting). */
    public boolean animating() {
        return !running.isEmpty() || !transitions.isEmpty();
    }

    // ── tweens ──────────────────────────────────────────────────────────────

    /** {@link #tween(Object, String, Map, double, UiEasing, TweenOptions, Listener)} on the UI clock. */
    public long tween(String key, Map<String, UiValue> targets, double duration, UiEasing easing, double delay,
                      Listener listener) {
        return tween(null, key, targets, duration, easing,
            new TweenOptions(delay, UiClocks.UI, Fill.HOLD, Map.of()), listener);
    }

    /**
     * Animates {@code targets} (style property, or {@code prop:<name>}, → end value) on the element
     * with {@code key} from what it shows now over {@code duration} seconds.
     *
     * @param owner groups animations for {@link #clear(Object)} (a script runtime, a state machine)
     * @throws IllegalArgumentException for an unknown element, property, clock or unfit value
     */
    public long tween(Object owner, String key, Map<String, UiValue> targets, double duration, UiEasing easing,
                      TweenOptions options, Listener listener) {
        UiElement el = ui.find(key);
        if (el == null) {
            throw new IllegalArgumentException("no element " + key);
        }
        TweenOptions o = options == null ? TweenOptions.DEFAULTS : options;
        double now = clocks.now(o.clock());
        ui.resolveStyles(); // start from what the element shows now, including writes made this frame
        boolean reduced = ui.preferences().reducedMotion();
        TweenPlayback p = new TweenPlayback(nextToken++, owner, listener == null ? Listener.NONE : listener, o.clock(),
            o.fill(), easing, o.bezier());
        for (Map.Entry<String, UiValue> e : targets.entrySet()) {
            AnimProperty ap = property(e.getKey(), el);
            UiValue to = resolve(el, e.getValue());
            String problem = ap.problem(to, el.descriptor());
            if (problem != null) {
                throw new IllegalArgumentException(e.getKey() + ": " + problem);
            }
            UiValue from = o.from().containsKey(e.getKey()) ? resolve(el, o.from().get(e.getKey())) : shown(el, ap);
            double d = Math.max(0, duration);
            double delay = o.delay();
            if (reduced) {
                d = ap.motion() ? 0 : Math.min(d, REDUCED_FADE_SECONDS);
                delay = 0;
            }
            p.slots.add(new TweenPlayback.TweenSlot(channel(key, ap), from, to, delay, d));
        }
        return start(p, now, 0, 1);
    }

    // ── clips ───────────────────────────────────────────────────────────────

    /** Plays {@code clip} on the UI clock; {@code loop} overrides the clip's mode when non-null. */
    public long play(UiAnimationClip clip, UnaryOperator<String> keyOf, double speed, LoopMode loop,
                     Listener listener) {
        return play(null, clip, keyOf, new PlayOptions(UiClocks.UI, speed <= 0 ? 1 : speed, loop, 0, Fill.HOLD, 0,
            true), listener);
    }

    /**
     * Plays {@code clip}; {@code keyOf} maps a track's node id to an element key (the clip's
     * document scope). Tracks whose target, property or key values do not fit are reported
     * ({@link UiRuntimeDiagnostic.Code#ANIMATION_TRACK}) and skipped; the rest play.
     */
    public long play(Object owner, UiAnimationClip clip, UnaryOperator<String> keyOf, PlayOptions options,
                     Listener listener) {
        PlayOptions o = options == null ? PlayOptions.DEFAULTS : options;
        double now = clocks.now(o.clock());
        if (!o.restart()) {
            for (Playback r : running) {
                if (r instanceof ClipPlayback c && c.clip.id().equals(clip.id()) && Objects.equals(r.owner, owner)) {
                    return r.token;
                }
            }
        }
        ui.resolveStyles();
        ClipPlayback p = new ClipPlayback(nextToken++, owner, listener == null ? Listener.NONE : listener, o.clock(),
            o.fill(), o.blend(), clip, o.loop());
        for (ClipTrack t : resolveTracks(clip, keyOf, true)) {
            p.slots.add(new ClipPlayback.TrackSlot(channel(t.key, t.property), t.keys));
        }
        p.jumpToEnd = ui.preferences().reducedMotion();
        return start(p, now, o.at(), o.speed());
    }

    /** A track of a clip resolved against this instance. */
    record ClipTrack(String key, AnimProperty property, List<AnimKey> keys) {
    }

    /**
     * Resolves every track of {@code clip} to an element channel; with {@code report}, tracks that
     * cannot play are reported. {@code var()} key values resolve against the element now.
     */
    List<ClipTrack> resolveTracks(UiAnimationClip clip, UnaryOperator<String> keyOf, boolean report) {
        List<ClipTrack> out = new ArrayList<>();
        for (AnimTrack track : clip.tracks()) {
            String key = keyOf.apply(track.target());
            String problem = null;
            UiElement el = ui.find(key);
            AnimProperty ap = null;
            List<AnimKey> keys = track.keys();
            if (el == null) {
                problem = "no element '" + key + "' for track " + track.target() + " " + track.property();
            } else if ((ap = AnimProperty.of(track.property(), el.descriptor())) == null) {
                problem = track.property() + " cannot be animated on " + el.type();
            } else if (!keys.isEmpty()) {
                try {
                    keys = resolvedKeys(el, keys);
                } catch (IllegalArgumentException e) {
                    problem = track.property() + ": " + e.getMessage();
                    keys = List.of();
                }
                for (AnimKey k : keys) {
                    String p = ap.problem(k.value(), el.descriptor());
                    if (p != null) {
                        problem = track.property() + " key at " + k.time() + " s: " + p;
                        break;
                    }
                }
            }
            if (problem != null) {
                if (report) {
                    ui.reportDiagnostic(UiRuntimeDiagnostic.warning(UiRuntimeDiagnostic.Code.ANIMATION_TRACK, key,
                        "clip " + clip.id() + ": " + problem));
                }
            } else if (!keys.isEmpty()) {
                out.add(new ClipTrack(key, ap, keys));
            }
        }
        return out;
    }

    private static List<AnimKey> resolvedKeys(UiElement el, List<AnimKey> keys) {
        boolean anyVar = false;
        for (AnimKey k : keys) {
            anyVar |= StyleValues.isVar(k.value());
        }
        if (!anyVar) {
            return keys;
        }
        List<AnimKey> out = new ArrayList<>(keys.size());
        for (AnimKey k : keys) {
            out.add(new AnimKey(k.time(), resolve(el, k.value()), k.easing(), k.unknown()));
        }
        return out;
    }

    // ── control ─────────────────────────────────────────────────────────────

    /** {@link #stop(long, StopMode)} holding the values shown now. */
    public boolean stop(long token) {
        return stop(token, StopMode.HOLD);
    }

    /** Stops a clip or tween; its listener sees {@code stopped}. @return false when it already ended */
    public boolean stop(long token, StopMode mode) {
        Playback p = find(token);
        if (p == null) {
            return false;
        }
        if (mode == StopMode.END) {
            p.sampleEnd(this);
        }
        end(p, true, mode == StopMode.RELEASE);
        return true;
    }

    /** Moves a running animation to {@code seconds} of its own time (events in between do not fire). */
    public boolean seek(long token, double seconds) {
        Playback p = find(token);
        if (p == null || !Double.isFinite(seconds)) {
            return false;
        }
        double t = Math.max(0, seconds);
        p.rebase(clocks.now(p.clock), t, p.speed);
        if (p instanceof ClipPlayback c) {
            c.seeked(t);
        }
        if (p.sample(t, this)) {
            end(p, false, p.fill == Fill.RELEASE);
        }
        return true;
    }

    /** Changes the playback rate from now on (0 pauses) without a jump. */
    public boolean setSpeed(long token, double speed) {
        Playback p = find(token);
        if (p == null || !(speed >= 0) || !Double.isFinite(speed)) {
            return false;
        }
        double now = clocks.now(p.clock);
        p.rebase(now, p.elapsed(now), speed);
        return true;
    }

    /** Own time of a running animation, or NaN when it ended. */
    public double elapsed(long token) {
        Playback p = find(token);
        return p == null ? Double.NaN : p.elapsed(clocks.now(p.clock));
    }

    public boolean isRunning(long token) {
        return find(token) != null;
    }

    /** Tokens of {@code owner}'s running playbacks of clip {@code clipId}, oldest first. */
    public List<Long> clipTokens(Object owner, String clipId) {
        List<Long> out = new ArrayList<>();
        for (Playback p : running) {
            if (p instanceof ClipPlayback c && c.clip.id().equals(clipId) && Objects.equals(p.owner, owner)) {
                out.add(p.token);
            }
        }
        return out;
    }

    /**
     * Stops whatever animates {@code property} ({@code opacity} or {@code prop:value}) on
     * {@code key} and hands it back to the cascade, through the property's declared transition.
     */
    public void release(String key, String property) {
        String target = property.startsWith(AnimProperty.PROP) || property.startsWith(AnimProperty.STYLE)
            ? property : AnimProperty.STYLE + property;
        Map<String, Channel> byTarget = channels.get(key);
        Channel ch = byTarget == null ? null : byTarget.get(target);
        if (ch == null) {
            UiElement el = ui.find(key);
            if (el != null && target.startsWith(AnimProperty.STYLE)) {
                el.clearAnimatedStyle(target.substring(AnimProperty.STYLE.length()));
            } else if (el != null) {
                el.clearAnimatedProp(target.substring(AnimProperty.PROP.length()));
            }
            return;
        }
        Playback owner = ch.owner;
        if (owner != null) {
            lose(owner, ch);
            if (!owner.alive()) {
                end(owner, true, false);
            }
        }
        releaseChannel(ch, true);
    }

    /**
     * Hands back every channel whose value {@code owner}'s animations wrote, except those the
     * running animation {@code keep} animates (0 = none): running ones are interrupted. State
     * machines use it so a new state releases what its clip does not pose.
     */
    public void releaseHeld(Object owner, long keep) {
        List<Channel> held = new ArrayList<>();
        for (Map<String, Channel> byTarget : channels.values()) {
            for (Channel ch : byTarget.values()) {
                if (ch.holder != null && ch.holder.equals(owner) && (ch.owner == null || ch.owner.token != keep)) {
                    held.add(ch);
                }
            }
        }
        for (Channel ch : held) {
            Playback p = ch.owner;
            if (p != null) {
                lose(p, ch);
                if (!p.alive()) {
                    end(p, true, false);
                }
            }
            releaseChannel(ch, true);
        }
    }

    /** Stops every animation of {@code owner} without notifying (script reload, machine teardown); values stay held. */
    public void clear(Object owner) {
        for (int i = running.size() - 1; i >= 0; i--) {
            Playback p = running.get(i);
            if (Objects.equals(p.owner, owner)) {
                running.remove(i);
                detach(p);
            }
        }
    }

    /** Stops everything without notifying (screen close); running transitions settle on the cascade. */
    public void clear() {
        for (Playback p : running) {
            detach(p);
        }
        running.clear();
        for (Transition t : transitions) {
            t.channel.transition = null;
            t.channel.transitionValue = null;
            apply(t.channel);
        }
        transitions.clear();
    }

    // ── sampling ────────────────────────────────────────────────────────────

    /** Samples every animation at its clock's current reading. Once per host frame. */
    public void sample() {
        if (!running.isEmpty()) {
            scratch.clear();
            scratch.addAll(running);
            for (int i = 0; i < scratch.size(); i++) {
                Playback p = scratch.get(i);
                if (p.ended) {
                    continue;
                }
                if (p.sample(p.elapsed(clocks.now(p.clock)), this)) {
                    end(p, false, p.fill == Fill.RELEASE);
                }
            }
            scratch.clear();
        }
        for (int i = transitions.size() - 1; i >= 0; i--) {
            Transition t = transitions.get(i);
            UiValue v = t.sample(clocks.now(UiClocks.UI), done);
            if (done[0]) {
                transitions.remove(i);
                t.channel.transition = null;
                t.channel.transitionValue = null;
            } else {
                t.channel.transitionValue = v;
            }
            apply(t.channel);
        }
    }

    // ── style transitions ───────────────────────────────────────────────────

    /**
     * The cascade of {@code el} changed from {@code before} to {@code after} (called by the
     * instance while resolving styles): properties with a declared transition start one from the
     * value the transition layer showed. Properties without one, or whose transition is
     * {@code all} on a layout property, settle immediately.
     */
    public void baseChanged(UiElement el, ComputedStyle before, ComputedStyle after) {
        for (String p : after.changedProperties(before)) {
            AnimProperty ap = AnimProperty.style(p);
            if (ap == null) {
                continue;
            }
            Channel ch = existing(el.key(), ap.target());
            UiStyleSheet.StyleTransition t = after.transition(p);
            if (!transitions(ap, t)) {
                if (ch != null && ch.transition != null) {
                    cancelTransition(ch);
                }
                continue;
            }
            UiValue to = after.get(p);
            if (ch != null && ch.transition != null && Objects.equals(ch.transition.to, to)) {
                continue; // already heading there
            }
            UiValue from = ch != null && ch.transition != null ? ch.transitionValue : before.get(p);
            startTransition(ch != null ? ch : channel(el.key(), ap), from, to, t);
        }
    }

    private static boolean transitions(AnimProperty ap, UiStyleSheet.StyleTransition t) {
        return t != null && ap.interpolates() && !("all".equals(t.property()) && ap.relayout());
    }

    private void startTransition(Channel ch, UiValue from, UiValue to, UiStyleSheet.StyleTransition t) {
        double duration = t.duration();
        double delay = t.delay();
        if (ui.preferences().reducedMotion()) {
            duration = ch.property.motion() ? 0 : Math.min(duration, REDUCED_FADE_SECONDS);
            delay = 0;
        }
        if (ch.transition != null) {
            transitions.remove(ch.transition);
        }
        if (duration + delay <= 0 || Objects.equals(from, to)) {
            ch.transition = null;
            ch.transitionValue = null;
            apply(ch);
            return;
        }
        Transition tr = new Transition(ch, from == null ? ch.property.neutral(to) : from, to,
            clocks.now(UiClocks.UI), delay, duration, t);
        ch.transition = tr;
        ch.transitionValue = tr.from;
        transitions.add(tr);
        apply(ch);
    }

    private void cancelTransition(Channel ch) {
        transitions.remove(ch.transition);
        ch.transition = null;
        ch.transitionValue = null;
        apply(ch);
    }

    // ── channels ────────────────────────────────────────────────────────────

    private long start(Playback p, double now, double at, double speed) {
        p.rebase(now, at, speed);
        List<Playback> interrupted = new ArrayList<>();
        for (Playback.Slot s : p.slots) {
            s.blendFrom = s.channel.display() != null ? s.channel.display() : shown(ui.find(s.channel.key),
                s.channel.property);
            Playback prev = s.channel.owner;
            if (prev != null && prev != p) {
                lose(prev, s.channel);
                if (!prev.alive() && !interrupted.contains(prev)) {
                    interrupted.add(prev);
                }
            }
            s.channel.owner = p;
            s.channel.holder = p.owner;
        }
        for (Playback prev : interrupted) {
            end(prev, true, false);
        }
        running.add(p);
        if (p.sample(p.elapsed(now), this)) { // first frame now: no flash of the unanimated state
            end(p, false, p.fill == Fill.RELEASE);
        }
        return p.token;
    }

    private static void lose(Playback p, Channel ch) {
        for (Playback.Slot s : p.slots) {
            if (s.channel == ch) {
                s.lost = true;
            }
        }
        if (ch.owner == p) {
            ch.owner = null;
        }
    }

    /** Removes {@code p}; its channels hold, or release with {@code release}. Notifies its listener. */
    private void end(Playback p, boolean stopped, boolean release) {
        if (p.ended) {
            return;
        }
        running.remove(p);
        p.ended = true;
        for (Playback.Slot s : p.slots) {
            if (!s.lost && s.channel.owner == p) {
                s.channel.owner = null;
                if (release) {
                    releaseChannel(s.channel, true);
                }
            }
        }
        p.listener.finished(p.token, stopped);
    }

    private void detach(Playback p) {
        p.ended = true;
        for (Playback.Slot s : p.slots) {
            if (s.channel.owner == p) {
                s.channel.owner = null;
            }
        }
    }

    private void releaseChannel(Channel ch, boolean smooth) {
        UiValue shown = ch.display();
        ch.explicit = null;
        ch.holder = null;
        if (smooth && ch.property.isStyle() && ch.transition == null && shown != null) {
            UiElement el = ui.find(ch.key);
            if (el != null) {
                String name = ch.property.name();
                UiStyleSheet.StyleTransition t = el.baseStyle().transition(name);
                if (transitions(ch.property, t)) {
                    startTransition(ch, shown, el.baseStyle().get(name), t);
                    return;
                }
            }
        }
        apply(ch);
    }

    void writeExplicit(Channel ch, UiValue value) {
        ch.explicit = value;
        apply(ch);
    }

    /** Pushes what {@code ch} shows to its element; drops empty channels. */
    private void apply(Channel ch) {
        UiElement el = ui.find(ch.key);
        if (el != null) {
            UiValue v = ch.display();
            String name = ch.property.name();
            if (ch.property.isStyle()) {
                if (v == null) {
                    el.clearAnimatedStyle(name);
                } else {
                    el.setAnimatedStyle(name, v);
                }
            } else if (v == null) {
                el.clearAnimatedProp(name);
            } else {
                el.setAnimatedProp(name, v);
            }
        }
        if (ch.empty() || el == null) {
            Map<String, Channel> byTarget = channels.get(ch.key);
            if (byTarget != null && byTarget.get(ch.property.target()) == ch && ch.empty()) {
                byTarget.remove(ch.property.target());
                if (byTarget.isEmpty()) {
                    channels.remove(ch.key);
                }
            }
        }
    }

    private Channel channel(String key, AnimProperty ap) {
        return channels.computeIfAbsent(key, k -> new LinkedHashMap<>())
            .computeIfAbsent(ap.target(), t -> new Channel(key, ap));
    }

    private Channel existing(String key, String target) {
        Map<String, Channel> byTarget = channels.get(key);
        return byTarget == null ? null : byTarget.get(target);
    }

    private Playback find(long token) {
        for (Playback p : running) {
            if (p.token == token) {
                return p;
            }
        }
        return null;
    }

    private static AnimProperty property(String name, UiElement el) {
        String target = name.startsWith(AnimProperty.PROP) || name.startsWith(AnimProperty.STYLE) ? name
            : AnimProperty.STYLE + name;
        AnimProperty ap = AnimProperty.of(target, el.descriptor());
        if (ap == null) {
            throw new IllegalArgumentException(name + " cannot be animated on " + el.type()
                + " (style properties, or prop:<name> of the widget)");
        }
        return ap;
    }

    /** What {@code el} shows for {@code ap} now (after any animation), or null. */
    private static UiValue shown(UiElement el, AnimProperty ap) {
        if (el == null) {
            return null;
        }
        if (ap.isStyle()) {
            return el.computedStyle().get(ap.name());
        }
        UiValue v = el.prop(ap.name());
        return v == UiValue.NULL ? null : v;
    }

    private static UiValue resolve(UiElement el, UiValue v) {
        if (StyleValues.isVar(v)) {
            UiValue r = el.computedStyle().customs().get(StyleValues.varName(v));
            if (r == null) {
                throw new IllegalArgumentException(((UiValue.Str) v).value() + " is not defined at " + el.key());
            }
            return r;
        }
        return v;
    }
}
