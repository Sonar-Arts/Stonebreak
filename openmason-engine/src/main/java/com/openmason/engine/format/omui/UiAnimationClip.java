package com.openmason.engine.format.omui;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code animations/<id>.anim.json}: a timeline clip sampled by the #295 runtime. Times are
 * seconds (binary64). Tracks are sorted by (target, property); keys must have strictly
 * increasing times (validated) and keep that order.
 *
 * @param duration clip length in seconds
 * @param loop     playback mode
 * @param tracks   animated channels
 * @param events   named markers fired during playback, sorted by time then name
 */
public record UiAnimationClip(String id, double duration, LoopMode loop, List<AnimTrack> tracks,
                              List<AnimEvent> events, Map<String, UiValue> unknown) {

    public UiAnimationClip {
        Objects.requireNonNull(id, "id");
        loop = loop == null ? LoopMode.ONCE : loop;
        duration = Canon.num(duration);
        tracks = Canon.sortedBy(tracks, AnimTrack.ORDER);
        events = Canon.sortedBy(events, AnimEvent.ORDER);
        unknown = Canon.unknown(unknown);
    }

    public enum LoopMode implements WireEnum {
        ONCE("once"), LOOP("loop"), PING_PONG("ping-pong");

        private final String wire;

        LoopMode(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    /**
     * @param target   stable node id (never the {@code #name}, so renames keep the clip)
     * @param property binding-target syntax: {@code style:opacity}, {@code prop:text}
     */
    public record AnimTrack(String target, String property, List<AnimKey> keys, Map<String, UiValue> unknown) {
        static final Comparator<AnimTrack> ORDER = Comparator
                .comparing(AnimTrack::target, UiValue.KEY_ORDER)
                .thenComparing(AnimTrack::property, UiValue.KEY_ORDER);

        public AnimTrack {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(property, "property");
            keys = Canon.list(keys);
            unknown = Canon.unknown(unknown);
        }
    }

    /**
     * {@code easing} (or {@code bezier} when set, #295) shapes the segment from this key to the next.
     */
    public record AnimKey(double time, UiValue value, UiEasing easing, UiBezier bezier, Map<String, UiValue> unknown) {
        public AnimKey {
            value = Canon.value(Objects.requireNonNull(value, "value"));
            time = Canon.num(time);
            easing = easing == null ? UiEasing.LINEAR : easing;
            unknown = Canon.unknown(unknown);
        }

        public AnimKey(double time, UiValue value, UiEasing easing, Map<String, UiValue> unknown) {
            this(time, value, easing, null, unknown);
        }

        /** Progress of the segment after this key at time fraction {@code u}. */
        public float ease(float u) {
            return UiBezier.ease(easing, bezier, u);
        }
    }

    public record AnimEvent(double time, String name, Map<String, UiValue> unknown) {
        static final Comparator<AnimEvent> ORDER = Comparator.comparingDouble(AnimEvent::time)
                .thenComparing(AnimEvent::name, UiValue.KEY_ORDER);

        public AnimEvent {
            Objects.requireNonNull(name, "name");
            time = Canon.num(time);
            unknown = Canon.unknown(unknown);
        }
    }
}
