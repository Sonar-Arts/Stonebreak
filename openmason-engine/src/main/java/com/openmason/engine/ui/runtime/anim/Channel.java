package com.openmason.engine.ui.runtime.anim;

import com.openmason.engine.format.omui.UiValue;

import java.util.ArrayList;
import java.util.List;

/**
 * One animated (element key, property) pair and its layers (#295, #301):
 *
 * <ol>
 *   <li><b>transition</b> (lowest): a style transition easing the cascade's own changes;</li>
 *   <li><b>explicit</b>: the value of the replacing clip or tween that last claimed the channel,
 *       still held after it finished until released;</li>
 *   <li><b>additive</b> (top, #301): deltas of {@link UiAnimator.Composite#ADD} animations, summed
 *       in the order they started over whatever the layers below show (the cascade when nothing
 *       else animates). They never take the channel over and are never taken over.</li>
 * </ol>
 * The element shows the explicit value when there is one, else the transition's, else the
 * cascade, plus every additive delta. The channel is dropped once all layers are empty.
 */
final class Channel {

    /** One additive animation's delta on this channel. */
    static final class Add {
        /** The playback writing {@link #value}; null once it finished and the delta is only held. */
        Playback owner;
        final Object holder;
        /** What wrote it (a clip id, or null for a tween): a new run of the same source replaces a held delta. */
        final String source;
        UiValue value;

        Add(Playback owner, String source) {
            this.owner = owner;
            this.holder = owner.owner;
            this.source = source;
        }
    }

    final String key;
    final AnimProperty property;
    /** Playback that writes {@link #explicit}; null once it finished and only holds. */
    Playback owner;
    /** Owner object ({@link Playback#owner}) of the animation that wrote {@link #explicit}; kept while held. */
    Object holder;
    UiValue explicit;
    Transition transition;
    UiValue transitionValue;
    /** Additive layers in claim order; empty for most channels. */
    final List<Add> adds = new ArrayList<>(0);

    Channel(String key, AnimProperty property) {
        this.key = key;
        this.property = property;
    }

    /** What the replacing layers show, or null for "the cascade" (additive deltas excluded). */
    UiValue display() {
        return explicit != null ? explicit : transitionValue;
    }

    /** {@code under} (what the replacing layers or the cascade show) plus every additive delta. */
    UiValue withAdds(UiValue under) {
        UiValue v = under;
        for (Add a : adds) {
            if (a.value != null) {
                v = property.add(v, a.value);
            }
        }
        return v;
    }

    Add add(Playback p) {
        for (Add a : adds) {
            if (a.owner == p) {
                return a;
            }
        }
        return null;
    }

    boolean empty() {
        return owner == null && explicit == null && transition == null && transitionValue == null && adds.isEmpty();
    }

    @Override
    public String toString() {
        return key + " " + property.target();
    }
}
