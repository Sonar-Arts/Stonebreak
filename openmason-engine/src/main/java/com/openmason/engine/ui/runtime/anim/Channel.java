package com.openmason.engine.ui.runtime.anim;

import com.openmason.engine.format.omui.UiValue;

/**
 * One animated (element key, property) pair and its two layers (#295):
 *
 * <ol>
 *   <li><b>transition</b> (lower): a style transition easing the cascade's own changes;</li>
 *   <li><b>explicit</b> (upper): the value of the clip or tween that last claimed the channel,
 *       still held after it finished until released.</li>
 * </ol>
 * The element shows the explicit value when there is one, else the transition's, else the
 * cascade (the channel is then dropped).
 */
final class Channel {

    final String key;
    final AnimProperty property;
    /** Playback that writes {@link #explicit}; null once it finished and only holds. */
    Playback owner;
    /** Owner object ({@link Playback#owner}) of the animation that wrote {@link #explicit}; kept while held. */
    Object holder;
    UiValue explicit;
    Transition transition;
    UiValue transitionValue;

    Channel(String key, AnimProperty property) {
        this.key = key;
        this.property = property;
    }

    /** What the element should show, or null for "the cascade". */
    UiValue display() {
        return explicit != null ? explicit : transitionValue;
    }

    boolean empty() {
        return owner == null && explicit == null && transition == null && transitionValue == null;
    }

    @Override
    public String toString() {
        return key + " " + property.target();
    }
}
