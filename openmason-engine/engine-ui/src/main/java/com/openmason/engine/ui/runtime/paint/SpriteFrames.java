package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiSpriteSheet.Frame;

import java.util.List;

/**
 * Animated-sprite timing (#294): which frame shows at a document's UI time, and when the next
 * change is due, so a host repaints exactly on frame boundaries instead of every frame.
 *
 * <ul>
 *   <li>{@link LoopMode#ONCE}: plays through and holds the last frame.</li>
 *   <li>{@link LoopMode#LOOP}: repeats from the first frame.</li>
 *   <li>{@link LoopMode#PING_PONG}: plays forward then backward without repeating the end frames
 *       (0 1 2 1 0 1 2 ...).</li>
 * </ul>
 * A frame is shown on {@code [start, start + duration)}. Time is the document's UI clock, which
 * hosts advance with {@code UiDocumentView.frame(dt)}; frames therefore follow game time, pause
 * with it, and are reproducible in tests.
 */
public final class SpriteFrames {

    private SpriteFrames() {
    }

    /** Index into {@code frames} shown at {@code time} seconds (0 for a still or empty list). */
    public static int frameAt(List<Frame> frames, LoopMode loop, double time) {
        return (int) locate(frames, loop, time)[0];
    }

    /** UI time of the next frame change after {@code time}; {@code +∞} when the sprite will not change again. */
    public static double nextChange(List<Frame> frames, LoopMode loop, double time) {
        return locate(frames, loop, time)[1];
    }

    /** Length of one pass of the sequence (forward and back for ping-pong). */
    public static double period(List<Frame> frames, LoopMode loop) {
        double total = 0;
        for (Frame f : frames) {
            total += f.duration();
        }
        if (loop == LoopMode.PING_PONG && frames.size() > 2) {
            for (int i = frames.size() - 2; i >= 1; i--) {
                total += frames.get(i).duration();
            }
        }
        return total;
    }

    /** @return {frame index, absolute time of the next change} */
    private static double[] locate(List<Frame> frames, LoopMode loop, double time) {
        int n = frames.size();
        if (n <= 1) {
            return new double[]{0, Double.POSITIVE_INFINITY};
        }
        double period = period(frames, loop);
        if (!(period > 0)) {
            return new double[]{0, Double.POSITIVE_INFINITY};
        }
        double t = Math.max(0, time);
        double cycleStart;
        if (loop == LoopMode.ONCE) {
            if (t >= period) {
                return new double[]{n - 1, Double.POSITIVE_INFINITY};
            }
            cycleStart = 0;
        } else {
            cycleStart = Math.floor(t / period) * period;
        }
        double local = t - cycleStart;
        int steps = loop == LoopMode.PING_PONG ? 2 * n - 2 : n;
        double start = 0;
        for (int step = 0; step < steps; step++) {
            int index = step < n ? step : 2 * n - 2 - step;
            double end = start + frames.get(index).duration();
            if (local < end) {
                boolean last = loop == LoopMode.ONCE && step == steps - 1;
                return new double[]{index, last ? Double.POSITIVE_INFINITY : cycleStart + end};
            }
            start = end;
        }
        // floating-point edge at the very end of a cycle: the next cycle's first frame
        return new double[]{0, cycleStart + period + frames.getFirst().duration()};
    }
}
