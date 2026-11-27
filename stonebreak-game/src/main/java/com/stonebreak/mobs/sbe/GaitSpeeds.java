package com.stonebreak.mobs.sbe;

import com.openmason.engine.anim.AnimClipDocument;
import com.openmason.engine.animgraph.AnimGraph;
import com.openmason.engine.animgraph.AnimGraphInstance;
import com.openmason.engine.character.CharacterDefinition;
import com.openmason.engine.character.Retargeter;
import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.Rig;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * How fast a gait clip carries its body (#460): the ground speed at which its planted feet stay put. Measured from the
 * pose itself, model units per second at play rate 1: a foot is planted while it is within {@link #CONTACT_BAND} of its
 * vertical range above its lowest point, and the speed is how far the planted feet travel back per second of contact.
 * A clip whose feet never plant measures 0.
 *
 * <p>Also the contact-foot drift probe ({@link #drift}): the body moved at a ground speed, how far each planted foot
 * slides between touching down and lifting off (OpenMason #159 / #160 measure it the same way, there from lock curves).
 */
public final class GaitSpeeds {

    /** Share of a foot's vertical range above its lowest point that still counts as planted. */
    public static final float CONTACT_BAND = 0.02f;
    /** Sampling step, seconds. */
    public static final float DT = 1f / 120f;

    private static final String ONE_CLIP = """
        {"format": "omanimgraph", "version": "1.0", "slots": ["clip"], "root": "clip",
         "nodes": [{"id": "clip", "type": "clip", "slot": "clip"}]}""";

    private GaitSpeeds() {
    }

    /** One foot's samples over the probe: model-space position per frame. */
    private record Track(float[] x, float[] y, float[] z) {
        boolean[] contact() {
            float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
            for (float v : y) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            float band = min + Math.max(1e-5f, (max - min) * CONTACT_BAND);
            boolean[] down = new boolean[y.length];
            for (int i = 0; i < y.length; i++) down[i] = y[i] <= band;
            return down;
        }
    }

    /** The feet of {@code clip} posed on {@code rig} (through {@code retargeter} when not null) for {@code seconds}. */
    private static Track[] sample(Rig rig, CharacterDefinition character, AnimClipDocument clip, Retargeter retargeter,
                                  float seconds) {
        int[] feet = feet(rig, character);
        int frames = Math.round(seconds / DT);
        Track[] t = {new Track(new float[frames], new float[frames], new float[frames]),
            new Track(new float[frames], new float[frames], new float[frames])};
        try (AnimGraph graph = AnimGraph.read(ONE_CLIP);
             AnimGraphInstance inst = AnimGraphInstance.create(graph, rig, character)) {
            if (retargeter != null) inst.bindSlotRetargeted(0, clip, retargeter);
            else inst.bindSlot(0, clip);
            JointPalette p = new JointPalette();
            Matrix4f m = new Matrix4f();
            Vector3f at = new Vector3f();
            for (int f = 0; f < frames; f++) {
                inst.evaluate(DT, null, p);
                for (int k = 0; k < 2; k++) {
                    p.worldMatrix(feet[k], m).getTranslation(at);
                    t[k].x[f] = at.x;
                    t[k].y[f] = at.y;
                    t[k].z[f] = at.z;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return t;
    }

    private static int[] feet(Rig rig, CharacterDefinition character) {
        String l = character.roles().get("foot_l"), r = character.roles().get("foot_r");
        if (l == null || r == null) throw new IllegalArgumentException("the character maps no foot_l / foot_r");
        return new int[]{rig.find(l), rig.find(r)};
    }

    /**
     * The clip's ground speed, model units per second of the target body at play rate 1 (see the class doc). Samples
     * {@code seconds} after skipping the first quarter (a cycle settling from its first frame).
     */
    public static float stanceSpeed(Rig rig, CharacterDefinition character, AnimClipDocument clip,
                                    Retargeter retargeter, float seconds) {
        Track[] feet = sample(rig, character, clip, retargeter, seconds);
        double travel = 0.0;
        int frames = 0;
        for (Track t : feet) {
            boolean[] down = t.contact();
            for (int f = Math.max(1, t.y.length / 4); f < t.y.length; f++) {
                if (down[f] && down[f - 1]) {
                    travel += Math.hypot(t.x[f] - t.x[f - 1], t.z[f] - t.z[f - 1]);
                    frames++;
                }
            }
        }
        return frames == 0 ? 0f : (float) (travel / (frames * DT));
    }

    /** Contact-foot drift: per planted step, how far the foot moved over the ground while planted. */
    public record Drift(double perStep, int steps, List<Double> each) {}

    /**
     * Moves the body at {@code groundSpeed} (model units/s, along its facing -Z) while {@code clip} plays at
     * {@code rate}, and measures each planted foot's slide over the ground (world x/z, first to last planted frame),
     * skipping the first quarter.
     */
    public static Drift drift(Rig rig, CharacterDefinition character, AnimClipDocument clip, Retargeter retargeter,
                              float rate, float groundSpeed, float seconds) {
        // At play rate r the pose at clip time t is reached at real time t / r: sample the clip at its own rate and
        // place the body where it is after t / r seconds.
        Track[] feet = sample(rig, character, clip, retargeter, seconds * rate);
        List<Double> each = new ArrayList<>();
        for (Track t : feet) {
            boolean[] down = t.contact();
            int start = -1;
            for (int f = Math.max(1, t.y.length / 4); f < t.y.length; f++) {
                if (down[f] && !down[f - 1]) start = f;
                if (!down[f] && down[f - 1] && start >= 0) {
                    int end = f - 1;
                    double z0 = t.z[start] - groundSpeed * (start * DT / rate);
                    double z1 = t.z[end] - groundSpeed * (end * DT / rate);
                    each.add(Math.hypot(t.x[end] - t.x[start], z1 - z0));
                    start = -1;
                }
            }
        }
        double sum = 0.0;
        for (double d : each) sum += d;
        return new Drift(each.isEmpty() ? Double.NaN : sum / each.size(), each.size(), each);
    }
}
