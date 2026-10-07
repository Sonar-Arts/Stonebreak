package com.openmason.main.systems.uiEditor.timeline;

import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.anim.UiAnimator;
import com.openmason.engine.ui.runtime.anim.UiClocks;

/**
 * Shows a clip at the Timeline's playhead on the designer's runtime instance (#295). It plays the
 * clip through the instance's own sampler, exactly as the game would, but:
 *
 * <ul>
 *   <li>it never touches the document: only the instance's animation channels change, so
 *       scrubbing is never an edit and never dirties the source;</li>
 *   <li>clip events go nowhere ({@link UiAnimator.Listener#NONE}): no script or host action runs,
 *       so a scrub cannot trigger anything irreversible;</li>
 *   <li>{@link #release} hands every channel it posed back to the cascade.</li>
 * </ul>
 */
public final class TimelineScrubber {

    /** Owner of the scrub playback in the animator (also what {@link UiAnimator#releaseHeld} releases). */
    private final Object owner = new Object() {
        @Override
        public String toString() {
            return "timeline";
        }
    };

    private UiDocumentInstance ui;
    private UiAnimationClip clip;
    private long token;
    private boolean playing;
    private boolean loop;
    private double time;

    /** The instance to show on; a new one (a rebuilt view) restarts the playback. */
    public void attach(UiDocumentInstance instance) {
        if (instance != ui) {
            ui = instance;
            token = 0;
            playing = false;
        }
    }

    public boolean isPlaying() {
        return playing;
    }

    /** The playhead in clip seconds. */
    public double time() {
        if (playing && ui != null && ui.animator().isRunning(token)) {
            double e = ui.animator().elapsed(token);
            double d = clip.duration();
            time = loop && d > 0 ? e % d : Math.min(e, d);
        } else if (playing) {
            playing = false; // a one-shot preview reached its end
            time = clip == null ? 0 : clip.duration();
        }
        return time;
    }

    /** Poses {@code c} at {@code seconds}, paused. Restarts the playback when the clip changed (an edit). */
    public void show(UiAnimationClip c, double seconds) {
        if (ui == null || c == null) {
            return;
        }
        time = Math.clamp(seconds, 0, c.duration());
        if (playing || !c.equals(clip) || !ui.animator().isRunning(token)) {
            start(c, LoopMode.ONCE, 0);
            playing = false;
        }
        ui.animator().seek(token, time);
    }

    /** Plays {@code c} from the playhead on the UI clock (looping when asked). */
    public void play(UiAnimationClip c, boolean looping) {
        if (ui == null || c == null) {
            return;
        }
        double from = time >= c.duration() ? 0 : time;
        loop = looping;
        start(c, looping ? LoopMode.LOOP : LoopMode.ONCE, 1);
        ui.animator().seek(token, from);
        playing = true;
    }

    /** Pauses on the current frame. */
    public void pause() {
        time();
        playing = false;
        if (ui != null && clip != null) {
            show(clip, time);
        }
    }

    /** Hands every channel the Timeline posed back to the cascade (leaving the Timeline, switching mode). */
    public void release() {
        if (ui != null) {
            ui.animator().releaseHeld(owner, 0);
        }
        token = 0;
        playing = false;
        clip = null;
    }

    private void start(UiAnimationClip c, LoopMode mode, double speed) {
        clip = c;
        ui.animator().clear(owner);
        token = ui.animator().play(owner, c, k -> k,
            new UiAnimator.PlayOptions(UiClocks.UI, speed, mode, 0, UiAnimator.Fill.HOLD, 0, true),
            UiAnimator.Listener.NONE);
        ui.animator().releaseHeld(owner, token); // channels of tracks the edited clip no longer has
    }
}
